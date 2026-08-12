package org.jenaripper.dto;

public record ConnectionSettingsUpdateRequest(
        JenaSettings jena,
        PostgresSettings postgres,
        RedisSettings redis) {

    public record JenaSettings(String sourceType, String type, String path, CimApiSettings cimApi) {
        public JenaSettings(String type, String path) { this("LOCAL_TDB2", type, path, null); }
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
