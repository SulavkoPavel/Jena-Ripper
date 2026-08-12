package org.jenaripper;

import org.apache.jena.sparql.core.Quad;
import org.jenaripper.config.RdfSourceProperties;
import org.jenaripper.source.CimApiGraphDataSource;
import org.jenaripper.source.LocalJenaDataSource;
import org.jenaripper.source.RoutingGraphDataSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RoutingGraphDataSourceTest {
    private static final String RESOURCE_URI = "https://cim.so-ups.ru#_resource";

    @Test
    void routesGraphReadsToLocalJenaForLocalProfile() {
        LocalJenaDataSource local = mock(LocalJenaDataSource.class);
        CimApiGraphDataSource remote = mock(CimApiGraphDataSource.class);
        List<Quad> expected = List.of(mock(Quad.class));
        when(local.outgoing(RESOURCE_URI, 100)).thenReturn(expected);

        RoutingGraphDataSource source = new RoutingGraphDataSource(
                local, remote, new RdfSourceProperties(RdfSourceProperties.LOCAL_TDB2, null));

        assertThat(source.outgoing(RESOURCE_URI, 100)).isSameAs(expected);
        verifyNoInteractions(remote);
    }

    @Test
    void routesGraphReadsToCimApiForRemoteProfile() {
        LocalJenaDataSource local = mock(LocalJenaDataSource.class);
        CimApiGraphDataSource remote = mock(CimApiGraphDataSource.class);
        List<Quad> expected = List.of(mock(Quad.class));
        when(remote.outgoing(RESOURCE_URI, 100)).thenReturn(expected);

        RoutingGraphDataSource source = new RoutingGraphDataSource(
                local, remote, new RdfSourceProperties(RdfSourceProperties.CIM_API, null));

        assertThat(source.outgoing(RESOURCE_URI, 100)).isSameAs(expected);
        verifyNoInteractions(local);
    }
}
