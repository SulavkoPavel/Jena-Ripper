package org.jenaripper.service;

import org.apache.jena.rdf.model.ModelFactory;
import org.jenaripper.config.JenaRipperProperties;
import org.jenaripper.config.RdfSourceProperties;
import org.jenaripper.dto.OwnerRulesResponse;
import org.jenaripper.dto.OwnerResourceDto;
import org.jenaripper.dto.OwnerRuleDto;
import org.jenaripper.jena.JenaReadExecutor;
import org.jenaripper.remote.CimApiClient;
import org.jenaripper.owner.OwnerRulesResolver;
import org.jenaripper.owner.OwnerResolution;
import org.jenaripper.dto.GraphNodeDto;
import org.apache.jena.query.Dataset;
import org.apache.jena.rdf.model.Model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jenaripper.owner.OwnerRulesUnavailableException;
import org.springframework.stereotype.Service;

@Service
public class OwnerRulesService {
    private final JenaReadExecutor executor;
    private final GraphService graphService;
    private final OwnerRulesResolver resolver;
    private final JenaRipperProperties properties;
    private final RdfSourceProperties source;
    private final CimApiClient cimApi;

    public OwnerRulesService(
            JenaReadExecutor executor,
            GraphService graphService,
            OwnerRulesResolver resolver, JenaRipperProperties properties,
            RdfSourceProperties source, CimApiClient cimApi) {
        this.executor = executor;
        this.graphService = graphService;
        this.resolver = resolver;
        this.properties = properties;
        this.source = source;
        this.cimApi = cimApi;
    }

    public OwnerRulesResponse resolve(String input) {
        if (source.remote()) return resolveRemote(input);
        if (properties.ownerRules() == null || !properties.ownerRules().enabled()) {
            throw new OwnerRulesUnavailableException("Owner Rules отключены в конфигурации.");
        }
        String uri = graphService.resolveResource(input);
        long started = System.nanoTime();
        OwnerResolution resolution = executor.read(() -> {
            Dataset dataset = executor.dataset();
            Model data = ModelFactory.createUnion(dataset.getDefaultModel(), dataset.getUnionModel());
            return resolver.resolve(data, uri);
        });
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        String message = resolution.assetOwners().isEmpty() && resolution.dataSources().isEmpty()
                ? "Asset Owner и Data Source не определены." : null;
        return new OwnerRulesResponse(
                resolution.resource(),
                resolution.assetOwners(), resolution.dataSources(),
                resolution.assetOwnerMatchedRules(), resolution.dataSourceMatchedRules(),
                resolution.assetOwnerPaths(), resolution.dataSourcePaths(),
                resolution.owners(), resolution.matchedRules(), resolution.paths(), elapsed, message);
    }

    private OwnerRulesResponse resolveRemote(String input) {
        String uri = graphService.resolveResource(input);
        long started = System.nanoTime();
        String[] predicates = {
                "http://so-ups.ru/2015/schema-cim16#Object.OwnersToBottom",
                "http://so-ups.ru/2015/schema-cim16#Object.OwnersToTop",
                "http://so-ups.ru/2015/schema-cim16#Object.DataSourceToBottom",
                "http://so-ups.ru/2015/schema-cim16#Object.DataSourceToTop"
        };
        String values = java.util.Arrays.stream(predicates).map(value -> "<" + value + ">")
                .collect(java.util.stream.Collectors.joining(" "));
        String query = "SELECT DISTINCT ?predicate ?owner WHERE { <" + uri + "> ?predicate ?owner " +
                "VALUES ?predicate { " + values + " } }";
        List<Map<String, String>> rows = cimApi.select(query, 1000, true);
        LinkedHashMap<String, OwnerResourceDto> assetOwners = new LinkedHashMap<>();
        LinkedHashMap<String, OwnerResourceDto> dataSources = new LinkedHashMap<>();
        List<OwnerRuleDto> assetOwnerRules = new ArrayList<>();
        List<OwnerRuleDto> dataSourceRules = new ArrayList<>();
        for (Map<String, String> row : rows) {
            String owner = row.get("owner");
            if (owner == null) continue;
            String expanded = owner.startsWith("http") ? owner : owner;
            try {
                GraphNodeDto node = graphService.node(expanded);
                OwnerResourceDto result = new OwnerResourceDto(node.uri(), node.compactUri(), node.label(), node.types());
                roleResources(predicate(row), assetOwners, dataSources).putIfAbsent(node.uri(), result);
            } catch (RuntimeException ignored) {
                OwnerResourceDto result = new OwnerResourceDto(expanded, expanded, expanded, java.util.List.of());
                roleResources(predicate(row), assetOwners, dataSources).putIfAbsent(expanded, result);
            }
            String predicate = predicate(row);
            OwnerRuleDto rule = new OwnerRuleDto(predicate, "owner = " + owner,
                    java.util.List.of("CIM App useOwnerRules=true"));
            (isDataSource(predicate) ? dataSourceRules : assetOwnerRules).add(rule);
        }
        GraphNodeDto resourceNode = graphService.node(uri);
        OwnerResourceDto resource = new OwnerResourceDto(resourceNode.uri(), resourceNode.compactUri(), resourceNode.label(), resourceNode.types());
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        LinkedHashMap<String, OwnerResourceDto> allResources = new LinkedHashMap<>();
        allResources.putAll(assetOwners);
        dataSources.forEach(allResources::putIfAbsent);
        List<OwnerRuleDto> allRules = new ArrayList<>(assetOwnerRules);
        allRules.addAll(dataSourceRules);
        boolean empty = assetOwners.isEmpty() && dataSources.isEmpty();
        return new OwnerRulesResponse(resource,
                java.util.List.copyOf(assetOwners.values()), java.util.List.copyOf(dataSources.values()),
                java.util.List.copyOf(assetOwnerRules), java.util.List.copyOf(dataSourceRules),
                java.util.List.of(), java.util.List.of(),
                java.util.List.copyOf(allResources.values()), java.util.List.copyOf(allRules), java.util.List.of(), elapsed,
                empty ? "Asset Owner и Data Source не определены CIM App Owner Rules." : null);
    }

    private static String predicate(java.util.Map<String, String> row) {
        return row.getOrDefault("predicate", "Owner rule");
    }

    private static boolean isDataSource(String predicate) {
        return predicate.contains("DataSourceTo");
    }

    private static java.util.LinkedHashMap<String, OwnerResourceDto> roleResources(
            String predicate,
            java.util.LinkedHashMap<String, OwnerResourceDto> assetOwners,
            java.util.LinkedHashMap<String, OwnerResourceDto> dataSources) {
        return isDataSource(predicate) ? dataSources : assetOwners;
    }
}
