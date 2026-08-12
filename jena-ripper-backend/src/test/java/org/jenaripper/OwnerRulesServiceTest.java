package org.jenaripper;

import org.jenaripper.config.JenaRipperProperties;
import org.jenaripper.config.RdfSourceProperties;
import org.jenaripper.dto.GraphNodeDto;
import org.jenaripper.dto.OwnerRulesResponse;
import org.jenaripper.jena.JenaReadExecutor;
import org.jenaripper.owner.OwnerRulesResolver;
import org.jenaripper.remote.CimApiClient;
import org.jenaripper.service.GraphService;
import org.jenaripper.service.OwnerRulesService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OwnerRulesServiceTest {
    private static final String SO = "http://so-ups.ru/2015/schema-cim16#";
    private static final String RESOURCE = "https://cim.so-ups.ru#_resource";
    private static final String A = "https://cim.so-ups.ru#_a";
    private static final String B = "https://cim.so-ups.ru#_b";

    @Test
    void separatesAssetOwnerAndDataSourceInAllRemoteScenarios() {
        OwnerRulesResponse different = resolve(List.of(
                row(SO + "Object.OwnersToBottom", A),
                row(SO + "Object.DataSourceToBottom", B))).response();
        assertThat(different.assetOwners()).extracting(owner -> owner.uri()).containsExactly(A);
        assertThat(different.dataSources()).extracting(owner -> owner.uri()).containsExactly(B);

        OwnerRulesResponse same = resolve(List.of(
                row(SO + "Object.OwnersToBottom", A),
                row(SO + "Object.DataSourceToBottom", A))).response();
        assertThat(same.assetOwners()).extracting(owner -> owner.uri()).containsExactly(A);
        assertThat(same.dataSources()).extracting(owner -> owner.uri()).containsExactly(A);

        OwnerRulesResponse dataSourceOnly = resolve(List.of(
                row(SO + "Object.DataSourceToTop", B))).response();
        assertThat(dataSourceOnly.assetOwners()).isEmpty();
        assertThat(dataSourceOnly.dataSources()).extracting(owner -> owner.uri()).containsExactly(B);

        RemoteResult empty = resolve(List.of());
        assertThat(empty.response().assetOwners()).isEmpty();
        assertThat(empty.response().dataSources()).isEmpty();
        assertThat(empty.response().message()).contains("Asset Owner", "Data Source");
        assertThat(empty.query()).contains(
                "<http://so-ups.ru/2015/schema-cim16#Object.OwnersToBottom>",
                "<http://so-ups.ru/2015/schema-cim16#Object.DataSourceToBottom>");
    }

    private static RemoteResult resolve(List<Map<String, String>> rows) {
        GraphService graphService = mock(GraphService.class);
        CimApiClient cimApi = mock(CimApiClient.class);
        when(graphService.resolveResource(RESOURCE)).thenReturn(RESOURCE);
        when(graphService.node(anyString())).thenAnswer(invocation -> node(invocation.getArgument(0)));
        when(cimApi.select(anyString(), anyInt(), anyBoolean())).thenReturn(rows);
        OwnerRulesService service = new OwnerRulesService(
                mock(JenaReadExecutor.class), graphService, mock(OwnerRulesResolver.class),
                mock(JenaRipperProperties.class), new RdfSourceProperties("CIM_API", null), cimApi);

        OwnerRulesResponse response = service.resolve(RESOURCE);
        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(cimApi).select(query.capture(), anyInt(), anyBoolean());
        return new RemoteResult(response, query.getValue());
    }

    private static Map<String, String> row(String predicate, String owner) {
        return Map.of("predicate", predicate, "owner", owner);
    }

    private static GraphNodeDto node(String uri) {
        return new GraphNodeDto(uri, uri, uri, uri, uri, List.of(), "RESOURCE");
    }

    private record RemoteResult(OwnerRulesResponse response, String query) {
    }
}
