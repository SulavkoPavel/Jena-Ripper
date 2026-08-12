package org.jenaripper.dto;

public record ConnectionSettingsDto(
        JenaSettings jena,
        PostgresSettings postgres,
        RedisSettings redis) {

    public record JenaSettings(String sourceType, String type, String path, CimApiSettings cimApi) {
        public JenaSettings(String type, String path) { this("LOCAL_TDB2", type, path, null); }
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
