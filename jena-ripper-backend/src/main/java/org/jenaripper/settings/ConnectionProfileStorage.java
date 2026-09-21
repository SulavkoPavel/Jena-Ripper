package org.jenaripper.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.jenaripper.dto.ConnectionProfileFormat;
import org.jenaripper.config.JenaRipperProperties;
import org.jenaripper.config.RdfSourceProperties;
import org.springframework.stereotype.Component;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ConnectionProfileStorage {
    private static final String DEFAULT_PROFILE_NAME = "Текущее подключение";
    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_POSTGRES_PORT = 5_432;
    private static final int DEFAULT_REDIS_PORT = 6_379;
    private static final long DEFAULT_REDIS_TIMEOUT_MS = 5_000;

    private final JenaRipperProperties properties;
    private final ObjectMapper objectMapper;

    public StoredConnectionProfiles read() {
        return read(true);
    }

    public StoredConnectionProfiles readWithoutMigration() {
        return read(false);
    }

    public void write(StoredConnectionProfiles profiles) {
        Path target = path();
        try {
            Files.createDirectories(target.getParent());
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), profiles);
            moveIntoPlace(temporary, target);
        } catch (Exception exception) {
            throw new IllegalStateException("Не удалось сохранить локальные настройки подключений.", exception);
        }
    }

    public StoredConnectionProfiles.Profile find(StoredConnectionProfiles profiles, String profileId) {
        return profiles.profiles().stream()
                .filter(profile -> profile.id().equals(profileId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Профиль подключения не найден."));
    }

    Path path() {
        String configuredPath = properties.settings() == null ? null : properties.settings().path();
        return ConnectionProfilePaths.resolve(configuredPath);
    }

    private StoredConnectionProfiles read(boolean migrateLegacy) {
        Path settingsPath = path();
        if (!Files.isRegularFile(settingsPath)) {
            StoredConnectionProfiles defaults = defaultProfiles();
            if (migrateLegacy) {
                write(defaults);
            }
            return defaults;
        }
        try {
            JsonNode root = objectMapper.readTree(settingsPath.toFile());
            if (root.has("profiles")) {
                return readCurrentFormat(root, migrateLegacy);
            }
            StoredConnectionProfiles migrated = migrateLegacySettings(root);
            if (migrateLegacy) {
                write(migrated);
            }
            return migrated;
        } catch (Exception ignored) {
            return defaultProfiles();
        }
    }

    private StoredConnectionProfiles readCurrentFormat(JsonNode root, boolean persistNormalization) throws Exception {
        StoredConnectionProfiles stored = objectMapper.treeToValue(root, StoredConnectionProfiles.class);
        if (stored.profiles() == null || stored.profiles().isEmpty()) {
            return defaultProfiles();
        }
        StoredConnectionProfiles normalized = normalize(stored);
        if (persistNormalization && !normalized.equals(stored)) {
            write(normalized);
        }
        return normalized;
    }

    private StoredConnectionProfiles migrateLegacySettings(JsonNode root) throws Exception {
        StoredConnectionSettings legacy = objectMapper.treeToValue(root, StoredConnectionSettings.class);
        String profileId = UUID.randomUUID().toString();
        StoredConnectionProfiles.Profile profile = new StoredConnectionProfiles.Profile(
                profileId, DEFAULT_PROFILE_NAME, legacy.jena(), legacy.postgres(), legacy.redis());
        return new StoredConnectionProfiles(profileId, List.of(profile));
    }

    private StoredConnectionProfiles defaultProfiles() {
        String profileId = UUID.randomUUID().toString();
        StoredConnectionSettings.Jena jena = new StoredConnectionSettings.Jena(
                RdfSourceProperties.LOCAL_TDB2, properties.dataset().type().toLowerCase(),
                properties.dataset().path(), null, null);
        StoredConnectionProfiles.Profile profile = new StoredConnectionProfiles.Profile(
                profileId, DEFAULT_PROFILE_NAME, ConnectionProfileFormat.INITIAL_PROFILE_VERSION,
                Instant.now(), jena,
                new StoredConnectionSettings.Postgres(
                        DEFAULT_HOST, DEFAULT_POSTGRES_PORT, "jena_ripper", "public", "", ""),
                new StoredConnectionSettings.Redis(
                        DEFAULT_HOST, DEFAULT_REDIS_PORT, 0, "", "", DEFAULT_REDIS_TIMEOUT_MS));
        return new StoredConnectionProfiles(profileId, List.of(profile));
    }

    private StoredConnectionProfiles normalize(StoredConnectionProfiles stored) {
        List<StoredConnectionProfiles.Profile> normalizedProfiles = new ArrayList<>();
        String activeProfileId = stored.activeProfileId();
        for (StoredConnectionProfiles.Profile profile : stored.profiles()) {
            String normalizedId = validUuid(profile.id()) ? profile.id() : UUID.randomUUID().toString();
            if (Objects.equals(activeProfileId, profile.id())) {
                activeProfileId = normalizedId;
            }
            normalizedProfiles.add(new StoredConnectionProfiles.Profile(
                    normalizedId, profile.name(), version(profile),
                    profile.updatedAt() == null ? Instant.now() : profile.updatedAt(),
                    profile.jena(), profile.postgres(), profile.redis()));
        }
        if (!containsId(normalizedProfiles, activeProfileId)) {
            activeProfileId = normalizedProfiles.get(0).id();
        }
        return new StoredConnectionProfiles(activeProfileId, normalizedProfiles);
    }

    private static void moveIntoPlace(Path temporary, Path target) throws Exception {
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean containsId(List<StoredConnectionProfiles.Profile> profiles, String profileId) {
        return profiles.stream().anyMatch(profile -> profile.id().equals(profileId));
    }

    private static boolean validUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static long version(StoredConnectionProfiles.Profile profile) {
        return profile.version() == null || profile.version() < 1 ? 1 : profile.version();
    }
}
