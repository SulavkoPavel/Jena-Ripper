package org.jenaripper.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.time.Duration;

@ConfigurationProperties(prefix = "jena-ripper")
public record JenaRipperProperties(Dataset dataset, Graph graph, Labels labels, Api api, Sparql sparql, OwnerRules ownerRules, RedisRules redisRules, Settings settings) {
    public record Dataset(String type, String path) {}
    public record Graph(int maxNeighbors) {}
    public record Labels(List<String> predicates) {}
    public record Api(List<String> allowedOrigins, int searchLimit) {}
    public record Sparql(Duration timeout, int maxSelectRows, int maxGraphTriples, List<Long> speedThresholdsMs) {}
    public record OwnerRules(boolean enabled, String jdbcUrl, String username, String password, String schema,
                             Duration connectTimeout, int poolSize) {}
    public record RedisRules(
            boolean enabled,
            String host,
            int port,
            String username,
            String password,
            int database,
            Duration timeout,
            long datasetId,
            String modelType,
            Long diffId,
            List<Long> additionalDatasetIds) {}
    public record Settings(String path) {}
}
