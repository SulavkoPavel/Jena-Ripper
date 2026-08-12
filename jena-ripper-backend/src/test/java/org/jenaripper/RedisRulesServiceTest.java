package org.jenaripper;

import org.jenaripper.config.JenaRipperProperties;
import org.jenaripper.dto.GraphNodeDto;
import org.jenaripper.dto.RedisRulesResponse;
import org.jenaripper.redis.RedisPermissionReader;
import org.jenaripper.redis.RedisRulesUnavailableException;
import org.jenaripper.service.GraphService;
import org.jenaripper.service.PrefixService;
import org.jenaripper.service.RedisRulesService;
import org.jenaripper.config.RdfSourceProperties;
import org.jenaripper.remote.CimApiClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RedisRulesServiceTest {
    private static final String OBJECT = "ups:_object-without-uuid";
    private static final String URI = "https://cim.so-ups.ru#_object-without-uuid";

    @Test
    void readsReadReadTopWriteAndMultipleValuesWithoutScanning() {
        FakeReader reader = new FakeReader(Map.of(
                "2/rb:" + OBJECT, Set.of("ups:_company-a", "ups:_company-b"),
                "2/rt:" + OBJECT, Set.of("ups:_company-top"),
                "2/wb:ups:_company-a", Set.of(OBJECT),
                "2/wb:ups:_company-b", Set.of("ups:_another-object"),
                "2/wb:ups:_company-top", Set.of(OBJECT)));

        RedisRulesResponse response = service(reader, "IM", null, List.of()).inspect(OBJECT);

        assertThat(response.resource().compactUri()).isEqualTo(OBJECT);
        assertThat(response.permissions().read()).hasSize(2);
        assertThat(response.permissions().readTop()).hasSize(1);
        assertThat(response.permissions().write()).extracting(resource -> resource.compactUri())
                .containsExactlyInAnyOrder("ups:_company-a", "ups:_company-top");
        assertThat(response.rawKeys()).extracting(key -> key.key()).contains("2/rb:" + OBJECT, "2/rt:" + OBJECT);
        assertThat(response.message()).isNull();
    }

    @Test
    void missingKeysAreNormalEmptyResult() {
        RedisRulesResponse response = service(new FakeReader(Map.of()), "IM", null, List.of()).inspect(OBJECT);
        assertThat(response.permissions().read()).isEmpty();
        assertThat(response.permissions().readTop()).isEmpty();
        assertThat(response.permissions().write()).isEmpty();
        assertThat(response.message()).contains("нет прав");
    }

    @Test
    void appliesPimDiffInheritedAndReverseSemantics() {
        FakeReader reader = new FakeReader(Map.of(
                "7/rb:" + OBJECT, Set.of("ups:_reader"),
                "2/rev:/rb:" + OBJECT, Set.of("ups:_reader"),
                "7/wb:ups:_reader", Set.of(OBJECT),
                "2/rev:/wb:ups:_reader", Set.of(OBJECT)));

        RedisRulesResponse response = service(reader, "PIM_DIFF", 91L, List.of(7L)).inspect(OBJECT);

        assertThat(response.permissions().read()).isEmpty();
        assertThat(response.permissions().write()).isEmpty();
        assertThat(response.rawKeys()).extracting(key -> key.key())
                .contains("7/rb:" + OBJECT, "2/rev:/rb:" + OBJECT, "91/2/rev:/wb:ups:_reader");
    }

    @Test
    void preservesUnavailableAndTimeoutErrors() {
        RedisPermissionReader unavailable = new RedisPermissionReader() {
            public Set<String> members(String key) { throw new RedisRulesUnavailableException("timeout", true, null); }
            public boolean contains(String key, String member) { return false; }
            public long size(String key) { return 0; }
        };
        assertThatThrownBy(() -> service(unavailable, "IM", null, List.of()).inspect(OBJECT))
                .isInstanceOf(RedisRulesUnavailableException.class)
                .extracting(error -> ((RedisRulesUnavailableException) error).timeout()).isEqualTo(true);
    }

    @Test
    void usesSeparateRedisConnectionForCimApiSource() {
        RedisPermissionReader reader = new FakeReader(Map.of(
                "2/rb:" + OBJECT, Set.of("ups:_company-a"),
                "2/wb:ups:_company-a", Set.of(OBJECT)));
        GraphService graph = graph();
        CimApiClient client = mock(CimApiClient.class);
        RedisRulesResponse response = new RedisRulesService(reader, graph, new PrefixService(), properties("IM", null, List.of()),
                new RdfSourceProperties("CIM_API", null), client).inspect(OBJECT);
        assertThat(response.datasetId()).isEqualTo(2L);
        assertThat(response.modelType()).isEqualTo("IM");
        assertThat(response.permissions().read()).hasSize(1);
        assertThat(response.permissions().readTop()).isEmpty();
        assertThat(response.permissions().write()).hasSize(1);
        assertThat(response.rawKeys()).extracting(key -> key.key())
                .contains("2/rb:" + OBJECT, "2/wb:ups:_company-a");
        verifyNoInteractions(client);
    }

    private RedisRulesService service(RedisPermissionReader reader, String modelType, Long diffId, List<Long> additional) {
        GraphService graph = graph();
        return new RedisRulesService(reader, graph, new PrefixService(), properties(modelType, diffId, additional));
    }

    private GraphService graph() {
        GraphService graph = mock(GraphService.class);
        when(graph.resolveResource(anyString())).thenReturn(URI);
        when(graph.node(anyString())).thenAnswer(invocation -> {
            String value = invocation.getArgument(0);
            String compact = value.startsWith("ups:") ? value : new PrefixService().compact(value);
            return new GraphNodeDto(value, value, compact, compact, compact, List.of(), "RESOURCE");
        });
        return graph;
    }

    private JenaRipperProperties properties(String modelType, Long diffId, List<Long> additional) {
        return new JenaRipperProperties(
                new JenaRipperProperties.Dataset("memory", "unused"),
                new JenaRipperProperties.Graph(100),
                new JenaRipperProperties.Labels(List.of()),
                new JenaRipperProperties.Api(List.of(), 25),
                new JenaRipperProperties.Sparql(Duration.ofSeconds(5), 100, 100, List.of()),
                null,
                new JenaRipperProperties.RedisRules(true, "localhost", 6379, "", "", 0,
                        Duration.ofSeconds(1), 2L, modelType, diffId, additional),
                null);
    }

    private static final class FakeReader implements RedisPermissionReader {
        private final Map<String, Set<String>> data = new LinkedHashMap<>();

        private FakeReader(Map<String, Set<String>> data) {
            this.data.putAll(data);
        }

        public Set<String> members(String key) { return data.getOrDefault(key, Set.of()); }
        public boolean contains(String key, String member) { return data.getOrDefault(key, Set.of()).contains(member); }
        public long size(String key) { return data.getOrDefault(key, Set.of()).size(); }
    }
}
