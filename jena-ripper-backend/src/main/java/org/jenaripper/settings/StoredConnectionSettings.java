package org.jenaripper.settings;

import org.jenaripper.config.RdfSourceProperties;

public record StoredConnectionSettings(Jena jena, Postgres postgres, Redis redis) {
    public record Jena(String sourceType, String type, String path, CimApi cimApi, String uploadedModelId) {
        public Jena(String type, String path) {
            this(RdfSourceProperties.LOCAL_TDB2, type, path, null, null);
        }
        public Jena(String sourceType, String type, String path, CimApi cimApi) {
            this(sourceType, type, path, cimApi, null);
        }
    }
    public record CimApi(String baseUrl, String authBaseUrl, String username, String password, Long modelId, String modelName,
                         long connectTimeoutMs, long readTimeoutMs, boolean trustUntrustedCertificates) {
        public CimApi(String baseUrl, String authBaseUrl, String username, String password, Long modelId, String modelName,
                      long connectTimeoutMs, long readTimeoutMs) {
            this(baseUrl, authBaseUrl, username, password, modelId, modelName, connectTimeoutMs, readTimeoutMs, true);
        }
        public CimApi(String baseUrl, String username, String password, Long modelId, String modelName,
                      long connectTimeoutMs, long readTimeoutMs) {
            this(baseUrl, baseUrl, username, password, modelId, modelName, connectTimeoutMs, readTimeoutMs, true);
        }
    }
    public record Postgres(String host, int port, String database, String schema, String username, String password) {}
    public record Redis(String host, int port, int database, String username, String password, long timeoutMs) {}
}
