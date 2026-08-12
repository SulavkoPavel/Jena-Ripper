package org.jenaripper.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.tdb2.TDB2Factory;
import org.jenaripper.config.JenaRipperProperties;
import org.jenaripper.dto.*;
import org.jenaripper.jena.JenaReadExecutor;
import org.jenaripper.remote.CimApiClient;
import org.jenaripper.remote.CimApiModel;
import org.jenaripper.config.RdfSourceProperties;
import org.jenaripper.config.DatasetRuntimeState;
import org.jenaripper.settings.StoredConnectionProfiles;
import org.jenaripper.settings.StoredConnectionSettings;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.DriverManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

@Service
public class ConnectionSettingsService {
    private static final String DEFAULT_PROFILE_ID = "default";
    private static final String DEFAULT_PROFILE_NAME = "Текущее подключение";

    private final JenaRipperProperties properties;
    private final JenaReadExecutor jena;
    private final ObjectMapper objectMapper;
    private final RdfSourceProperties sourceProperties;
    private final DatasetRuntimeState datasetRuntimeState;

    public ConnectionSettingsService(JenaRipperProperties properties, JenaReadExecutor jena, ObjectMapper objectMapper,
                                     RdfSourceProperties sourceProperties, DatasetRuntimeState datasetRuntimeState) {
        this.properties = properties;
        this.jena = jena;
        this.objectMapper = objectMapper;
        this.sourceProperties = sourceProperties;
        this.datasetRuntimeState = datasetRuntimeState;
    }

    public ConnectionSettingsDto current() {
        StoredConnectionProfiles store = readProfiles(true);
        return safe(findProfile(store, store.activeProfileId()));
    }

    public ConnectionProfilesDto profiles() {
        return safe(readProfiles(true));
    }

    public ConnectionProfilesDto createProfile(CreateConnectionProfileRequest request) {
        String name = validatedName(request == null ? null : request.name());
        StoredConnectionProfiles store = readProfiles(true);
        ensureUniqueName(store, name, null);
        StoredConnectionProfiles.Profile source = findProfile(store,
                request.sourceProfileId() == null ? store.activeProfileId() : request.sourceProfileId());
        boolean copy = Boolean.TRUE.equals(request.copyCurrent());
        StoredConnectionProfiles.Profile created = new StoredConnectionProfiles.Profile(
                UUID.randomUUID().toString(), name,
                copy ? source.jena() : new StoredConnectionSettings.Jena("tdb2", ""),
                copy ? source.postgres() : new StoredConnectionSettings.Postgres("", 5432, "", "public", "", ""),
                copy ? source.redis() : new StoredConnectionSettings.Redis("", 6379, 0, "", "", 5000));
        List<StoredConnectionProfiles.Profile> profiles = new ArrayList<>(store.profiles());
        profiles.add(created);
        write(new StoredConnectionProfiles(store.activeProfileId(), profiles));
        return safe(new StoredConnectionProfiles(store.activeProfileId(), profiles));
    }

    public ConnectionProfilesDto renameProfile(String profileId, RenameConnectionProfileRequest request) {
        String name = validatedName(request == null ? null : request.name());
        StoredConnectionProfiles store = readProfiles(true);
        ensureUniqueName(store, name, profileId);
        List<StoredConnectionProfiles.Profile> profiles = store.profiles().stream().map(profile ->
                profile.id().equals(profileId)
                        ? new StoredConnectionProfiles.Profile(profile.id(), name, profile.jena(), profile.postgres(), profile.redis())
                        : profile).toList();
        if (profiles.stream().noneMatch(profile -> profile.id().equals(profileId))) {
            throw new IllegalArgumentException("Профиль подключения не найден.");
        }
        StoredConnectionProfiles updated = new StoredConnectionProfiles(store.activeProfileId(), profiles);
        write(updated);
        return safe(updated);
    }

