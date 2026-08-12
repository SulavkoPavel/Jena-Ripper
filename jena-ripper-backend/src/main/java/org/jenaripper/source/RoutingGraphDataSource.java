package org.jenaripper.source;

import org.apache.jena.sparql.core.Quad;
import org.jenaripper.config.RdfSourceProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

@Primary
@Component
public class RoutingGraphDataSource implements GraphDataSource {
    private final LocalJenaDataSource local;
    private final CimApiGraphDataSource remote;
    private final RdfSourceProperties properties;

    public RoutingGraphDataSource(LocalJenaDataSource local, CimApiGraphDataSource remote, RdfSourceProperties properties) {
        this.local = local;
        this.remote = remote;
        this.properties = properties;
    }

    private GraphDataSource active() {
        return properties.remote() ? remote : local;
    }

    public List<Quad> outgoing(String uri, int limit) { return active().outgoing(uri, limit); }
    public List<Quad> outgoingRelations(String uri, int limit) { return active().outgoingRelations(uri, limit); }
    public List<Quad> incoming(String uri, int limit) { return active().incoming(uri, limit); }
    public long outgoingCount(String uri) { return active().outgoingCount(uri); }
    public long incomingCount(String uri) { return active().incomingCount(uri); }
    public boolean resourceExists(String uri) { return active().resourceExists(uri); }
    public boolean subjectExists(String uri) { return active().subjectExists(uri); }
    public List<String> findByLocalPart(String value, int limit) { return active().findByLocalPart(value, limit); }
    public List<String> searchUris(String value, List<String> predicates, int limit) { return active().searchUris(value, predicates, limit); }
}
