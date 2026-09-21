package org.jenaripper;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jenaripper.settings.ConnectionSettingsEnvironmentPostProcessor;
import org.jenaripper.settings.StoredConnectionSettings;
import org.jenaripper.settings.StoredConnectionProfiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionSettingsEnvironmentPostProcessorTest {
    @TempDir Path directory;

    @Test
    void loadsPersistedOverridesBeforeConfigurationBinding() throws Exception {
        Path file = directory.resolve("settings.json");
        new ObjectMapper().writeValue(file.toFile(), new StoredConnectionSettings(
                new StoredConnectionSettings.Jena("tdb2", "D:/datasets/9"),
                new StoredConnectionSettings.Postgres("pg-host", 5544, "cim", "rules", "reader", "secret"),
                new StoredConnectionSettings.Redis("redis-host", 6388, 3, "redis-user", "redis-secret", 2500)));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("jena-ripper.settings.path", file.toString())
                .withProperty("jena-ripper.settings.overrides-enabled", "true");

        new ConnectionSettingsEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("jena-ripper.dataset.path")).isEqualTo("D:/datasets/9");
        assertThat(environment.getProperty("jena-ripper.owner-rules.jdbc-url")).isEqualTo("jdbc:postgresql://pg-host:5544/cim");
        assertThat(environment.getProperty("jena-ripper.owner-rules.password")).isEqualTo("secret");
        assertThat(environment.getProperty("jena-ripper.redis-rules.database")).isEqualTo("3");
        assertThat(environment.getProperty("jena-ripper.redis-rules.timeout")).isEqualTo("2500ms");
    }

    @Test
    void loadsOnlyActiveProfileOverrides() throws Exception {
        Path file = directory.resolve("profiles.json");
        StoredConnectionProfiles.Profile first = new StoredConnectionProfiles.Profile("first", "First",
                new StoredConnectionSettings.Jena("tdb2", "D:/datasets/first"),
                new StoredConnectionSettings.Postgres("first-pg", 5432, "cim", "public", "user", "one"),
                new StoredConnectionSettings.Redis("first-redis", 6379, 0, "", "one", 5000));
        StoredConnectionProfiles.Profile second = new StoredConnectionProfiles.Profile("second", "Second", 7L,
                Instant.parse("2026-09-10T12:00:00Z"),
                new StoredConnectionSettings.Jena("tdb2", "D:/datasets/second"),
                new StoredConnectionSettings.Postgres("second-pg", 5544, "cim2", "rules", "reader", "two"),
                new StoredConnectionSettings.Redis("second-redis", 6380, 3, "redis", "two", 2500));
        new ObjectMapper().findAndRegisterModules()
                .writeValue(file.toFile(), new StoredConnectionProfiles("second", List.of(first, second)));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("jena-ripper.settings.path", file.toString())
                .withProperty("jena-ripper.settings.overrides-enabled", "true");

        new ConnectionSettingsEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("jena-ripper.dataset.path")).isEqualTo("D:/datasets/second");
        assertThat(environment.getProperty("jena-ripper.rdf-source.profile-id")).isEqualTo("second");
        assertThat(environment.getProperty("jena-ripper.owner-rules.jdbc-url")).isEqualTo("jdbc:postgresql://second-pg:5544/cim2");
        assertThat(environment.getProperty("jena-ripper.redis-rules.host")).isEqualTo("second-redis");
    }

    @Test
    void remoteProfileUsesCimBusinessApiAndSeparateRedisSettings() throws Exception {
        Path file = directory.resolve("remote.json");
        StoredConnectionProfiles.Profile remote = new StoredConnectionProfiles.Profile("remote", "SIM2",
                new StoredConnectionSettings.Jena("CIM_API", "tdb2", "",
                        new StoredConnectionSettings.CimApi("https://cim.example", "client", "secret", 42L,
                                "SIM2", 3000, 20000)),
                new StoredConnectionSettings.Postgres("must-not-be-used", 5432, "cim", "public", "user", "secret"),
                new StoredConnectionSettings.Redis("remote-redis", 6381, 4, "redis-user", "secret", 5000));
        new ObjectMapper().writeValue(file.toFile(), new StoredConnectionProfiles("remote", List.of(remote)));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("jena-ripper.settings.path", file.toString())
                .withProperty("jena-ripper.settings.overrides-enabled", "true")
                .withProperty("jena-ripper.owner-rules.jdbc-url", "jdbc:postgresql://local/default")
                .withProperty("jena-ripper.redis-rules.host", "local-redis");

        new ConnectionSettingsEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("jena-ripper.rdf-source.type")).isEqualTo("CIM_API");
        assertThat(environment.getProperty("jena-ripper.rdf-source.cim-api.model-id")).isEqualTo("42");
        assertThat(environment.getProperty("jena-ripper.dataset.type")).isEqualTo("memory");
        assertThat(environment.getProperty("jena-ripper.owner-rules.jdbc-url")).isEqualTo("jdbc:postgresql://local/default");
        assertThat(environment.getProperty("jena-ripper.redis-rules.host")).isEqualTo("remote-redis");
        assertThat(environment.getProperty("jena-ripper.redis-rules.port")).isEqualTo("6381");
        assertThat(environment.getProperty("jena-ripper.redis-rules.database")).isEqualTo("4");
    }

    @Test
    void fileProfileRoutesExistingServicesToItsOwnDataset() throws Exception {
        Path file = directory.resolve("file-profile.json");
        String modelId = "8b124153-e126-47c4-821c-62f35ab5cfb8";
        StoredConnectionProfiles.Profile profile = new StoredConnectionProfiles.Profile("file", "Uploaded",
                new StoredConnectionSettings.Jena("FILE", "tdb2", "", null, modelId),
                new StoredConnectionSettings.Postgres("pg", 5432, "cim", "public", "user", "secret"),
                new StoredConnectionSettings.Redis("redis", 6379, 0, "", "", 5000));
        new ObjectMapper().writeValue(file.toFile(), new StoredConnectionProfiles("file", List.of(profile)));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("jena-ripper.settings.path", file.toString())
                .withProperty("jena-ripper.settings.overrides-enabled", "true");

        new ConnectionSettingsEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("jena-ripper.rdf-source.type")).isEqualTo("FILE");
        assertThat(environment.getProperty("jena-ripper.dataset.type")).isEqualTo("tdb2");
        assertThat(Path.of(environment.getProperty("jena-ripper.dataset.path")))
                .isEqualTo(directory.resolve("uploaded-models").resolve(modelId).resolve("dataset"));
        assertThat(environment.getProperty("jena-ripper.owner-rules.jdbc-url"))
                .isEqualTo("jdbc:postgresql://pg:5432/cim");
    }

    @Test
    void desktopProfileMigratesLegacySettingsWithoutOverwritingTarget() throws Exception {
        String originalHome = System.getProperty("user.home");
        Path legacy = directory.resolve(".jena-ripper/jena-ripper-settings.json");
        Path target = directory.resolve("appdata/JenaRipper/jena-ripper-settings.json");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "{\"jena\":{\"type\":\"tdb2\",\"path\":\"D:/legacy\"}}");
        try {
            System.setProperty("user.home", directory.toString());
            MockEnvironment environment = new MockEnvironment()
                    .withProperty("jena-ripper.settings.path", target.toString())
                    .withProperty("jena-ripper.settings.overrides-enabled", "true");
            environment.setActiveProfiles("desktop");

            new ConnectionSettingsEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());

            assertThat(target).hasSameTextualContentAs(legacy);
            assertThat(environment.getProperty("jena-ripper.dataset.path")).isEqualTo("D:/legacy");
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }
}