    public ConnectionProfilesDto deleteProfile(String profileId) {
        StoredConnectionProfiles store = readProfiles(true);
        if (store.profiles().size() <= 1) throw new IllegalArgumentException("Нельзя удалить последний профиль.");
        if (store.activeProfileId().equals(profileId)) {
            throw new IllegalArgumentException("Сначала выберите и сохраните другой активный профиль.");
        }
        List<StoredConnectionProfiles.Profile> profiles = store.profiles().stream()
                .filter(profile -> !profile.id().equals(profileId)).toList();
        if (profiles.size() == store.profiles().size()) throw new IllegalArgumentException("Профиль подключения не найден.");
        StoredConnectionProfiles updated = new StoredConnectionProfiles(store.activeProfileId(), profiles);
        write(updated);
        return safe(updated);
    }

    public ConnectionSettingsSaveResponse save(ConnectionSettingsUpdateRequest request) {
        StoredConnectionProfiles store = readProfiles(true);
        return saveProfile(store.activeProfileId(), request);
    }

    public ConnectionSettingsSaveResponse saveProfile(String profileId, ConnectionSettingsUpdateRequest request) {
        validateRequest(request);
        StoredConnectionProfiles store = readProfiles(true);
        StoredConnectionProfiles.Profile existing = findProfile(store, profileId);
        StoredConnectionProfiles.Profile updated = storedProfile(existing.id(), existing.name(), request, existing);
        List<StoredConnectionProfiles.Profile> profiles = store.profiles().stream()
                .map(profile -> profile.id().equals(profileId) ? updated : profile).toList();
        write(new StoredConnectionProfiles(profileId, profiles));
        return new ConnectionSettingsSaveResponse(true, true,
                "Профиль сохранён. Подключение будет применено автоматически.");
    }

    public ConnectionTestResponse testJena(ConnectionSettingsUpdateRequest.JenaSettings request, String profileId) {
        return remote(request) ? testCimApi(request, profileId) : testLocalTdb2(request);
    }

    private ConnectionTestResponse testCimApi(ConnectionSettingsUpdateRequest.JenaSettings request, String profileId) {
        validateCim(request.cimApi(), false);
        StoredConnectionSettings.CimApi settings = cimSettings(request, profileId);
        CimApiClient client = CimApiClient.forSettings(objectMapper, settings);
        List<CimApiModel> models = client.models();
        if (settings.modelId() != null) client.verify(settings.modelId());
        return new ConnectionTestResponse(true, RdfSourceProperties.CIM_API, settings.modelId() == null
                ? "Авторизация успешна. Выберите информационную модель. Доступно: " + models.size()
                : "Авторизация и модель доступны");
    }

    private ConnectionTestResponse testLocalTdb2(ConnectionSettingsUpdateRequest.JenaSettings request) {
        validateJena(request);
        String type = request.type().trim().toUpperCase();
        if (!"TDB2".equals(type)) throw new IllegalArgumentException("Поддерживается только локальный TDB2 Dataset.");
        Path requested = Path.of(request.path()).toAbsolutePath().normalize();
        if (!Files.isDirectory(requested)) throw new IllegalArgumentException("Указанная папка Dataset не существует.");
        Path active = Path.of(properties.dataset().path()).toAbsolutePath().normalize();
        try {
            if (requested.equals(active)) {
                if (datasetRuntimeState.fallback()) {
                    throw new ConnectionTestException(datasetRuntimeState.error());
                }
                jena.read(() -> jena.dataset().isInTransaction());
            } else {
                Dataset dataset = TDB2Factory.connectDataset(requested.toString());
                try {
                    dataset.begin(ReadWrite.READ);
                    try { dataset.asDatasetGraph().find().hasNext(); } finally { dataset.end(); }
                } finally { dataset.close(); }
            }
            return new ConnectionTestResponse(true, "TDB2", "Доступен");
        } catch (ConnectionTestException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ConnectionTestException("Не удалось открыть Dataset. Проверьте путь и отсутствие блокировки.");
        }
    }

    public List<CimApiModel> cimModels(ConnectionSettingsUpdateRequest.JenaSettings request, String profileId) {
        validateCim(request == null ? null : request.cimApi(), false);
        return CimApiClient.forSettings(objectMapper, cimSettings(request, profileId)).models();
    }

