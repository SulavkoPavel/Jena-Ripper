package org.jenaripper.dto;

import org.jenaripper.config.RdfSourceProperties;

public record DatasetStatusDto(String status, DatasetStateDto dataset, FeaturesDto features) {
    public record DatasetStateDto(String type, String path, boolean available, String error) {
        public DatasetStateDto(String type, String path, boolean available) { this(type, path, available, null); }
    }
    public record FeaturesDto(boolean sparql, boolean ownerRules, boolean redisRules,
                              boolean graph, boolean sparqlOwnerRules, String sourceType, boolean redisConsole,
                              String activeProfileId) {
        public FeaturesDto(boolean sparql, boolean ownerRules, boolean redisRules) {
            this(sparql, ownerRules, redisRules, true, ownerRules,
                    RdfSourceProperties.LOCAL_TDB2, redisRules, null);
        }

        public FeaturesDto(boolean sparql, boolean ownerRules, boolean redisRules,
                           boolean graph, boolean sparqlOwnerRules, String sourceType, boolean redisConsole) {
            this(sparql, ownerRules, redisRules, graph, sparqlOwnerRules, sourceType, redisConsole, null);
        }
    }
}
