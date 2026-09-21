package org.jenaripper.source;

import lombok.RequiredArgsConstructor;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.datatypes.TypeMapper;
import org.apache.jena.riot.out.NodeFmtLib;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.vocabulary.RDF;
import org.jenaripper.remote.CimApiClient;
import org.jenaripper.service.PrefixService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class CimApiGraphDataSource implements GraphDataSource {
    private final CimApiClient client;
    private final PrefixService prefixes;

    public List<Quad> outgoing(String uri, int limit) {
        String query = "SELECT ?p ?o (IF(isIRI(?o), 'uri', IF(isBlank(?o), 'bnode', 'literal')) AS ?oKind) " +
                "(DATATYPE(?o) AS ?oDatatype) (LANG(?o) AS ?oLang) WHERE { " + iri(uri) + " ?p ?o } LIMIT " + limit;
        return client.select(query, limit, false).stream().map(row -> new Quad(Quad.defaultGraphNodeGenerated,
                NodeFactory.createURI(uri), uriNode(row.get("p")), valueNode(row, "o"))).toList();
    }

    public List<Quad> outgoingRelations(String uri, int limit) {
        return outgoing(uri, limit).stream().filter(quad -> quad.getObject().isURI()
                && !RDF.type.getURI().equals(quad.getPredicate().getURI())).toList();
    }

    public List<Quad> incoming(String uri, int limit) {
        String query = "SELECT ?s ?p WHERE { ?s ?p " + iri(uri) + " } LIMIT " + limit;
        return client.select(query, limit, false).stream().map(row -> new Quad(Quad.defaultGraphNodeGenerated,
                uriNode(row.get("s")), uriNode(row.get("p")), NodeFactory.createURI(uri))).toList();
    }

    public long outgoingCount(String uri) { return count("SELECT (COUNT(*) AS ?count) WHERE { " + iri(uri) + " ?p ?o }"); }
    public long incomingCount(String uri) { return count("SELECT (COUNT(*) AS ?count) WHERE { ?s ?p " + iri(uri) + " }"); }

    public boolean resourceExists(String uri) {
        String query = "SELECT ?resource WHERE { { " + iri(uri) + " ?p ?o } UNION { ?s ?p " + iri(uri) + " } BIND(" + iri(uri) + " AS ?resource) } LIMIT 1";
        return !client.select(query, 1, false).isEmpty();
    }

    public boolean subjectExists(String uri) {
        return !client.select("SELECT ?p WHERE { " + iri(uri) + " ?p ?o } LIMIT 1", 1, false).isEmpty();
    }

    public List<String> findByLocalPart(String localPart, int limit) {
        String term = NodeFmtLib.strNT(NodeFactory.createLiteral(localPart));
        String query = "SELECT DISTINCT ?resource WHERE { ?resource ?p ?o FILTER(isIRI(?resource) && " +
                "STRENDS(LCASE(STR(?resource)), LCASE(" + term + "))) } ORDER BY STR(?resource) LIMIT " + limit;
        return uris(client.select(query, limit, false), "resource");
    }

    public List<String> searchUris(String queryText, List<String> labelPredicates, int limit) {
        String term = NodeFmtLib.strNT(NodeFactory.createLiteral(queryText));
        String values = labelPredicates.stream().map(CimApiGraphDataSource::iri).reduce("", (a, b) -> a + " " + b);
        String query = "SELECT DISTINCT ?resource WHERE { ?resource ?predicate ?value FILTER(isIRI(?resource)) " +
                "OPTIONAL { ?resource ?textPredicate ?label VALUES ?textPredicate {" + values + "} } " +
                "OPTIONAL { ?resource <" + RDF.type.getURI() + "> ?type } " +
                "FILTER(CONTAINS(LCASE(STR(?resource)), LCASE(" + term + ")) || " +
                "(BOUND(?label) && CONTAINS(LCASE(STR(?label)), LCASE(" + term + "))) || " +
                "(BOUND(?type) && CONTAINS(LCASE(STR(?type)), LCASE(" + term + ")))) } " +
                "ORDER BY STR(?resource) LIMIT " + limit;
        return uris(client.select(query, limit, false), "resource");
    }

    private long count(String query) {
        List<Map<String, String>> rows = client.select(query, 1, false);
        if (rows.isEmpty() || rows.get(0).get("count") == null) return 0;
        try { return Long.parseLong(rows.get(0).get("count")); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private List<String> uris(List<Map<String, String>> rows, String variable) {
        List<String> values = new ArrayList<>();
        for (Map<String, String> row : rows) if (row.get(variable) != null) values.add(prefixes.expand(row.get(variable)));
        return values;
    }

    private Node valueNode(Map<String, String> row, String variable) {
        String value = row.get(variable);
        String kind = row.get(variable + "Kind");
        if ("uri".equals(kind)) return uriNode(value);
        if ("bnode".equals(kind) || "true".equals(row.get(variable + ".isBlank"))) {
            return NodeFactory.createBlankNode(value == null ? "remote" : value.replaceFirst("^_:", ""));
        }
        String language = row.get(variable + "Lang");
        String datatype = row.get(variable + "Datatype");
        if (language != null && !language.isBlank()) return NodeFactory.createLiteral(value, language);
        if (datatype != null && !datatype.isBlank()) return NodeFactory.createLiteral(value,
                TypeMapper.getInstance().getSafeTypeByName(prefixes.expand(datatype)));
        return NodeFactory.createLiteral(value == null ? "" : value);
    }

    private Node uriNode(String value) { return NodeFactory.createURI(prefixes.expand(value)); }
    private static String iri(String value) {
        if (value == null || !value.matches("https?://[^>\\s]+")) throw new IllegalArgumentException("Некорректный RDF URI.");
        return "<" + value + ">";
    }
}
