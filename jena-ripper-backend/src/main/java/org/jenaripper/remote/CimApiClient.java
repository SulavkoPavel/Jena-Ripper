package org.jenaripper.remote;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jenaripper.config.RdfSourceProperties;
import org.jenaripper.settings.StoredConnectionSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

@Component
public class CimApiClient {
    private static final Logger log = LoggerFactory.getLogger(CimApiClient.class);
    private static final AtomicLong REQUEST_SEQUENCE = new AtomicLong();

    private final ObjectMapper mapper;
    private final Endpoint endpoint;
    private final HttpClient http;
    private volatile Token token;

    @Autowired
    public CimApiClient(ObjectMapper mapper, RdfSourceProperties properties) {
        this(mapper, endpoint(properties));
    }

    private CimApiClient(ObjectMapper mapper, Endpoint endpoint) {
        this.mapper = mapper;
        this.endpoint = endpoint;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(endpoint.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL);
        SSLContext systemSslContext = endpoint.trustUntrustedCertificates()
                ? untrustedCertificatesSslContext() : systemSslContext();
        if (endpoint.trustUntrustedCertificates()) {
            log.warn("CIM API certificate-chain verification is disabled for endpoint {}", endpoint.authBaseUrl());
        }
        if (systemSslContext != null) builder.sslContext(systemSslContext);
        this.http = builder.build();
    }

    public static CimApiClient forSettings(ObjectMapper mapper, StoredConnectionSettings.CimApi settings) {
        return new CimApiClient(mapper, new Endpoint(settings.baseUrl(), authBaseUrl(settings.baseUrl(), settings.authBaseUrl()), settings.username(), settings.password(),
                settings.modelId(), Duration.ofMillis(settings.connectTimeoutMs()), Duration.ofMillis(settings.readTimeoutMs()),
                settings.trustUntrustedCertificates()));
    }

    public List<CimApiModel> models() {
        JsonNode root = json(send("GET", "/api/info-models?page=0&size=200", null, true));
        JsonNode content = root.isArray() ? root : root.path("content");
        if (!content.isArray()) throw new CimApiException("CIM_API_INVALID_RESPONSE", "CIM App вернул некорректный список моделей.");
        return mapper.convertValue(content, new TypeReference<List<CimApiModel>>() {});
    }

    public void verify(Long modelId) {
        List<CimApiModel> models = models();
        if (modelId != null && models.stream().noneMatch(model -> modelId.equals(model.id()))) {
            throw new CimApiException("CIM_API_DATASET_NOT_FOUND", "Выбранная информационная модель недоступна в CIM App.");
        }
    }

