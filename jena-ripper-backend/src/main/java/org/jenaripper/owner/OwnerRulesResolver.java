package org.jenaripper.owner;

import org.apache.jena.graph.Triple;
import org.apache.jena.rdf.model.InfModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.reasoner.Derivation;
import org.apache.jena.reasoner.rulesys.GenericRuleReasoner;
import org.apache.jena.reasoner.rulesys.RuleDerivation;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.vocabulary.RDF;
import org.jenaripper.dto.OwnerPathDto;
import org.jenaripper.dto.OwnerPathStepDto;
import org.jenaripper.dto.OwnerResourceDto;
import org.jenaripper.dto.OwnerRuleDto;
import org.jenaripper.dto.GraphNodeDto;
import org.jenaripper.service.GraphMapper;
import org.jenaripper.service.PrefixService;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.Iterator;

@Component
public class OwnerRulesResolver {
    private static final String SO = "http://so-ups.ru/2015/schema-cim16#";
    private static final Set<String> ASSET_OWNER_PREDICATES = Set.of(
            SO + "Object.OwnersToBottom", SO + "Object.OwnersToTop");
    private static final Set<String> DATA_SOURCE_PREDICATES = Set.of(
            SO + "Object.DataSourceToBottom", SO + "Object.DataSourceToTop");
    private static final Set<String> INFERRED_PREDICATES = Set.of(
            SO + "Object.OwnersToBottom", SO + "Object.OwnersToTop",
            SO + "Object.DataSourceToBottom", SO + "Object.DataSourceToTop",
            SO + "HasDirectAssetOwner", SO + "HasNotDirectAssetOwner", SO + "HasDirectAssetDataSource");

    private final OwnerRuleGenerator ruleGenerator;
    private final PrefixService prefixService;
    private final GraphMapper graphMapper;

    public OwnerRulesResolver(OwnerRuleGenerator ruleGenerator, PrefixService prefixService, GraphMapper graphMapper) {
        this.ruleGenerator = ruleGenerator;
        this.prefixService = prefixService;
        this.graphMapper = graphMapper;
    }

    public OwnerResolution resolve(Model data, String uri) {
        OwnerRuleBundle rules = ruleGenerator.rules();
        GenericRuleReasoner directReasoner = new GenericRuleReasoner(rules.directAssetRules());
        GenericRuleReasoner ownerReasoner = new GenericRuleReasoner(rules.ownerRules());
        InfModel directModel = ModelFactory.createInfModel(directReasoner, data);
        directModel.setDerivationLogging(true);
        InfModel ownerModel = ModelFactory.createInfModel(ownerReasoner, directModel);
        ownerModel.setDerivationLogging(true);
        ownerModel.prepare();
            Resource selected = ownerModel.createResource(uri);
        RoleResolution assetOwners = collectRole(selected, ASSET_OWNER_PREDICATES, ownerModel, directModel, data, uri);
        RoleResolution dataSources = collectRole(selected, DATA_SOURCE_PREDICATES, ownerModel, directModel, data, uri);
        List<OwnerResourceDto> resources = java.util.stream.Stream.concat(
                        assetOwners.resources().stream(), dataSources.resources().stream())
                .collect(java.util.stream.Collectors.toMap(OwnerResourceDto::uri, value -> value,
                        (left, right) -> left, LinkedHashMap::new)).values().stream().toList();
        List<OwnerRuleDto> rulesUsed = java.util.stream.Stream.concat(
                        assetOwners.rules().stream(), dataSources.rules().stream()).distinct().toList();
        List<OwnerPathDto> paths = java.util.stream.Stream.concat(
                        assetOwners.paths().stream(), dataSources.paths().stream()).distinct().toList();
        return new OwnerResolution(
                resource(data, uri),
                assetOwners.resources(), dataSources.resources(),
                assetOwners.rules(), dataSources.rules(),
                assetOwners.paths(), dataSources.paths(),
                resources, rulesUsed, paths);
    }

    private RoleResolution collectRole(
            Resource selected,
            Set<String> predicates,
            InfModel ownerModel,
            InfModel directModel,
            Model data,
            String resourceUri) {
        Map<String, Resource> resources = new LinkedHashMap<>();
        List<Statement> statements = new ArrayList<>();
        for (String predicateUri : predicates) {
            Property predicate = ownerModel.createProperty(predicateUri);
            ownerModel.listStatements(selected, predicate, (org.apache.jena.rdf.model.RDFNode) null)
                    .forEachRemaining(statement -> {
                        if (statement.getObject().isURIResource()) {
                            Resource result = statement.getResource();
                            resources.putIfAbsent(result.getURI(), result);
                            statements.add(statement);
                        }
                    });
        }
        DerivationCollector collector = new DerivationCollector(prefixService);
        statements.forEach(statement -> collector.collect(statement, ownerModel, directModel));
        List<OwnerResourceDto> resourceDtos = resources.keySet().stream()
                .map(result -> resource(data, result)).toList();
        List<OwnerPathDto> paths = resources.keySet().stream()
                .map(result -> path(resourceUri, result, collector.matches(), data))
                .filter(result -> !result.steps().isEmpty())
                .toList();
        return new RoleResolution(resourceDtos, collector.rules(), paths);
    }

