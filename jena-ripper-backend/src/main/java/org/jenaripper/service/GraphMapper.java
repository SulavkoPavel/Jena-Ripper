package org.jenaripper.service;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.vocabulary.RDF;
import org.jenaripper.config.JenaRipperProperties;
import org.jenaripper.dto.GraphEdgeDto;
import org.jenaripper.dto.GraphNodeDto;
import org.jenaripper.dto.RdfPropertyDto;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class GraphMapper {
    private final PrefixService prefixService;
    private final List<String> labelPredicates;

    public GraphMapper(PrefixService prefixService, JenaRipperProperties properties) {
        this.prefixService = prefixService;
        this.labelPredicates = List.copyOf(properties.labels().predicates());
    }

    public GraphNodeDto toNode(String uri, List<Quad> outgoing) {
        return toNode(NodeFactory.createURI(uri), outgoing);
    }

    public GraphNodeDto toNode(Node resource, List<Quad> outgoing) {
        String id = resourceId(resource);
        Set<String> types = new LinkedHashSet<>();
        for (Quad quad : outgoing) {
            if (quad.getPredicate().getURI().equals(RDF.type.getURI()) && quad.getObject().isURI()) {
                types.add(prefixService.compact(quad.getObject().getURI()));
            }
        }
        return new GraphNodeDto(
                id,
                id,
                resource.isURI() ? prefixService.compact(id) : id,
                label(id, outgoing),
                localName(id),
                List.copyOf(types),
                resource.isBlank() ? "BLANK_NODE" : "RESOURCE");
    }

    public List<RdfPropertyDto> properties(List<Quad> outgoing) {
        List<RdfPropertyDto> result = new ArrayList<>();
        for (Quad quad : outgoing) {
            String predicateUri = quad.getPredicate().getURI();
            if (predicateUri.equals(RDF.type.getURI())) continue;
            Node value = quad.getObject();
            if (value.isLiteral()) {
                result.add(new RdfPropertyDto(
                        prefixService.compact(predicateUri), predicateUri, value.getLiteralLexicalForm(), null,
                        "LITERAL", value.getLiteralDatatypeURI(), value.getLiteralLanguage()));
            } else if (value.isURI()) {
                result.add(new RdfPropertyDto(
                        prefixService.compact(predicateUri), predicateUri,
                        prefixService.compact(value.getURI()), value.getURI(), "RESOURCE", null, null));
            } else if (value.isBlank()) {
                String blank = "_:" + value.getBlankNodeLabel();
                result.add(new RdfPropertyDto(
                        prefixService.compact(predicateUri), predicateUri, blank, blank, "BLANK_NODE", null, null));
            }
        }
        return result;
    }

    public String label(String uri, List<Quad> outgoing) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Quad quad : outgoing) {
            if (quad.getObject().isLiteral()) {
                values.putIfAbsent(quad.getPredicate().getURI(), quad.getObject().getLiteralLexicalForm());
            }
        }
        for (String predicate : labelPredicates) {
            String value = values.get(predicate);
            if (value != null && !value.isBlank()) return value;
        }
        String compact = prefixService.compact(uri);
        return compact.equals(uri) ? localName(uri) : compact;
    }

    public String mrid(List<Quad> outgoing) {
        return outgoing.stream()
                .filter(quad -> quad.getPredicate().getURI().endsWith("#IdentifiedObject.mRID"))
                .map(Quad::getObject)
                .filter(Node::isLiteral)
                .map(Node::getLiteralLexicalForm)
                .findFirst()
                .orElse(null);
    }

    public GraphEdgeDto edge(Node source, Node predicate, Node target, String direction) {
        String sourceId = resourceId(source);
        String targetId = resourceId(target);
        String predicateUri = predicate.getURI();
        String compactPredicate = prefixService.compact(predicateUri);
        String raw = sourceId + "\u0000" + predicateUri + "\u0000" + targetId;
        return new GraphEdgeDto(
                sha256(raw), sourceId, targetId, predicateUri, compactPredicate,
                localName(compactPredicate), direction);
    }

    public static String resourceId(Node node) {
        if (node.isURI()) return node.getURI();
        if (node.isBlank()) return "_:" + node.getBlankNodeLabel();
        throw new IllegalArgumentException("Node is not an RDF resource: " + node);
    }

    public static String localName(String uri) {
        int split = Math.max(Math.max(uri.lastIndexOf('#'), uri.lastIndexOf('/')), uri.indexOf(':'));
        return split >= 0 && split + 1 < uri.length() ? uri.substring(split + 1) : uri;
    }

    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
