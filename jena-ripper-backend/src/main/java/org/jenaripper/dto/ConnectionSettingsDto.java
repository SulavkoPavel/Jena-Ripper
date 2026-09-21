package org.jenaripper.dto;

import org.jenaripper.config.RdfSourceProperties;

public record ConnectionSettingsDto(
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

    public record CimApiSettings(String baseUrl, String authBaseUrl, String username, Long modelId, String modelName,
                                 long connectTimeoutMs, long readTimeoutMs, boolean passwordConfigured,
                                 boolean trustUntrustedCertificates) {}

    public record PostgresSettings(
            String host,
            int port,
            String database,
            String schema,
            String username,
            boolean passwordConfigured) {}

    public record RedisSettings(
            String host,
            int port,
            int database,
            String username,
            long timeoutMs,
            boolean passwordConfigured) {}
}
