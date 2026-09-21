package org.jenaripper.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.jenaripper.config.RdfSourceProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

public class ConnectionSettingsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty("jena-ripper.settings.overrides-enabled", Boolean.class, true)) return;
        String configured = environment.getProperty("jena-ripper.settings.path");
        Path path = ConnectionProfilePaths.resolve(configured);
        migrateLegacySettings(path);
        if (!Files.isRegularFile(path)) return;
        try {
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            JsonNode root = mapper.readTree(path.toFile());
            StoredConnectionSettings stored;
            if (root.has("profiles")) {
                StoredConnectionProfiles profiles = mapper.treeToValue(root, StoredConnectionProfiles.class);
                StoredConnectionProfiles.Profile active = profiles.profiles() == null ? null : profiles.profiles().stream()
                        .filter(profile -> profile.id().equals(profiles.activeProfileId()))
                        .findFirst().orElse(null);
                if (active == null) return;
                environment.getPropertySources().addFirst(new MapPropertySource(
                        "jenaRipperActiveConnectionProfile",
                        Map.of("jena-ripper.rdf-source.profile-id", active.id())));
                stored = new StoredConnectionSettings(active.jena(), active.postgres(), active.redis());
            } else {
                stored = mapper.treeToValue(root, StoredConnectionSettings.class);
            }
            Map<String, Object> values = new LinkedHashMap<>();
            if (stored.jena() != null) {
                String sourceType = stored.jena().sourceType() == null ? RdfSourceProperties.LOCAL_TDB2 : stored.jena().sourceType();
                values.put("jena-ripper.rdf-source.type", sourceType);
                if (RdfSourceProperties.CIM_API.equalsIgnoreCase(sourceType) && stored.jena().cimApi() != null) {
                    StoredConnectionSettings.CimApi cim = stored.jena().cimApi();
                    values.put("jena-ripper.dataset.type", "memory");
                    values.put("jena-ripper.dataset.path", "remote-cim-api");
                    values.put("jena-ripper.rdf-source.cim-api.base-url", cim.baseUrl());
                    values.put("jena-ripper.rdf-source.cim-api.auth-base-url",
                            cim.authBaseUrl() == null || cim.authBaseUrl().isBlank() ? cim.baseUrl() : cim.authBaseUrl());
                    values.put("jena-ripper.rdf-source.cim-api.username", cim.username());
                    values.put("jena-ripper.rdf-source.cim-api.password", cim.password());
                    if (cim.modelId() != null) values.put("jena-ripper.rdf-source.cim-api.model-id", cim.modelId());
                    values.put("jena-ripper.rdf-source.cim-api.model-name", cim.modelName());
                    values.put("jena-ripper.rdf-source.cim-api.connect-timeout", cim.connectTimeoutMs() + "ms");
                    values.put("jena-ripper.rdf-source.cim-api.read-timeout", cim.readTimeoutMs() + "ms");
                    values.put("jena-ripper.rdf-source.cim-api.trust-untrusted-certificates", cim.trustUntrustedCertificates());
                } else if (RdfSourceProperties.FILE.equalsIgnoreCase(sourceType)
                        && stored.jena().uploadedModelId() != null && !stored.jena().uploadedModelId().isBlank()) {
                    Path modelDataset = path.toAbsolutePath().normalize().getParent()
                            .resolve(ConnectionProfilePaths.UPLOADED_MODELS_DIRECTORY)
                            .resolve(stored.jena().uploadedModelId())
                            .resolve(ConnectionProfilePaths.MODEL_DATASET_DIRECTORY);
                    values.put("jena-ripper.dataset.type", "tdb2");
                    values.put("jena-ripper.dataset.path", modelDataset.toString());
                } else {
                    values.put("jena-ripper.dataset.type", stored.jena().type());
                    values.put("jena-ripper.dataset.path", stored.jena().path());
                }
            }
            boolean local = stored.jena() == null || stored.jena().sourceType() == null
                    || !RdfSourceProperties.CIM_API.equalsIgnoreCase(stored.jena().sourceType());
            if (local && stored.postgres() != null) {
                StoredConnectionSettings.Postgres postgres = stored.postgres();
                values.put("jena-ripper.owner-rules.jdbc-url", "jdbc:postgresql://" + postgres.host() + ":" + postgres.port() + "/" + postgres.database());
                values.put("jena-ripper.owner-rules.schema", postgres.schema());
                values.put("jena-ripper.owner-rules.username", postgres.username());
                values.put("jena-ripper.owner-rules.password", postgres.password());
            }
            if (stored.redis() != null) {
                StoredConnectionSettings.Redis redis = stored.redis();
                values.put("jena-ripper.redis-rules.host", redis.host());
                values.put("jena-ripper.redis-rules.port", redis.port());
                values.put("jena-ripper.redis-rules.database", redis.database());
                values.put("jena-ripper.redis-rules.username", redis.username());
                values.put("jena-ripper.redis-rules.password", redis.password());
                values.put("jena-ripper.redis-rules.timeout", redis.timeoutMs() + "ms");
            }
            environment.getPropertySources().addFirst(new MapPropertySource("jenaRipperUserConnectionSettings", values));
        } catch (Exception ignored) {
            // Invalid local overrides never prevent startup; application.yml remains the safe fallback.
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private static void migrateLegacySettings(Path target) {
        if (Files.exists(target)) return;
        java.util.List<Path> candidates = new java.util.ArrayList<>();
        candidates.add(Path.of(System.getProperty("user.home"), ".jena-ripper", "jena-ripper-settings.json"));
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            candidates.add(Path.of(localAppData, "JenaRipper", "jena-ripper-settings.json"));
        }
        for (Path legacy : candidates) {
            if (!Files.isRegularFile(legacy) || legacy.equals(target)) continue;
            try {
                Files.createDirectories(target.getParent());
                Files.copy(legacy, target, StandardCopyOption.COPY_ATTRIBUTES);
                return;
            } catch (Exception ignored) {
                // A failed migration must not prevent startup; defaults remain available.
            }
        }
    }
}
