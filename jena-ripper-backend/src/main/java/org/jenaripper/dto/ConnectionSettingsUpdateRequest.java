package org.jenaripper.dto;

import org.jenaripper.config.RdfSourceProperties;

public record ConnectionSettingsUpdateRequest(
        JenaSettings jena,
        PostgresSettings postgres,
        RedisSettings redis) {

    public record JenaSettings(String sourceType, String type, String path, CimApiSettings cimApi, String uploadedModelId) {
        public JenaSettings(String type, String path) {
            this(RdfSourceProperties.LOCAL_TDB2, type, path, null, null);
        }
        public JenaSettings(String sourceType, String type, String path, CimApiSettings cimApi) {
            this(sourceType, type, path, cimApi, null);
        }
    }

    public record CimApiSettings(String baseUrl, String authBaseUrl, String username, String password, Long modelId, String modelName,
                                 Long connectTimeoutMs, Long readTimeoutMs, Boolean trustUntrustedCertificates) {}

    public record PostgresSettings(
            String host,
            Integer port,
            String database,
            String schema,
            String username,
            String password) {}

    public record RedisSettings(
            String host,
            Integer port,
            Integer database,
            String username,
            String password,
            Long timeoutMs) {}
}
