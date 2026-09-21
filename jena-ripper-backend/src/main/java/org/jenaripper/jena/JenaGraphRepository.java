package org.jenaripper.jena;

import lombok.RequiredArgsConstructor;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.ParameterizedSparqlString;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.vocabulary.RDF;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class JenaGraphRepository {
    private final JenaReadExecutor executor;

    public List<Quad> outgoing(String uri, int limit) {
        return executor.read(() -> collect(executor.dataset().asDatasetGraph()
                .find(Node.ANY, NodeFactory.createURI(uri), Node.ANY, Node.ANY), limit));
    }

    public List<Quad> outgoingRelations(String uri, int limit) {
        return executor.read(() -> collectRelations(executor.dataset().asDatasetGraph()
                .find(Node.ANY, NodeFactory.createURI(uri), Node.ANY, Node.ANY), limit));
    }

    public List<Quad> incoming(String uri, int limit) {
        return executor.read(() -> collect(executor.dataset().asDatasetGraph()
                .find(Node.ANY, Node.ANY, Node.ANY, NodeFactory.createURI(uri)), limit));
    }

    public long outgoingCount(String uri) {
        return executor.read(() -> countRelations(executor.dataset().asDatasetGraph()
                .find(Node.ANY, NodeFactory.createURI(uri), Node.ANY, Node.ANY)));
    }

    public long incomingCount(String uri) {
        return executor.read(() -> count(executor.dataset().asDatasetGraph()
                .find(Node.ANY, Node.ANY, Node.ANY, NodeFactory.createURI(uri))));
    }

    public boolean resourceExists(String uri) {
        return executor.read(() -> {
            Node resource = NodeFactory.createURI(uri);
            DatasetGraph graph = executor.dataset().asDatasetGraph();
            return graph.find(Node.ANY, resource, Node.ANY, Node.ANY).hasNext()
                    || graph.find(Node.ANY, Node.ANY, Node.ANY, resource).hasNext();
        });
    }

    public boolean subjectExists(String uri) {
        return executor.read(() -> executor.dataset().asDatasetGraph()
                .find(Node.ANY, NodeFactory.createURI(uri), Node.ANY, Node.ANY).hasNext());
    }

    public List<String> findByLocalPart(String localPart, int limit) {
        return executor.read(() -> {
            ParameterizedSparqlString sparql = new ParameterizedSparqlString("""
                    SELECT DISTINCT ?resource WHERE {
                      { ?resource ?predicate ?value }
                      UNION { GRAPH ?graph { ?resource ?predicate ?value } }
                      FILTER(isIRI(?resource) && STRENDS(LCASE(STR(?resource)), LCASE(?localPart)))
                    } ORDER BY STR(?resource) LIMIT %d
                    """.formatted(limit));
            sparql.setLiteral("localPart", localPart);
            return selectUris(sparql);
        });
    }

    public List<String> searchUris(String query, List<String> labelPredicates, int limit) {
        return executor.read(() -> {
            String values = labelPredicates.stream().map(JenaGraphRepository::iri).reduce("", (a, b) -> a + " " + b);
            ParameterizedSparqlString sparql = new ParameterizedSparqlString("""
                    SELECT DISTINCT ?resource WHERE {
                      { ?resource ?predicate ?value }
                      UNION { GRAPH ?graph { ?resource ?predicate ?value } }
                      FILTER(isIRI(?resource))
                      OPTIONAL {
                        { ?resource ?textPredicate ?label }
                        UNION { GRAPH ?labelGraph { ?resource ?textPredicate ?label } }
                        VALUES ?textPredicate { %s }
                      }
                      OPTIONAL {
                        { ?resource rdf:type ?type }
                        UNION { GRAPH ?typeGraph { ?resource rdf:type ?type } }
                      }
                      FILTER(CONTAINS(LCASE(STR(?resource)), LCASE(?term)) ||
                             (BOUND(?label) && CONTAINS(LCASE(STR(?label)), LCASE(?term))) ||
                             (BOUND(?type) && CONTAINS(LCASE(STR(?type)), LCASE(?term))))
                    } ORDER BY STR(?resource) LIMIT %d
                    """.formatted(values, limit));
            sparql.setNsPrefix("rdf", RDF.getURI());
            sparql.setLiteral("term", query);
            return selectUris(sparql);
        });
    }

    private List<String> selectUris(ParameterizedSparqlString sparql) {
        List<String> result = new ArrayList<>();
        try (QueryExecution execution = QueryExecutionFactory.create(sparql.asQuery(), executor.dataset())) {
            execution.execSelect().forEachRemaining(row -> result.add(row.getResource("resource").getURI()));
        }
        return result;
    }

    private static String iri(String value) {
        if (value == null || !NodeFactory.createURI(value).isURI() || value.contains(">")) {
            throw new IllegalArgumentException("Invalid configured RDF predicate: " + value);
        }
        return "<" + value + ">";
    }

    private static List<Quad> collect(Iterator<Quad> quads, int limit) {
        List<Quad> result = new ArrayList<>();
        while (quads.hasNext() && result.size() < limit) result.add(quads.next());
        return result;
    }

    private static List<Quad> collectRelations(Iterator<Quad> quads, int limit) {
        List<Quad> result = new ArrayList<>();
        while (quads.hasNext() && result.size() < limit) {
            Quad quad = quads.next();
            if (quad.getObject().isURI() && !quad.getPredicate().getURI().equals(RDF.type.getURI())) result.add(quad);
        }
        return result;
    }

    private static long count(Iterator<Quad> quads) {
        long count = 0;
        while (quads.hasNext()) {
            quads.next();
            count++;
        }
        return count;
    }

    private static long countRelations(Iterator<Quad> quads) {
        long count = 0;
        while (quads.hasNext()) {
            Quad quad = quads.next();
            if (quad.getObject().isURI() && !quad.getPredicate().getURI().equals(RDF.type.getURI())) count++;
        }
        return count;
    }
}
