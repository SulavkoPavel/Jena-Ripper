package org.jenaripper.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;

@ConfigurationProperties(prefix = "jena-ripper.rdf-source")
public record RdfSourceProperties(String type, String profileId, CimApi cimApi) {
    public static final String LOCAL_TDB2 = "LOCAL_TDB2";
    public static final String CIM_API = "CIM_API";

    public boolean remote() {
        return CIM_API.equalsIgnoreCase(type);
    }

    public RdfSourceProperties(String type, CimApi cimApi) {
        this(type, null, cimApi);
    }

    @ConstructorBinding
    public RdfSourceProperties {
    }

    public record CimApi(
            String baseUrl,
            String authBaseUrl,
            String username,
            String password,
            Long modelId,
            String modelName,
            Duration connectTimeout,
            Duration readTimeout,
            boolean trustUntrustedCertificates) {}
}
