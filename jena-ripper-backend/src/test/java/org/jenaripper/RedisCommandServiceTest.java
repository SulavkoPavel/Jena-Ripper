package org.jenaripper;

import org.jenaripper.config.JenaRipperProperties;
import org.jenaripper.config.RdfSourceProperties;
import org.jenaripper.dto.RedisCommandRequest;
import org.jenaripper.dto.RedisKeyRequest;
import org.jenaripper.dto.RedisCommandResponse;
import org.jenaripper.dto.RedisKeyResponse;
import org.jenaripper.exception.RedisCommandException;
import org.jenaripper.redis.RedisCommandExecutor;
import org.jenaripper.redis.RedisCommandRegistry;
import org.jenaripper.service.RedisCommandService;
import org.jenaripper.remote.CimApiClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RedisCommandServiceTest {
    private final RedisCommandExecutor executor = mock(RedisCommandExecutor.class);
    private final RedisCommandService service = service(false);

    @Test void executesReadOnlySetCommand() {
        when(executor.smembers("2/rb:ups:_1")).thenReturn(new LinkedHashSet<>(List.of("owner-a", "owner-b")));
        RedisCommandResponse response = service.execute(new RedisCommandRequest("smembers", List.of("2/rb:ups:_1")));
        assertThat(response.command()).isEqualTo("SMEMBERS");
        assertThat(response.resultType()).isEqualTo("COLLECTION");
        assertThat(response.count()).isEqualTo(2);
    }

    @Test void rejectsKeysAndWriteCommands() {
        assertThatThrownBy(() -> service.execute(new RedisCommandRequest("KEYS", List.of("*"))))
                .isInstanceOfSatisfying(RedisCommandException.class, error -> {
                    assertThat(error.type()).isEqualTo("REDIS_COMMAND_FORBIDDEN");
                    assertThat(error.getMessage()).contains("SCAN");
                });
        assertThatThrownBy(() -> service.execute(new RedisCommandRequest("SREM", List.of("key", "value"))))
                .isInstanceOfSatisfying(RedisCommandException.class,
                        error -> assertThat(error.type()).isEqualTo("REDIS_COMMAND_FORBIDDEN"));
    }

    @Test void parsesScanWithoutUsingKeys() {
        when(executor.scan("0", "2/*", 100)).thenReturn(new RedisCommandExecutor.ScanResult("17", List.of("2/rb:ups:_1")));
        RedisCommandResponse response = service.execute(new RedisCommandRequest("SCAN", List.of("0", "MATCH", "2/*", "COUNT", "100")));
        assertThat(response.cursor()).isEqualTo("17");
        assertThat(response.count()).isEqualTo(1);
        verify(executor).scan("0", "2/*", 100);
    }

    @Test void buildsCimPermissionKeysThroughExistingFactorySemantics() {
        RedisKeyResponse response = service.key(new RedisKeyRequest(2L, "ups:_387", "READ_TOP"));
        assertThat(response.key()).isEqualTo("2/rt:ups:_387");
        assertThat(response.command()).isEqualTo("SMEMBERS 2/rt:ups:_387");
    }

    @Test void enablesDirectRedisForCimApiProfile() {
        RedisCommandService remote = service(true);
        assertThat(remote.metadata().available()).isTrue();
        remote.execute(new RedisCommandRequest("TYPE", List.of("key")));
        verify(executor).type("key");
    }

    @Test void usesConfiguredRedisDatasetWithoutCallingCimApiCapability() {
        CimApiClient client = mock(CimApiClient.class);
        JenaRipperProperties.RedisRules redis = new JenaRipperProperties.RedisRules(true, "localhost", 6379, "", "", 0,
                Duration.ofSeconds(5), 2, "PIM", null, List.of());
        JenaRipperProperties properties = new JenaRipperProperties(null, null, null, null, null, null, redis, null);
        RedisCommandService remote = new RedisCommandService(new RedisCommandRegistry(), executor, properties,
                new RdfSourceProperties("CIM_API", null), client);
        assertThat(remote.metadata().available()).isTrue();
        assertThat(remote.metadata().datasetId()).isEqualTo(2L);
        verifyNoInteractions(client);
    }

    private RedisCommandService service(boolean remote) {
        JenaRipperProperties.RedisRules redis = new JenaRipperProperties.RedisRules(true, "localhost", 6379, "", "", 0,
                Duration.ofSeconds(5), 2, "PIM", null, List.of());
        JenaRipperProperties properties = new JenaRipperProperties(null, null, null, null, null, null, redis, null);
        return new RedisCommandService(new RedisCommandRegistry(), executor, properties,
                new RdfSourceProperties(remote ? "CIM_API" : "LOCAL_TDB2", null));
    }
}
