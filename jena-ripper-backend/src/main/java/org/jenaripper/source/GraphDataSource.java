package org.jenaripper.source;

import org.apache.jena.sparql.core.Quad;

import java.util.List;

public interface GraphDataSource {
    List<Quad> outgoing(String uri, int limit);
    List<Quad> outgoingRelations(String uri, int limit);
    List<Quad> incoming(String uri, int limit);
    long outgoingCount(String uri);
    long incomingCount(String uri);
    boolean resourceExists(String uri);
    boolean subjectExists(String uri);
    List<String> findByLocalPart(String localPart, int limit);
    List<String> searchUris(String query, List<String> labelPredicates, int limit);
}