    private OwnerPathDto path(String start, String owner, List<Triple> matches, Model data) {
        Map<String, List<PathEdge>> adjacency = new HashMap<>();
        for (Triple triple : matches) {
            if (!triple.getSubject().isURI() || !triple.getObject().isURI()) continue;
            String predicate = triple.getPredicate().getURI();
            if (RDF.type.getURI().equals(predicate) || INFERRED_PREDICATES.contains(predicate)) continue;
            PathEdge edge = new PathEdge(triple.getSubject().getURI(), predicate, triple.getObject().getURI());
            adjacency.computeIfAbsent(edge.subject(), ignored -> new ArrayList<>()).add(edge);
            adjacency.computeIfAbsent(edge.object(), ignored -> new ArrayList<>()).add(edge);
        }
        Queue<String> queue = new ArrayDeque<>();
        Map<String, PathEdge> previous = new HashMap<>();
        Set<String> visited = new HashSet<>();
        queue.add(start);
        visited.add(start);
        while (!queue.isEmpty() && !visited.contains(owner)) {
            String current = queue.remove();
            for (PathEdge edge : adjacency.getOrDefault(current, List.of())) {
                String next = edge.other(current);
                if (visited.add(next)) {
                    previous.put(next, edge);
                    queue.add(next);
                }
            }
        }
        if (!visited.contains(owner)) return new OwnerPathDto(owner, List.of());
        List<OwnerPathStepDto> reversed = new ArrayList<>();
        String current = owner;
        while (!current.equals(start)) {
            PathEdge edge = previous.get(current);
            String from = edge.other(current);
            boolean outgoing = edge.subject().equals(from);
            reversed.add(new OwnerPathStepDto(
                    resource(data, from), prefixService.compact(edge.predicate()), edge.predicate(),
                    outgoing ? "outgoing" : "incoming", resource(data, current)));
            current = from;
        }
        Collections.reverse(reversed);
        return new OwnerPathDto(owner, List.copyOf(reversed));
    }

    private OwnerResourceDto resource(Model data, String uri) {
        Resource resource = data.createResource(uri);
        List<Quad> outgoing = data.listStatements(resource, null, (org.apache.jena.rdf.model.RDFNode) null)
                .mapWith(statement -> Quad.create(Quad.defaultGraphNodeGenerated, statement.asTriple()))
                .toList();
        GraphNodeDto node = graphMapper.toNode(uri, outgoing);
        return new OwnerResourceDto(uri, node.compactUri(), node.label(), node.types());
    }

    private record PathEdge(String subject, String predicate, String object) {
        String other(String uri) {
            return subject.equals(uri) ? object : subject;
        }
    }

    private record RoleResolution(
            List<OwnerResourceDto> resources,
            List<OwnerRuleDto> rules,
            List<OwnerPathDto> paths) {
    }

    private static final class DerivationCollector {
        private final PrefixService prefixes;
        private final Map<String, OwnerRuleDto> rules = new LinkedHashMap<>();
        private final Map<String, Triple> matches = new LinkedHashMap<>();
        private final Set<String> visited = new HashSet<>();

        private DerivationCollector(PrefixService prefixes) {
            this.prefixes = prefixes;
        }

        void collect(org.apache.jena.rdf.model.Statement statement, InfModel ownerModel, InfModel directModel) {
            collectFrom(statement, ownerModel, ownerModel, directModel);
        }

        private void collectFrom(
                org.apache.jena.rdf.model.Statement statement,
                InfModel source,
                InfModel ownerModel,
                InfModel directModel) {
            Iterator<Derivation> derivations = source.getDerivation(statement);
            while (derivations.hasNext()) {
                Derivation derivation = derivations.next();
                if (!(derivation instanceof RuleDerivation ruleDerivation)) continue;
                String key = ruleDerivation.getRule().getName() + "|" + ruleDerivation.getConclusion();
                if (!visited.add(key)) continue;
                List<String> premises = ruleDerivation.getMatches().stream()
                        .filter(java.util.Objects::nonNull).map(this::display).toList();
                String ruleName = ruleDerivation.getRule().getName();
                rules.putIfAbsent(key, new OwnerRuleDto(
                        ruleName == null || ruleName.isBlank() ? "anonymous" : ruleName,
                        display(ruleDerivation.getConclusion()), premises));
                for (Triple match : ruleDerivation.getMatches()) {
                    if (match == null) continue;
                    matches.putIfAbsent(match.toString(), match);
                    Statement nested = ownerModel.asStatement(match);
                    collectFrom(nested, ownerModel, ownerModel, directModel);
                    collectFrom(directModel.asStatement(match), directModel, ownerModel, directModel);
                }
            }
        }

        private String display(Triple triple) {
            return display(triple.getSubject()) + " " + display(triple.getPredicate()) + " " + display(triple.getObject());
        }

        private String display(org.apache.jena.graph.Node node) {
            if (node.isURI()) return prefixes.compact(node.getURI());
            if (node.isLiteral()) return '"' + node.getLiteralLexicalForm() + '"';
            return node.toString();
        }

        List<OwnerRuleDto> rules() {
            return List.copyOf(rules.values());
        }

        List<Triple> matches() {
            return List.copyOf(matches.values());
        }
    }
}
