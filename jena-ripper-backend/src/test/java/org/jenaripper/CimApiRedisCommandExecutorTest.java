package org.jenaripper;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jenaripper.redis.CimApiRedisCommandExecutor;
import org.jenaripper.redis.RedisCommandException;
import org.jenaripper.remote.CimApiClient;
import org.jenaripper.remote.CimApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CimApiRedisCommandExecutorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final CimApiClient client = mock(CimApiClient.class);
    private final CimApiRedisCommandExecutor executor = new CimApiRedisCommandExecutor(client, mapper);

    @Test void normalizesSetAndScanResults() {
        when(client.redis("SMEMBERS", List.of("2/rb:key"))).thenReturn(result("SMEMBERS", "COLLECTION",
                mapper.valueToTree(List.of("owner-a", "owner-b")), null));
        when(client.redis("SCAN", List.of("0", "MATCH", "2/*", "COUNT", "100"))).thenReturn(result("SCAN", "SCAN",
                mapper.valueToTree(List.of("2/rb:key")), "19"));

        assertThat(executor.smembers("2/rb:key")).containsExactly("owner-a", "owner-b");
        assertThat(executor.scan("0", "2/*", 100)).satisfies(scan -> {
            assertThat(scan.cursor()).isEqualTo("19");
            assertThat(scan.keys()).containsExactly("2/rb:key");
        });
    }

    @Test void supportsRequiredScalarReadCommands() {
        when(client.redis("TYPE", List.of("key"))).thenReturn(result("TYPE", "STRING", mapper.valueToTree("set"), null));
        when(client.redis("EXISTS", List.of("key"))).thenReturn(result("EXISTS", "BOOLEAN", mapper.valueToTree(true), null));
        when(client.redis("SCARD", List.of("key"))).thenReturn(result("SCARD", "INTEGER", mapper.valueToTree(3), null));
        when(client.redis("SISMEMBER", List.of("key", "owner"))).thenReturn(
                result("SISMEMBER", "BOOLEAN", mapper.valueToTree(true), null));

        assertThat(executor.type("key")).isEqualTo("set");
        assertThat(executor.exists("key")).isTrue();
        assertThat(executor.scard("key")).isEqualTo(3);
        assertThat(executor.sismember("key", "owner")).isTrue();
    }

    @Test void preservesRemoteSecurityErrors() {
        when(client.redis("TYPE", List.of("key"))).thenThrow(
                new CimApiException("CIM_REDIS_FORBIDDEN", "Нет доступа"));
        assertThatThrownBy(() -> executor.type("key"))
                .isInstanceOfSatisfying(RedisCommandException.class, error -> {
                    assertThat(error.type()).isEqualTo("CIM_REDIS_FORBIDDEN");
                    assertThat(error.getMessage()).isEqualTo("Нет доступа");
                });
    }

    private CimApiClient.RedisResult result(String command, String type,
                                            com.fasterxml.jackson.databind.JsonNode value, String cursor) {
        return new CimApiClient.RedisResult(command, type, value, cursor,
                value != null && value.isArray() ? value.size() : value == null || value.isNull() ? 0 : 1, 2L, "IM");
    }
}
