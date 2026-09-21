package org.jenaripper.service;

import org.apache.jena.graph.Node;
import org.apache.jena.sparql.core.Quad;
import lombok.RequiredArgsConstructor;
import org.jenaripper.config.JenaRipperProperties;
import org.jenaripper.config.RdfSourceProperties;
import org.jenaripper.config.DatasetRuntimeState;
import org.jenaripper.remote.CimApiClient;
import org.jenaripper.dto.DatasetInfoDto;
import org.jenaripper.dto.DatasetStatusDto;
import org.jenaripper.jena.JenaReadExecutor;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class
DatasetInfoService {
    private final JenaReadExecutor executor;
    private final JenaRipperProperties properties;
    private final RdfSourceProperties source;
    private final CimApiClient cimApi;
    private final DatasetRuntimeState runtimeState;

    public DatasetInfoDto info() {
        if (source.remote()) {
            long count = 0;
            try {
                List<Map<String, String>> rows = cimApi.select("SELECT (COUNT(*) AS ?count) WHERE { ?s ?p ?o }", 1, false);
                if (!rows.isEmpty()) count = Long.parseLong(rows.get(0).getOrDefault("count", "0"));
            } catch (NumberFormatException ignored) { }
            return new DatasetInfoDto(RdfSourceProperties.CIM_API, source.cimApi().modelName(), count,
                    java.util.List.of());
        }
        return executor.read(() -> {
            long count = 0;
            Set<String> names = new LinkedHashSet<>();
            Iterator<Quad> iterator = executor.dataset().asDatasetGraph().find();
            while (iterator.hasNext()) {
                Quad quad = iterator.next();
                count++;
                Node graph = quad.getGraph();
                if (!quad.isDefaultGraph() && graph.isURI()) names.add(graph.getURI());
            }
            JenaRipperProperties.Dataset config = properties.dataset();
            String path = "tdb2".equalsIgnoreCase(config.type())
                    ? Path.of(config.path()).toAbsolutePath().normalize().toString() : null;
            String type = RdfSourceProperties.FILE.equalsIgnoreCase(source.type()) ? RdfSourceProperties.FILE : config.type();
            return new DatasetInfoDto(type, path, count, names.stream().toList());
        });
    }

    public DatasetStatusDto status() {
        if (source.remote()) {
            boolean available;
            try { cimApi.verify(source.cimApi().modelId()); available = true; }
            catch (RuntimeException exception) { available = false; }
            String label = source.cimApi().modelName() == null || source.cimApi().modelName().isBlank()
                    ? source.cimApi().baseUrl() : source.cimApi().modelName();
            JenaRipperProperties.RedisRules redis = properties.redisRules();
            boolean redisAvailable = redis != null && redis.enabled()
                    && redis.host() != null && !redis.host().isBlank() && redis.port() > 0;
            return new DatasetStatusDto(available ? "UP" : "DOWN",
                    new DatasetStatusDto.DatasetStateDto(RdfSourceProperties.CIM_API, label, available),
                    new DatasetStatusDto.FeaturesDto(true, true, redisAvailable, true, true,
                            RdfSourceProperties.CIM_API,
                            redisAvailable, source.profileId()));
        }
        JenaRipperProperties.Dataset config = properties.dataset();
        String path = "tdb2".equalsIgnoreCase(config.type())
                ? Path.of(config.path()).toAbsolutePath().normalize().toString() : null;
        boolean available;
        try {
            available = !runtimeState.fallback() && executor.read(() -> executor.dataset().isInTransaction());
        } catch (RuntimeException exception) {
            available = false;
        }
        return new DatasetStatusDto(
                available ? "UP" : "DOWN",
                new DatasetStatusDto.DatasetStateDto(
                        RdfSourceProperties.FILE.equalsIgnoreCase(source.type()) ? RdfSourceProperties.FILE : config.type().toUpperCase(),
                        path, available, runtimeState.error()),
                new DatasetStatusDto.FeaturesDto(true,
                        properties.ownerRules() != null && properties.ownerRules().enabled(),
                        properties.redisRules() != null && properties.redisRules().enabled(),
                        true,
                        properties.ownerRules() != null && properties.ownerRules().enabled(),
                        RdfSourceProperties.FILE.equalsIgnoreCase(source.type()) ? RdfSourceProperties.FILE : RdfSourceProperties.LOCAL_TDB2,
                        properties.redisRules() != null && properties.redisRules().enabled(),
                        source.profileId()));
    }
}