    public List<Map<String, String>> select(String query, int limit, boolean useOwnerRules) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", normalizeOuterLimit(query));
        if (!hasOuterLimit(query)) body.put("limit", limit);
        body.put("useOwnerRules", useOwnerRules);
        String suffix = endpoint.modelId() == null ? "" : "?modelId=" + endpoint.modelId();
        return mapper.convertValue(json(send("POST", "/api/query/select" + suffix, body, true)),
                new TypeReference<List<Map<String, String>>>() {});
    }

    public boolean ask(String query) {
        JsonNode value = json(send("POST", "/api/query/ask", Map.of("query", query), true));
        return value.asBoolean();
    }

    public RedisCapabilities redisCapabilities() {
        String suffix = endpoint.modelId() == null ? "" : "?informationModelId=" + endpoint.modelId();
        return mapper.convertValue(redisJson("GET", "/api/redis/inspection/capabilities" + suffix, null, true),
                RedisCapabilities.class);
    }

    public RedisResult redis(String command, List<String> args) {
        String suffix = endpoint.modelId() == null ? "" : "?informationModelId=" + endpoint.modelId();
        return mapper.convertValue(redisJson("POST", "/api/redis/inspection/command" + suffix,
                Map.of("command", command, "args", args == null ? List.of() : args), true), RedisResult.class);
    }

    public RedisRulesResult redisRules(String resourceUri) {
        String suffix = "?resourceUri=" + encode(resourceUri);
        if (endpoint.modelId() != null) suffix += "&informationModelId=" + endpoint.modelId();
        return mapper.convertValue(redisJson("GET", "/api/redis/inspection/rules" + suffix, null, true),
                RedisRulesResult.class);
    }

    public String construct(String query, int limit, boolean useOwnerRules) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", normalizeOuterLimit(query));
        if (!hasOuterLimit(query)) body.put("limit", limit);
        body.put("format", "ttl");
        body.put("useOwnerRules", useOwnerRules);
        return send("POST", "/api/query/construct", body, true).body();
    }

    public Long modelId() { return endpoint.modelId(); }

    private HttpResponse<String> send(String method, String path, Object body, boolean authenticated) {
        return send(method, path, body, authenticated, true);
    }

    private HttpResponse<String> send(String method, String path, Object body, boolean authenticated, boolean retry) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path)).timeout(endpoint.readTimeout())
                    .header("Accept", "application/json");
            if (authenticated) builder.header("Authorization", "Bearer " + accessToken());
            if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
            else builder.header("Content-Type", "application/json").method(method,
                    HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            HttpResponse<String> response = exchange(builder.build(), "business-api");
            if (response.statusCode() == 401 && authenticated && retry) {
                token = null;
                return send(method, path, body, true, false);
            }
            ensureSuccess(response, method, path);
            return response;
        } catch (java.net.http.HttpTimeoutException exception) {
            throw new CimApiException("CIM_API_TIMEOUT", "CIM App не ответил за установленное время.");
        } catch (ConnectException exception) {
            throw new CimApiException("CIM_API_UNAVAILABLE", "CIM App недоступен. Проверьте URL и состояние приложения.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CimApiException("CIM_API_UNAVAILABLE", "Запрос к CIM App был прерван.");
        } catch (IOException | IllegalArgumentException exception) {
            throw new CimApiException("CIM_API_UNAVAILABLE", "Не удалось выполнить запрос к CIM App.");
        }
    }

    private JsonNode redisJson(String method, String path, Object body, boolean retry) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path)).timeout(endpoint.readTimeout())
                    .header("Accept", "application/json").header("Authorization", "Bearer " + accessToken());
            if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
            else builder.header("Content-Type", "application/json").method(method,
                    HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            HttpResponse<String> response = exchange(builder.build(), "redis-api");
            if (response.statusCode() == 401 && retry) {
                token = null;
                return redisJson(method, path, body, false);
            }
            if (response.statusCode() >= 200 && response.statusCode() < 300) return json(response);
            JsonNode error;
            try { error = mapper.readTree(response.body()); }
            catch (IOException ignored) { error = mapper.createObjectNode(); }
            String type = error.path("type").asText();
            String message = error.path("message").asText();
            if (response.statusCode() == 401) {
                throw new CimApiException("CIM_REDIS_AUTH", "Сессия CIM App истекла. Повторно авторизуйтесь.");
            }
            if (response.statusCode() == 403) {
                throw new CimApiException("CIM_REDIS_FORBIDDEN", "У пользователя нет прав на Redis API CIM App.");
            }
            if (response.statusCode() == 404) {
                throw new CimApiException("CIM_REDIS_UNAVAILABLE", "Redis API отсутствует в текущей версии CIM App.");
            }
            if (!type.isBlank()) {
                throw new CimApiException(type, message.isBlank() ? "CIM App отклонил Redis-команду." : message);
            }
            throw new CimApiException("CIM_REDIS_UNAVAILABLE", "Redis API CIM App вернул ошибку HTTP " + response.statusCode() + ".");
        } catch (java.net.http.HttpTimeoutException exception) {
            throw new CimApiException("CIM_REDIS_TIMEOUT", "Redis API CIM App не ответил за установленное время.");
        } catch (ConnectException exception) {
            throw new CimApiException("CIM_REDIS_UNAVAILABLE", "CIM App недоступен для выполнения Redis-команды.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CimApiException("CIM_REDIS_UNAVAILABLE", "Redis-запрос к CIM App был прерван.");
        } catch (CimApiException exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException exception) {
            throw new CimApiException("CIM_REDIS_UNAVAILABLE", "Не удалось выполнить Redis-запрос через CIM App.");
        }
    }

    private synchronized String accessToken() {
        if (token != null && token.expiresAt().isAfter(Instant.now().plusSeconds(10))) return token.accessToken();
        if (token != null && token.refreshToken() != null && !token.refreshToken().isBlank()) {
            try { token = requestToken("refreshToken", null, null, token.refreshToken()); return token.accessToken(); }
            catch (CimApiException ignored) { token = null; }
        }
        token = requestToken("clientCredentials", endpoint.username(), endpoint.password(), null);
        return token.accessToken();
    }

    private Token requestToken(String grantType, String username, String password, String refreshToken) {
        StringBuilder form = new StringBuilder("grantType=").append(encode(grantType));
        if (username != null) form.append("&clientId=").append(encode(username));
        if (password != null) form.append("&clientSecret=").append(encode(password));
        if (refreshToken != null) form.append("&refreshToken=").append(encode(refreshToken));
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(authBaseUrl() + "/oauth/token"))
                    .timeout(endpoint.readTimeout()).header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form.toString())).build();
            HttpResponse<String> response = exchange(request, "oauth");
            if (response.statusCode() == 400 || response.statusCode() == 401) {
                throw new CimApiException("CIM_API_AUTH_FAILED", "CIM App отклонил логин или пароль.");
            }
            ensureSuccess(response, "POST", "/oauth/token");
            JsonNode json = json(response);
            String access = json.path("accessToken").asText();
            if (access.isBlank()) throw new CimApiException("CIM_API_AUTH_FAILED", "CIM App не вернул access token.");
            long expires = Math.max(30, json.path("expiresIn").asLong(900));
            return new Token(access, json.path("refreshToken").asText(null), Instant.now().plusSeconds(expires));
        } catch (java.net.http.HttpTimeoutException exception) {
            throw new CimApiException("CIM_API_TIMEOUT", "CIM App не ответил при авторизации.");
        } catch (SSLHandshakeException exception) {
            throw new CimApiException("CIM_API_TLS_ERROR",
                    "Не удалось проверить HTTPS-сертификат CIM App. Добавьте сертификат организации в системное хранилище доверенных сертификатов.");
        } catch (ConnectException exception) {
            throw new CimApiException("CIM_API_UNAVAILABLE", connectionFailureMessage(exception));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CimApiException("CIM_API_UNAVAILABLE", "Авторизация в CIM App была прервана.");
        } catch (IOException | IllegalArgumentException exception) {
            throw new CimApiException("CIM_API_UNAVAILABLE", "Не удалось авторизоваться в CIM App.");
        }
    }

    private void ensureSuccess(HttpResponse<String> response, String method, String path) {
        if (response.statusCode() >= 200 && response.statusCode() < 300) return;
        if (response.statusCode() == 401) throw new CimApiException("CIM_API_AUTH_FAILED", "Сессия CIM App истекла. Повторно проверьте подключение.");
        if (response.statusCode() == 403) throw new CimApiException("CIM_API_FORBIDDEN", "У пользователя нет прав на этот API CIM App. Требуется роль ADMIN.");
        if (response.statusCode() == 404) throw new CimApiException("CIM_API_DATASET_NOT_FOUND",
                "CIM App вернул HTTP 404 для " + method + " " + path
                        + ". Проверьте Base URL и наличие endpoint в установленной версии CIM App.");
        throw new CimApiException("CIM_API_ERROR", "CIM App вернул ошибку HTTP " + response.statusCode() + ".");
    }

    private JsonNode json(HttpResponse<String> response) {
        try { return mapper.readTree(response.body()); }
        catch (IOException exception) { throw new CimApiException("CIM_API_INVALID_RESPONSE", "CIM App вернул некорректный JSON."); }
    }

    private String baseUrl() {
        String value = endpoint.baseUrl() == null ? "" : endpoint.baseUrl().trim().replaceAll("/+$", "");
        if (!(value.startsWith("http://") || value.startsWith("https://"))) {
            throw new CimApiException("CIM_API_UNAVAILABLE", "URL CIM App должен начинаться с http:// или https://.");
        }
        return value;
    }

    private String authBaseUrl() {
        String value = authBaseUrl(endpoint.baseUrl(), endpoint.authBaseUrl());
        if (!(value.startsWith("http://") || value.startsWith("https://"))) {
            throw new CimApiException("CIM_API_UNAVAILABLE", "Auth URL CIM App должен начинаться с http:// или https://.");
        }
        return value;
    }

    private static String authBaseUrl(String baseUrl, String authBaseUrl) {
        String value = authBaseUrl == null || authBaseUrl.isBlank() ? baseUrl : authBaseUrl;
        return value == null ? "" : value.trim().replaceAll("/+$", "");
    }

    private static SSLContext systemSslContext() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        String keyStoreType = osName.contains("win") ? "Windows-ROOT"
                : osName.contains("mac") ? "KeychainStore" : null;
        if (keyStoreType == null) return null;
        try {
            KeyStore keyStore = KeyStore.getInstance(keyStoreType);
            keyStore.load(null, null);
            TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(keyStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagers.getTrustManagers(), null);
            return context;
        } catch (GeneralSecurityException | IOException ignored) {
            return null;
        }
    }

    private static SSLContext untrustedCertificatesSslContext() {
        try {
            X509TrustManager trustManager = new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new X509TrustManager[]{trustManager}, new SecureRandom());
            return context;
        } catch (GeneralSecurityException exception) {
            throw new CimApiException("CIM_API_TLS_ERROR", "Не удалось включить доверие сертификату для этого профиля.");
        }
    }

    private String connectionFailureMessage(ConnectException exception) {
        Throwable root = rootCause(exception);
        String message = root.getMessage() == null ? "" : root.getMessage().toLowerCase();
        if (root instanceof java.net.UnknownHostException
                || root.getClass().getSimpleName().contains("UnresolvedAddress")) {
            return "Не удалось найти хост CIM App: " + authHostForMessage() + ".";
        }
        if (message.contains("refused") || message.contains("отказ")) {
            return "CIM App отклонил соединение с " + authHostForMessage()
                    + ". Проверьте протокол и порт.";
        }
        return "Не удалось подключиться к CIM App по адресу " + authHostForMessage()
                + ". Подробная причина записана в backend-лог.";
    }

    private String authEndpointForLog() {
        String value = endpoint.authBaseUrl() == null || endpoint.authBaseUrl().isBlank()
                ? endpoint.baseUrl() : endpoint.authBaseUrl();
        return (value == null ? "" : value.replaceAll("/+$", "")) + "/oauth/token";
    }

    private String authHostForMessage() {
        try {
            URI uri = URI.create(authEndpointForLog());
            int port = uri.getPort();
            return uri.getHost() + (port < 0 ? "" : ":" + port);
        } catch (RuntimeException ignored) {
            return authEndpointForLog();
        }
    }

    private static Throwable rootCause(Throwable exception) {
        Throwable current = exception;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current;
    }

    private HttpResponse<String> exchange(HttpRequest request, String stage) throws IOException, InterruptedException {
        long requestId = REQUEST_SEQUENCE.incrementAndGet();
        long started = System.nanoTime();
        log.info("CIM request [{}] started: stage={}, method={}, endpoint={}",
                requestId, stage, request.method(), request.uri());
        try {
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("CIM request [{}] completed: stage={}, status={}, durationMs={}, endpoint={}",
                        requestId, stage, response.statusCode(), elapsedMs, response.uri());
            } else {
                log.warn("CIM request [{}] failed: stage={}, status={}, durationMs={}, endpoint={}, contentType={}, responseBody={}",
                        requestId, stage, response.statusCode(), elapsedMs, response.uri(),
                        response.headers().firstValue("Content-Type").orElse("<absent>"),
                        safeResponseBody(request.uri().getPath(), response.body()));
            }
            return response;
        } catch (IOException | InterruptedException exception) {
            long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            Throwable root = rootCause(exception);
            log.warn("CIM request [{}] transport failure: stage={}, durationMs={}, endpoint={}, cause={}: {}",
                    requestId, stage, elapsedMs, request.uri(), root.getClass().getName(), root.getMessage(), exception);
            throw exception;
        }
    }

    private static String safeResponseBody(String path, String body) {
        if (path != null && path.endsWith("/oauth/token")) return "<redacted>";
        if (body == null || body.isBlank()) return "<empty>";
        String value = body.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s{2,}", " ").trim();
        value = value.replaceAll("(?i)(accessToken|refreshToken|clientSecret|password)\\s*[=:]\\s*[^, }]+", "$1=<redacted>");
        return value.length() <= 1200 ? value : value.substring(0, 1200) + "…";
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static boolean hasOuterLimit(String query) {
        if (query == null) return false;
        return java.util.regex.Pattern.compile("(?is)\\blimit\\s+\\d+\\s*(?:offset\\s+\\d+\\s*)?$", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(query.strip()).find();
    }
    private static String normalizeOuterLimit(String query) {
        if (query == null) return null;
        return java.util.regex.Pattern.compile("(?is)\\blimit(?=\\s+\\d+\\s*(?:offset\\s+\\d+\\s*)?$)")
                .matcher(query).replaceFirst("limit");
    }
    private static Endpoint endpoint(RdfSourceProperties properties) {
        RdfSourceProperties.CimApi value = properties.cimApi();
        if (value == null) return new Endpoint("", "", "", "", null, Duration.ofSeconds(5), Duration.ofSeconds(30), false);
        return new Endpoint(value.baseUrl(), authBaseUrl(value.baseUrl(), value.authBaseUrl()), value.username(), value.password(), value.modelId(),
                value.connectTimeout() == null ? Duration.ofSeconds(5) : value.connectTimeout(),
                value.readTimeout() == null ? Duration.ofSeconds(30) : value.readTimeout(), value.trustUntrustedCertificates());
    }

    private record Endpoint(String baseUrl, String authBaseUrl, String username, String password, Long modelId,
                            Duration connectTimeout, Duration readTimeout, boolean trustUntrustedCertificates) {}
    private record Token(String accessToken, String refreshToken, Instant expiresAt) {}
    public record RedisCapabilities(boolean available, Long datasetId, String modelType, List<String> commands,
                                    String message) {}
    public record RedisResult(String command, String resultType, JsonNode value, String cursor, int count,
                              Long datasetId, String modelType) {}
    public record RedisRulesResult(String resourceId, Long datasetId, String modelType,
                                   RedisRulesPermissions permissions, List<RedisRulesKey> technicalKeys,
                                   long executionTimeMs) {}
    public record RedisRulesPermissions(List<String> read, List<String> readTop, List<String> write) {}
    public record RedisRulesKey(String key, String role, String type, long size) {}
}
