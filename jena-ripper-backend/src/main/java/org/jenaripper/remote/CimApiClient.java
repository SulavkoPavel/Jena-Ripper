package org.jenaripper.remote;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jenaripper.config.RdfSourceProperties;
import org.jenaripper.exception.CimApiException;
import org.jenaripper.settings.StoredConnectionSettings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
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
@Slf4j
public class CimApiClient {
    private static final AtomicLong REQUEST_SEQUENCE = new AtomicLong();
    private static final int MODEL_PAGE_SIZE = 200;
    private static final long TOKEN_EXPIRY_SAFETY_MARGIN_SECONDS = 10;
    private static final long MINIMUM_TOKEN_TTL_SECONDS = 30;
    private static final long DEFAULT_TOKEN_TTL_SECONDS = 900;
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);
    private static final String OAUTH_TOKEN_PATH = "/oauth/token";
    private static final String BEARER_PREFIX = "Bearer ";

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
            log.warn("Проверка цепочки SSL-сертификатов CIM API отключена для endpoint {}", endpoint.authBaseUrl());
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
        JsonNode root = json(send(HttpMethod.GET,
                "/api/info-models?page=0&size=" + MODEL_PAGE_SIZE, null, true));
        JsonNode content = root.isArray() ? root : root.path("content");
        if (!content.isArray()) throw new CimApiException("CIM_API_INVALID_RESPONSE", "CIM App вернул некорректный список моделей.");
        return mapper.convertValue(content, new TypeReference<List<CimApiModel>>() {});
    }

    public List<CimMetamodelClass> metamodelClasses() {
        return mapper.convertValue(json(send(HttpMethod.GET,
                        "/api/core/metamodel/metamodels?simple=false", null, true)),
                new TypeReference<List<CimMetamodelClass>>() {});
    }

    public CimMetamodelClass metamodelClass(String classId) {
        return mapper.convertValue(json(send(HttpMethod.GET,
                        "/api/core/metamodel/classes/" + encode(classId), null, true)),
                CimMetamodelClass.class);
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
        return mapper.convertValue(json(send(HttpMethod.POST, "/api/query/select" + suffix, body, true)),
                new TypeReference<List<Map<String, String>>>() {});
    }

    public boolean ask(String query) {
        JsonNode value = json(send(HttpMethod.POST, "/api/query/ask", Map.of("query", query), true));
        return value.asBoolean();
    }

    public RedisCapabilities redisCapabilities() {
        String suffix = endpoint.modelId() == null ? "" : "?informationModelId=" + endpoint.modelId();
        return mapper.convertValue(redisJson(HttpMethod.GET,
                "/api/redis/inspection/capabilities" + suffix, null, true),
                RedisCapabilities.class);
    }

    public RedisResult redis(String command, List<String> args) {
        String suffix = endpoint.modelId() == null ? "" : "?informationModelId=" + endpoint.modelId();
        return mapper.convertValue(redisJson(HttpMethod.POST, "/api/redis/inspection/command" + suffix,
                Map.of("command", command, "args", args == null ? List.of() : args), true), RedisResult.class);
    }

    public RedisRulesResult redisRules(String resourceUri) {
        String suffix = "?resourceUri=" + encode(resourceUri);
        if (endpoint.modelId() != null) suffix += "&informationModelId=" + endpoint.modelId();
        return mapper.convertValue(redisJson(HttpMethod.GET,
                "/api/redis/inspection/rules" + suffix, null, true),
                RedisRulesResult.class);
    }

    public String construct(String query, int limit, boolean useOwnerRules) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", normalizeOuterLimit(query));
        if (!hasOuterLimit(query)) body.put("limit", limit);
        body.put("format", "ttl");
        body.put("useOwnerRules", useOwnerRules);
        return send(HttpMethod.POST, "/api/query/construct", body, true).body();
    }

    public Long modelId() { return endpoint.modelId(); }

    private HttpResponse<String> send(HttpMethod method, String path, Object body, boolean authenticated) {
        return sendTo(baseUrl(), method, path, body, authenticated);
    }

    private HttpResponse<String> sendTo(String apiBaseUrl, HttpMethod method, String path, Object body,
                                        boolean authenticated) {
        return sendTo(apiBaseUrl, method, path, body, authenticated, true);
    }

    private HttpResponse<String> sendTo(String apiBaseUrl, HttpMethod method, String path, Object body,
                                        boolean authenticated, boolean retry) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(apiBaseUrl + path)).timeout(endpoint.readTimeout())
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
            if (authenticated) builder.header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + accessToken());
            if (body == null) builder.method(method.name(), HttpRequest.BodyPublishers.noBody());
            else builder.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).method(method.name(),
                    HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            HttpResponse<String> response = exchange(builder.build(), "business-api");
            if (response.statusCode() == HttpStatus.UNAUTHORIZED.value() && authenticated && retry) {
                token = null;
                return sendTo(apiBaseUrl, method, path, body, true, false);
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

    private JsonNode redisJson(HttpMethod method, String path, Object body, boolean retry) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path)).timeout(endpoint.readTimeout())
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + accessToken());
            if (body == null) builder.method(method.name(), HttpRequest.BodyPublishers.noBody());
            else builder.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).method(method.name(),
                    HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            HttpResponse<String> response = exchange(builder.build(), "redis-api");
            if (response.statusCode() == HttpStatus.UNAUTHORIZED.value() && retry) {
                token = null;
                return redisJson(method, path, body, false);
            }
            if (HttpStatusCode.valueOf(response.statusCode()).is2xxSuccessful()) return json(response);
            JsonNode error;
            try { error = mapper.readTree(response.body()); }
            catch (IOException ignored) { error = mapper.createObjectNode(); }
            String type = error.path("type").asText();
            String message = error.path("message").asText();
            if (response.statusCode() == HttpStatus.UNAUTHORIZED.value()) {
                throw new CimApiException("CIM_REDIS_AUTH", "Сессия CIM App истекла. Повторно авторизуйтесь.");
            }
            if (response.statusCode() == HttpStatus.FORBIDDEN.value()) {
                throw new CimApiException("CIM_REDIS_FORBIDDEN", "У пользователя нет прав на Redis API CIM App.");
            }
            if (response.statusCode() == HttpStatus.NOT_FOUND.value()) {
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
        if (token != null && token.expiresAt().isAfter(
                Instant.now().plusSeconds(TOKEN_EXPIRY_SAFETY_MARGIN_SECONDS))) return token.accessToken();
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
            HttpRequest request = HttpRequest.newBuilder(URI.create(authBaseUrl() + OAUTH_TOKEN_PATH))
                    .timeout(endpoint.readTimeout())
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(form.toString())).build();
            HttpResponse<String> response = exchange(request, "oauth");
            if (response.statusCode() == HttpStatus.BAD_REQUEST.value()
                    || response.statusCode() == HttpStatus.UNAUTHORIZED.value()) {
                throw new CimApiException("CIM_API_AUTH_FAILED", "CIM App отклонил логин или пароль.");
            }
            ensureSuccess(response, HttpMethod.POST, OAUTH_TOKEN_PATH);
            JsonNode json = json(response);
            String access = json.path("accessToken").asText();
            if (access.isBlank()) throw new CimApiException("CIM_API_AUTH_FAILED", "CIM App не вернул access token.");
            long expires = Math.max(MINIMUM_TOKEN_TTL_SECONDS,
                    json.path("expiresIn").asLong(DEFAULT_TOKEN_TTL_SECONDS));
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

    private void ensureSuccess(HttpResponse<String> response, HttpMethod method, String path) {
        if (HttpStatusCode.valueOf(response.statusCode()).is2xxSuccessful()) return;
        if (response.statusCode() == HttpStatus.UNAUTHORIZED.value()) {
            throw new CimApiException("CIM_API_AUTH_FAILED",
                    "Сессия CIM App истекла. Повторно проверьте подключение.");
        }
        if (response.statusCode() == HttpStatus.FORBIDDEN.value()) {
            throw new CimApiException("CIM_API_FORBIDDEN",
                    "У пользователя нет прав на этот API CIM App. Требуется роль ADMIN.");
        }
        if (response.statusCode() == HttpStatus.NOT_FOUND.value()) {
            String endpointName = path.startsWith("/api/core/metamodel/")
                    ? "Base URL, prefix /api/core" : "Base URL";
            throw new CimApiException("CIM_API_DATASET_NOT_FOUND",
                    "CIM App вернул HTTP 404 для " + method + " " + path
                            + ". Проверьте " + endpointName + " и наличие endpoint в установленной версии CIM App.");
        }
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
        return (value == null ? "" : value.replaceAll("/+$", "")) + OAUTH_TOKEN_PATH;
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
        log.info("Запрос CIM [{}] начат: stage={}, method={}, endpoint={}",
                requestId, stage, request.method(), request.uri());
        try {
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            if (HttpStatusCode.valueOf(response.statusCode()).is2xxSuccessful()) {
                log.info("Запрос CIM [{}] завершён: stage={}, status={}, durationMs={}, endpoint={}",
                        requestId, stage, response.statusCode(), elapsedMs, response.uri());
            } else {
                log.warn("Запрос CIM [{}] завершился ошибкой: stage={}, status={}, durationMs={}, endpoint={}, contentType={}",
                        requestId, stage, response.statusCode(), elapsedMs, response.uri(),
                        response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse("отсутствует"));
            }
            return response;
        } catch (IOException | InterruptedException exception) {
            long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            Throwable root = rootCause(exception);
            log.warn("Транспортная ошибка запроса CIM [{}]: stage={}, durationMs={}, endpoint={}, cause={}: {}",
                    requestId, stage, elapsedMs, request.uri(), root.getClass().getName(), root.getMessage(), exception);
            throw exception;
        }
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
        if (value == null) {
            return new Endpoint("", "", "", "", null,
                    DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT, false);
        }
        return new Endpoint(value.baseUrl(), authBaseUrl(value.baseUrl(), value.authBaseUrl()), value.username(), value.password(), value.modelId(),
                value.connectTimeout() == null ? DEFAULT_CONNECT_TIMEOUT : value.connectTimeout(),
                value.readTimeout() == null ? DEFAULT_READ_TIMEOUT : value.readTimeout(),
                value.trustUntrustedCertificates());
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
    public record CimMetamodelClass(
            String id,
            String label,
            String labelRu,
            Boolean show,
            Boolean enumeration,
            Boolean compound,
            Boolean dictionary,
            Boolean isCimDataType,
            List<String> children,
            List<String> parents,
            List<CimMetamodelAttribute> attributes,
            List<CimMetamodelAssociation> associations) {}
    public record CimMetamodelAttribute(
            Long id,
            String name,
            String label,
            String labelRu,
            String type,
            Float factor,
            String unit,
            String range,
            Integer orderNum,
            String dataType,
            String dataTypeInfo,
            Boolean show) {}
    public record CimMetamodelAssociation(
            Long id,
            String name,
            String label,
            String labelRu,
            String range,
            Boolean show,
            List<String> ranges,
            List<CimMetamodelRangeNeed> rangesNeed,
            String dataType,
            String dataTypeInfo,
            Integer isTable,
            List<String> autoCreate,
            String inverseRoleName) {}
    public record CimMetamodelRangeNeed(String range, Long need) {}
    public record RedisRulesPermissions(List<String> read, List<String> readTop, List<String> write) {}
    public record RedisRulesKey(String key, String role, String type, long size) {}
}
