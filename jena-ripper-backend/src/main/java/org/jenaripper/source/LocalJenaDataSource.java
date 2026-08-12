package org.jenaripper.source;

import org.apache.jena.sparql.core.Quad;
import org.jenaripper.jena.JenaGraphRepository;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LocalJenaDataSource implements GraphDataSource {
    private final JenaGraphRepository repository;

    public LocalJenaDataSource(JenaGraphRepository repository) { this.repository = repository; }
    public List<Quad> outgoing(String uri, int limit) { return repository.outgoing(uri, limit); }
    public List<Quad> outgoingRelations(String uri, int limit) { return repository.outgoingRelations(uri, limit); }
    public List<Quad> incoming(String uri, int limit) { return repository.incoming(uri, limit); }
    public long outgoingCount(String uri) { return repository.outgoingCount(uri); }
    public long incomingCount(String uri) { return repository.incomingCount(uri); }
    public boolean resourceExists(String uri) { return repository.resourceExists(uri); }
    public boolean subjectExists(String uri) { return repository.subjectExists(uri); }
    public List<String> findByLocalPart(String localPart, int limit) { return repository.findByLocalPart(localPart, limit); }
    public List<String> searchUris(String query, List<String> predicates, int limit) { return repository.searchUris(query, predicates, limit); }
}
