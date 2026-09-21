package org.jenaripper.service;

import org.apache.jena.sparql.core.Quad;
import lombok.RequiredArgsConstructor;
import org.jenaripper.config.JenaRipperProperties;
import org.jenaripper.dto.GraphEdgeDto;
import org.jenaripper.dto.GraphNodeDto;
import org.jenaripper.dto.GraphResponseDto;
import org.jenaripper.dto.NeighborStatsDto;
import org.jenaripper.dto.NodeDetailsDto;
import org.jenaripper.dto.SearchResultDto;
import org.jenaripper.source.GraphDataSource;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class GraphService {
    private static final int AMBIGUITY_DETECTION_LIMIT = 2;
    private final GraphDataSource repository;
    private final GraphMapper mapper;
    private final PrefixService prefixService;
    private final JenaRipperProperties properties;

    public GraphNodeDto node(String resource) {
        String uri = resolveResource(resource);
        return mapper.toNode(uri, repository.outgoing(uri, properties.graph().maxNeighbors()));
    }

    public NodeDetailsDto details(String resource) {
        String uri = resolveResource(resource);
        int limit = properties.graph().maxNeighbors();
        List<Quad> outgoing = repository.outgoing(uri, limit + 1);
        boolean truncated = outgoing.size() > limit;
        if (truncated) outgoing = new ArrayList<>(outgoing.subList(0, limit));
        GraphNodeDto node = mapper.toNode(uri, outgoing);
        return new NodeDetailsDto(
                uri, node.compactUri(), node.label(), node.localName(), mapper.mrid(outgoing),
                node.types(), node.nodeType(), mapper.properties(outgoing),
                repository.incomingCount(uri), repository.outgoingCount(uri), truncated);
    }

    public GraphResponseDto neighbors(String resource) {
        String uri = resolveResource(resource);
        int limit = properties.graph().maxNeighbors();
        long outgoingTotal = repository.outgoingCount(uri);
        long incomingTotal = repository.incomingCount(uri);
        List<Quad> outgoing = repository.outgoingRelations(uri, limit);
        int incomingLimit = Math.max(0, limit - outgoing.size());
        List<Quad> incoming = repository.incoming(uri, incomingLimit);
        Map<String, List<Quad>> nodeQuads = new LinkedHashMap<>();
        nodeQuads.put(uri, repository.outgoing(uri, limit));
        Map<String, GraphEdgeDto> edges = new LinkedHashMap<>();

        for (Quad quad : outgoing) {
            if (quad.getObject().isURI() && !quad.getPredicate().getURI().equals(org.apache.jena.vocabulary.RDF.type.getURI())) {
                String target = quad.getObject().getURI();
                nodeQuads.computeIfAbsent(target, key -> repository.outgoing(key, limit));
                GraphEdgeDto edge = mapper.edge(quad.getSubject(), quad.getPredicate(), quad.getObject(), "outgoing");
                edges.putIfAbsent(edge.id(), edge);
            }
        }
        for (Quad quad : incoming) {
            if (quad.getSubject().isURI()) {
                String source = quad.getSubject().getURI();
                nodeQuads.computeIfAbsent(source, key -> repository.outgoing(key, limit));
                GraphEdgeDto edge = mapper.edge(quad.getSubject(), quad.getPredicate(), quad.getObject(), "incoming");
                edges.putIfAbsent(edge.id(), edge);
            }
        }
        List<GraphNodeDto> nodes = nodeQuads.entrySet().stream().map(entry -> mapper.toNode(entry.getKey(), entry.getValue())).toList();
        long total = incomingTotal + outgoingTotal;
        NeighborStatsDto stats = new NeighborStatsDto(uri, incomingTotal, outgoingTotal, limit, total > limit);
        return new GraphResponseDto(nodes, new ArrayList<>(edges.values()), stats);
    }

    public List<SearchResultDto> search(String query) {
        if (query == null || query.isBlank()) return List.of();
        String input = query.trim();
        String expanded = prefixService.expand(input);
        int limit = properties.api().searchLimit();
        Set<String> uris = new LinkedHashSet<>();
        boolean compactSubject = !expanded.equals(input) && repository.subjectExists(expanded);

        if (isAbsoluteUri(input) && repository.resourceExists(expanded)) uris.add(expanded);
        if (compactSubject) uris.add(expanded);
        if (!isAbsoluteUri(input) && !compactSubject) {
            uris.addAll(repository.findByLocalPart(stripLeadingUnderscore(input), AMBIGUITY_DETECTION_LIMIT));
        }
        boolean directIdentifier = isAbsoluteUri(input)
                || input.matches("_?[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                || compactSubject;
        if (uris.isEmpty() || !directIdentifier) {
            uris.addAll(repository.searchUris(expanded, properties.labels().predicates(), limit));
        }

        return uris.stream().limit(limit).map(uri -> {
            GraphNodeDto node = mapper.toNode(uri, repository.outgoing(uri, properties.graph().maxNeighbors()));
            return new SearchResultDto(uri, node.compactUri(), node.label(), node.types());
        }).toList();
    }

    public String resolveResource(String input) {
        if (input == null || input.isBlank()) throw new IllegalArgumentException("Укажите URI, compact URI или UUID");
        String value = input.trim();
        String expanded = prefixService.expand(value);
        if (isAbsoluteUri(expanded)) {
            if (repository.resourceExists(expanded)) return expanded;
            throw new IllegalArgumentException("RDF resource не найден: " + value);
        }
        List<String> matches = repository.findByLocalPart(
                stripLeadingUnderscore(value), AMBIGUITY_DETECTION_LIMIT);
        if (matches.isEmpty()) throw new IllegalArgumentException("RDF resource не найден: " + value);
        if (matches.size() > 1) throw new IllegalArgumentException("Идентификатор неоднозначен; укажите compact или полный URI");
        return matches.get(0);
    }

    private static String stripLeadingUnderscore(String value) {
        int colon = value.indexOf(':');
        String local = colon >= 0 ? value.substring(colon + 1) : value;
        return local.startsWith("_") ? local.substring(1) : local;
    }

    private static boolean isAbsoluteUri(String value) {
        try {
            return URI.create(value).isAbsolute() && value.contains("://");
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