    public ConnectionTestResponse testPostgres(ConnectionSettingsUpdateRequest.PostgresSettings request, String profileId) {
        validatePostgres(request);
        String password = passwordOrCurrent(request.password(), profilePassword(profileId, true));
        String url = postgresUrl(request.host(), request.port(), request.database());
        Properties connectionProperties = new Properties();
        connectionProperties.setProperty("user", request.username().trim());
        connectionProperties.setProperty("password", password);
        connectionProperties.setProperty("connectTimeout", String.valueOf(Math.max(1, properties.ownerRules().connectTimeout().toSeconds())));
        try (Connection connection = DriverManager.getConnection(url, connectionProperties);
             PreparedStatement statement = connection.prepareStatement("SELECT 1")) {
            connection.setReadOnly(true);
            statement.setQueryTimeout(Math.max(1, (int) properties.ownerRules().connectTimeout().toSeconds()));
            statement.executeQuery();
            return new ConnectionTestResponse(true, "POSTGRESQL", "Доступен");
        } catch (Exception exception) {
            throw new ConnectionTestException("Не удалось подключиться к PostgreSQL. Проверьте адрес и учётные данные.");
        }
    }

    public ConnectionTestResponse testRedis(ConnectionSettingsUpdateRequest.RedisSettings request, String profileId) {
        validateRedis(request);
        String password = passwordOrCurrent(request.password(), profilePassword(profileId, false));
        Duration timeout = Duration.ofMillis(request.timeoutMs());
        RedisURI.Builder builder = RedisURI.builder().withHost(request.host().trim()).withPort(request.port())
                .withDatabase(request.database()).withTimeout(timeout);
        if (request.username() != null && !request.username().isBlank()) {
            builder.withAuthentication(request.username().trim(), password.toCharArray());
        } else if (!password.isBlank()) {
            builder.withPassword(password.toCharArray());
        }
        RedisClient client = RedisClient.create(builder.build());
        client.setDefaultTimeout(timeout);
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            if (!"PONG".equalsIgnoreCase(connection.sync().ping())) throw new IllegalStateException("Unexpected Redis PING response");
            return new ConnectionTestResponse(true, "REDIS", "Доступен");
        } catch (Exception exception) {
            throw new ConnectionTestException("Не удалось подключиться к Redis. Проверьте адрес и учётные данные.");
        } finally {
            client.shutdown(Duration.ZERO, Duration.ofMillis(200));
        }
    }

    Path settingsPath() {
        String configured = properties.settings() == null ? null : properties.settings().path();
        return configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".jena-ripper", "jena-ripper-settings.json")
                : Path.of(configured).toAbsolutePath().normalize();
    }

    private StoredConnectionProfiles readProfiles(boolean migrateLegacy) {
        Path path = settingsPath();
        if (!Files.isRegularFile(path)) return defaultStore();
        try {
            JsonNode root = objectMapper.readTree(path.toFile());
            if (root.has("profiles")) {
                StoredConnectionProfiles store = objectMapper.treeToValue(root, StoredConnectionProfiles.class);
                if (store.profiles() == null || store.profiles().isEmpty()) return defaultStore();
                return store;
            }
            StoredConnectionSettings legacy = objectMapper.treeToValue(root, StoredConnectionSettings.class);
            StoredConnectionProfiles migrated = new StoredConnectionProfiles(DEFAULT_PROFILE_ID,
                    List.of(new StoredConnectionProfiles.Profile(DEFAULT_PROFILE_ID, DEFAULT_PROFILE_NAME,
                            legacy.jena(), legacy.postgres(), legacy.redis())));
            if (migrateLegacy) write(migrated);
            return migrated;
        } catch (Exception ignored) {
            return defaultStore();
        }
    }

    private StoredConnectionProfiles defaultStore() {
        PostgresParts postgres = postgresParts(properties.ownerRules().jdbcUrl());
        JenaRipperProperties.RedisRules redis = properties.redisRules();
        StoredConnectionSettings.Jena jenaSettings;
        if (sourceProperties.remote() && sourceProperties.cimApi() != null) {
            RdfSourceProperties.CimApi cim = sourceProperties.cimApi();
            jenaSettings = new StoredConnectionSettings.Jena(RdfSourceProperties.CIM_API, "tdb2", "",
                    new StoredConnectionSettings.CimApi(cim.baseUrl(), cim.authBaseUrl(), cim.username(), cim.password(), cim.modelId(), cim.modelName(),
                            timeout(cim.connectTimeout()).toMillis(), timeout(cim.readTimeout()).toMillis(), cim.trustUntrustedCertificates()));
        } else {
            jenaSettings = new StoredConnectionSettings.Jena(properties.dataset().type().toLowerCase(), properties.dataset().path());
        }
        StoredConnectionProfiles.Profile profile = new StoredConnectionProfiles.Profile(DEFAULT_PROFILE_ID, DEFAULT_PROFILE_NAME,
                jenaSettings,
                new StoredConnectionSettings.Postgres(postgres.host(), postgres.port(), postgres.database(),
                        properties.ownerRules().schema(), properties.ownerRules().username(), properties.ownerRules().password()),
                new StoredConnectionSettings.Redis(redis.host(), redis.port(), redis.database(), blankToEmpty(redis.username()),
                        blankToEmpty(redis.password()), timeout(redis.timeout()).toMillis()));
        return new StoredConnectionProfiles(DEFAULT_PROFILE_ID, List.of(profile));
    }

    private void write(StoredConnectionProfiles stored) {
        Path target = settingsPath();
        try {
            Files.createDirectories(target.getParent());
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), stored);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Не удалось сохранить локальные настройки подключений.");
        }
    }

    private StoredConnectionProfiles.Profile storedProfile(String id, String name, ConnectionSettingsUpdateRequest request,
                                                             StoredConnectionProfiles.Profile existing) {
        String sourceType = sourceType(request.jena());
        StoredConnectionSettings.CimApi cim = null;
        if (RdfSourceProperties.CIM_API.equals(sourceType)) {
            ConnectionSettingsUpdateRequest.CimApiSettings value = request.jena().cimApi();
            StoredConnectionSettings.CimApi old = existing.jena().cimApi();
            cim = new StoredConnectionSettings.CimApi(value.baseUrl().trim(), normalizedAuthUrl(value), value.username().trim(),
                    passwordOrCurrent(value.password(), old == null ? "" : old.password()), value.modelId(),
                    blankToEmpty(value.modelName()).trim(), value.connectTimeoutMs(), value.readTimeoutMs(),
                    trustUntrustedCertificates(value.trustUntrustedCertificates()));
        }
        return new StoredConnectionProfiles.Profile(id, name,
                new StoredConnectionSettings.Jena(sourceType, blankToEmpty(request.jena().type()).trim().toLowerCase(),
                        blankToEmpty(request.jena().path()).trim(), cim),
                RdfSourceProperties.CIM_API.equals(sourceType) || request.postgres() == null ? existing.postgres() :
                new StoredConnectionSettings.Postgres(request.postgres().host().trim(), request.postgres().port(),
                        request.postgres().database().trim(), request.postgres().schema().trim(),
                        request.postgres().username().trim(), passwordOrCurrent(request.postgres().password(), existing.postgres().password())),
                request.redis() == null ? existing.redis() :
                new StoredConnectionSettings.Redis(request.redis().host().trim(), request.redis().port(), request.redis().database(),
                        blankToEmpty(request.redis().username()).trim(), passwordOrCurrent(request.redis().password(), existing.redis().password()),
                        request.redis().timeoutMs()));
    }

    private String profilePassword(String profileId, boolean postgres) {
        if (profileId != null && !profileId.isBlank()) {
            try {
                StoredConnectionProfiles.Profile profile = findProfile(readProfiles(false), profileId);
                return postgres ? profile.postgres().password() : profile.redis().password();
            } catch (IllegalArgumentException ignored) { }
        }
        return postgres ? properties.ownerRules().password() : properties.redisRules().password();
    }

    private ConnectionProfilesDto safe(StoredConnectionProfiles store) {
        return new ConnectionProfilesDto(store.activeProfileId(), store.profiles().stream().map(profile ->
                new ConnectionProfilesDto.Profile(profile.id(), profile.name(), profile.id().equals(store.activeProfileId()),
                        safe(profile).jena(), safe(profile).postgres(), safe(profile).redis())).toList());
    }

    private ConnectionSettingsDto safe(StoredConnectionProfiles.Profile profile) {
        StoredConnectionSettings.Jena storedJena = profile.jena();
        StoredConnectionSettings.CimApi storedCim = storedJena.cimApi();
        ConnectionSettingsDto.CimApiSettings cim = storedCim == null ? null : new ConnectionSettingsDto.CimApiSettings(
                storedCim.baseUrl(), storedCim.authBaseUrl(), storedCim.username(), storedCim.modelId(), storedCim.modelName(),
                storedCim.connectTimeoutMs(), storedCim.readTimeoutMs(), configured(storedCim.password()),
                storedCim.trustUntrustedCertificates());
        return new ConnectionSettingsDto(
                new ConnectionSettingsDto.JenaSettings(
                        storedJena.sourceType() == null ? RdfSourceProperties.LOCAL_TDB2 : storedJena.sourceType().toUpperCase(),
                        blankToEmpty(storedJena.type()).toUpperCase(), blankToEmpty(storedJena.path()), cim),
                new ConnectionSettingsDto.PostgresSettings(profile.postgres().host(), profile.postgres().port(),
                        profile.postgres().database(), profile.postgres().schema(), profile.postgres().username(), configured(profile.postgres().password())),
                new ConnectionSettingsDto.RedisSettings(profile.redis().host(), profile.redis().port(), profile.redis().database(),
                        blankToEmpty(profile.redis().username()), profile.redis().timeoutMs(), configured(profile.redis().password())));
    }

    private static StoredConnectionProfiles.Profile findProfile(StoredConnectionProfiles store, String id) {
        return store.profiles().stream().filter(profile -> profile.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Профиль подключения не найден."));
    }

    private static String validatedName(String value) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("Введите название профиля.");
        if (value.trim().length() > 80) throw new IllegalArgumentException("Название профиля не должно превышать 80 символов.");
        return value.trim();
    }

    private static void ensureUniqueName(StoredConnectionProfiles store, String name, String exceptId) {
        boolean duplicate = store.profiles().stream().anyMatch(profile -> !profile.id().equals(exceptId)
                && profile.name().equalsIgnoreCase(name));
        if (duplicate) throw new IllegalArgumentException("Профиль с таким названием уже существует.");
    }

    private static void validateRequest(ConnectionSettingsUpdateRequest request) {
        if (request == null || request.jena() == null) {
            throw new IllegalArgumentException("Переданы неполные настройки подключений.");
        }
        validateJena(request.jena());
        if (!remote(request.jena())) validatePostgres(request.postgres());
        validateRedis(request.redis());
    }

    private static void validateJena(ConnectionSettingsUpdateRequest.JenaSettings value) {
        if (value == null) throw new IllegalArgumentException("Укажите источник RDF.");
        if (remote(value)) validateCim(value.cimApi(), true);
        else if (blank(value.type()) || blank(value.path())) throw new IllegalArgumentException("Для Jena Dataset укажите тип и путь.");
    }

    private static void validateCim(ConnectionSettingsUpdateRequest.CimApiSettings value, boolean requireModel) {
        if (value == null || blank(value.baseUrl()) || blank(value.username())
                || value.connectTimeoutMs() == null || value.connectTimeoutMs() < 100
                || value.readTimeoutMs() == null || value.readTimeoutMs() < 100
                || (requireModel && value.modelId() == null)) {
            throw new IllegalArgumentException("Для CIM App API укажите URL, пользователя, таймауты и информационную модель.");
        }
        URI uri;
        try { uri = URI.create(value.baseUrl().trim()); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("Некорректный URL CIM App API."); }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())))
            throw new IllegalArgumentException("URL CIM App API должен использовать HTTP или HTTPS.");
        String authUrl = normalizedAuthUrl(value);
        try { uri = URI.create(authUrl); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("Некорректный Auth URL CIM App."); }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())))
            throw new IllegalArgumentException("Auth URL CIM App должен использовать HTTP или HTTPS.");
    }

    private StoredConnectionSettings.CimApi cimSettings(ConnectionSettingsUpdateRequest.JenaSettings request, String profileId) {
        ConnectionSettingsUpdateRequest.CimApiSettings value = request.cimApi();
        String current = "";
        if (profileId != null && !profileId.isBlank()) {
            try {
                StoredConnectionSettings.CimApi old = findProfile(readProfiles(false), profileId).jena().cimApi();
                if (old != null) current = old.password();
            } catch (RuntimeException ignored) { }
        }
        return new StoredConnectionSettings.CimApi(value.baseUrl().trim(), normalizedAuthUrl(value), value.username().trim(),
                passwordOrCurrent(value.password(), current), value.modelId(), value.modelName(),
                value.connectTimeoutMs(), value.readTimeoutMs(), trustUntrustedCertificates(value.trustUntrustedCertificates()));
    }

    private static boolean remote(ConnectionSettingsUpdateRequest.JenaSettings value) {
        return value != null && RdfSourceProperties.CIM_API.equalsIgnoreCase(value.sourceType());
    }

    private static boolean trustUntrustedCertificates(Boolean value) {
        return value == null || value;
    }

    private static String sourceType(ConnectionSettingsUpdateRequest.JenaSettings value) {
        return remote(value) ? RdfSourceProperties.CIM_API : RdfSourceProperties.LOCAL_TDB2;
    }

    private static String normalizedAuthUrl(ConnectionSettingsUpdateRequest.CimApiSettings value) {
        return blank(value.authBaseUrl()) ? value.baseUrl().trim() : value.authBaseUrl().trim();
    }

    private static void validatePostgres(ConnectionSettingsUpdateRequest.PostgresSettings value) {
        if (value == null || blank(value.host()) || invalidPort(value.port()) || blank(value.database())
                || blank(value.schema()) || blank(value.username())) throw new IllegalArgumentException("Проверьте обязательные поля PostgreSQL.");
        if (!value.schema().matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Некорректное имя PostgreSQL schema.");
    }

    private static void validateRedis(ConnectionSettingsUpdateRequest.RedisSettings value) {
        if (value == null || blank(value.host()) || invalidPort(value.port()) || value.database() == null
                || value.database() < 0 || value.timeoutMs() == null || value.timeoutMs() < 100) {
            throw new IllegalArgumentException("Проверьте host, port, database и timeout Redis.");
        }
    }

    private static boolean invalidPort(Integer port) { return port == null || port < 1 || port > 65535; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static boolean configured(String value) { return value != null && !value.isBlank(); }
    private static String blankToEmpty(String value) { return value == null ? "" : value; }
    private static String passwordOrCurrent(String submitted, String current) { return submitted == null || submitted.isBlank() ? blankToEmpty(current) : submitted; }
    private static Duration timeout(Duration value) { return value == null ? Duration.ofSeconds(5) : value; }
    private static String postgresUrl(String host, int port, String database) { return "jdbc:postgresql://" + host.trim() + ":" + port + "/" + database.trim(); }

    private static PostgresParts postgresParts(String jdbcUrl) {
        try {
            URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
            return new PostgresParts(uri.getHost(), uri.getPort() < 0 ? 5432 : uri.getPort(), uri.getPath().replaceFirst("^/", ""));
        } catch (RuntimeException exception) {
            return new PostgresParts("localhost", 5432, "");
        }
    }

    private record PostgresParts(String host, int port, String database) {}
    public static class ConnectionTestException extends RuntimeException { public ConnectionTestException(String message) { super(message); } }
}
