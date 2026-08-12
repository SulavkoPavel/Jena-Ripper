package org.jenaripper;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.jenaripper.service.PrefixService;
import org.jenaripper.service.SparqlQueryException;
import org.jenaripper.service.SparqlQueryService;

import static org.assertj.core.api.Assertions.assertThat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import com.fasterxml.jackson.databind.JsonNode;

@SpringBootTest(properties = {
        "jena-ripper.dataset.type=memory",
        "jena-ripper.dataset.path=unused",
        "jena-ripper.api.allowed-origins=http://localhost:[*]",
        "jena-ripper.graph.max-neighbors=100",
        "jena-ripper.api.search-limit=25",
        "jena-ripper.sparql.timeout=5s",
        "jena-ripper.sparql.max-select-rows=2",
        "jena-ripper.sparql.max-graph-triples=2",
        "jena-ripper.owner-rules.enabled=false",
        "jena-ripper.redis-rules.enabled=false",
        "jena-ripper.settings.overrides-enabled=false",
        "jena-ripper.settings.path=target/test-connection-settings.json",
        "jena-ripper.user-data.path=target/test-user-data.json"
})
@AutoConfigureMockMvc
class ApiIntegrationTest {
    private static final String UUID = "6de77a5d-fa11-41cc-a0f3-8697fb705da6";
    private static final String ALPHA = "https://cim.so-ups.ru#_" + UUID;
    private static final String BETA = "https://example.org/beta";
    private static final String CIM = "http://iec.ch/TC57/CIM100#";

    @Autowired Dataset dataset;
    @Autowired MockMvc mvc;
    @Autowired PrefixService prefixService;
    @Autowired SparqlQueryService sparqlQueryService;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void loadGraph() {
        dataset.begin(ReadWrite.WRITE);
        try {
            Model model = dataset.getDefaultModel();
            model.removeAll();
            Resource alpha = model.createResource(ALPHA);
            Resource beta = model.createResource(BETA);
            alpha.addProperty(RDFS.label, "Fallback label")
                    .addProperty(ResourceFactory.createProperty(CIM + "IdentifiedObject.name"), "ПП 220 кВ Зея")
                    .addProperty(ResourceFactory.createProperty(CIM + "IdentifiedObject.mRID"), UUID)
                    .addProperty(RDF.type, model.createResource(CIM + "Substation"))
                    .addProperty(ResourceFactory.createProperty("https://example.org/connectedTo"), beta);
            beta.addProperty(RDFS.label, "Beta resource");
            dataset.commit();
        } finally {
            dataset.end();
        }
    }

    @AfterEach
    void removeSavedConnectionSettings() throws Exception {
        java.nio.file.Files.deleteIfExists(java.nio.file.Path.of("target/test-connection-settings.json"));
        java.nio.file.Files.deleteIfExists(java.nio.file.Path.of("target/test-connection-settings.json.tmp"));
        java.nio.file.Files.deleteIfExists(java.nio.file.Path.of("target/test-user-data.json"));
        java.nio.file.Files.deleteIfExists(java.nio.file.Path.of("target/test-user-data.json.tmp"));
    }

    @Test
    void returnsDatasetNodeNeighborhoodAndSearch() throws Exception {
        mvc.perform(get("/api/dataset")).andExpect(status().isOk()).andExpect(jsonPath("$.statementCount").value(6));
        mvc.perform(get("/api/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.dataset.available").value(true))
                .andExpect(jsonPath("$.features.sparql").value(true))
                .andExpect(jsonPath("$.features.ownerRules").value(false));
        mvc.perform(get("/api/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.features.redisRules").value(false));
        mvc.perform(get("/api/nodes").param("uri", "ups:_" + UUID)).andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("ПП 220 кВ Зея"))
                .andExpect(jsonPath("$.compactUri").value("ups:_" + UUID))
                .andExpect(jsonPath("$.types[0]").value("cim:Substation"));
        mvc.perform(get("/api/nodes/neighbors").param("uri", UUID)).andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes.length()").value(2))
                .andExpect(jsonPath("$.stats.outgoingTotal").value(1));
        mvc.perform(get("/api/nodes/details").param("uri", ALPHA)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mrid").value(UUID))
                .andExpect(jsonPath("$.outgoingCount").value(1));
        mvc.perform(get("/api/search").param("q", "ПП 220 кВ Зея")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].uri").value(ALPHA));
        mvc.perform(get("/api/search").param("q", "cim:Substation")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].types[0]").value("cim:Substation"));
    }

    @Test
    void doesNotExposeDesktopShutdownInServerMode() throws Exception {
        mvc.perform(get("/api/application/runtime")).andExpect(status().isNotFound());
    }

    @Test
    void persistsUserDataCollections() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/user-data/sparql/templates")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("[{\"id\":\"template-1\",\"name\":\"My query\",\"query\":\"SELECT * {}\"}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sparqlTemplates[0].id").value("template-1"));
        mvc.perform(get("/api/user-data"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.sparqlTemplates[0].query").value("SELECT * {}"));
    }

    @Test
    void compactsAndExpandsUsingLongestMatchingNamespace() {
        assertThat(prefixService.compact(CIM + "Substation")).isEqualTo("cim:Substation");
        assertThat(prefixService.expand("ups:_" + UUID)).isEqualTo(ALPHA);
        assertThat(prefixService.findPrefix(ALPHA)).contains("ups");
    }

    @Test
    void rejectsNonAbsoluteResourceUri() throws Exception {
        mvc.perform(get("/api/nodes").param("uri", "not-a-uri")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/nodes/owner-rules").param("uri", "not-a-uri"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("OWNER_RULES_UNAVAILABLE"));
    }

    @Test
    void executesReadOnlySparqlQueriesAndReturnsFrontendDtos() throws Exception {
        sparql("SELECT ?s ?name WHERE { ?s <" + CIM + "IdentifiedObject.name> ?name }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("SELECT"))
                .andExpect(jsonPath("$.variables[0]").value("s"))
                .andExpect(jsonPath("$.rows[0].s.type").value("uri"))
                .andExpect(jsonPath("$.rows[0].s.displayValue").value("ups:_" + UUID));

        sparql("ASK { <" + ALPHA + "> a <" + CIM + "Substation> }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("ASK"))
                .andExpect(jsonPath("$.value").value(true));

        sparql("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("CONSTRUCT"))
                .andExpect(jsonPath("$.tripleCount").value(2))
                .andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.statements.length()").value(2))
                .andExpect(jsonPath("$.nodes").isArray());

        mvc.perform(get("/api/sparql/prefixes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cim").value(CIM));
    }

    @Test
    void rejectsSparqlUpdatesAndReportsParseLocation() throws Exception {
        sparql("DELETE WHERE { ?s ?p ?o }")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("SPARQL_UPDATE_DISABLED"));

        sparql("SELEC ?s WHERE { ?s ?p ?o }")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("SPARQL_PARSE_ERROR"))
                .andExpect(jsonPath("$.error.line").isNumber())
                .andExpect(jsonPath("$.error.column").isNumber());
    }

    @Test
    void returnsMetricsStaticAnalysisAlgebraAndBenchmark() throws Exception {
        String query = "SELECT * WHERE { ?s ?p ?o . ?x a ?class } ORDER BY ?s";
        sparql(query)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics.status").value("SUCCESS"))
                .andExpect(jsonPath("$.metrics.resultCount").isNumber())
                .andExpect(jsonPath("$.analysis.selectStar").value(true))
                .andExpect(jsonPath("$.analysis.recommendations[?(@.code == 'MISSING_LIMIT')]").exists())
                .andExpect(jsonPath("$.analysis.recommendations[?(@.code == 'CARTESIAN_PRODUCT')]").exists())
                .andExpect(jsonPath("$.algebra.original").isNotEmpty())
                .andExpect(jsonPath("$.algebra.optimized").isNotEmpty());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/sparql/analyze")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("query", "SELECT ?s WHERE { ?s ?p ?o }"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("SELECT"))
                .andExpect(jsonPath("$.analysis.triplePatterns").value(1));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/sparql/benchmark")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "query", "ASK { ?s ?p ?o }", "warmup", 1, "runs", 2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warmupTimesMs.length()").value(1))
                .andExpect(jsonPath("$.runTimesMs.length()").value(2))
                .andExpect(jsonPath("$.type").value("ASK"));
    }

    @Test
    void acceptsCancellableRequestIdAndReportsAlreadyFinishedQuery() throws Exception {
        String requestId = java.util.UUID.randomUUID().toString();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/sparql/query")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "query", "SELECT ?s WHERE { ?s ?p ?o } LIMIT 1",
                                "requestId", requestId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("SELECT"));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/sparql/query/{requestId}", requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelled").value(false));
    }

    @Test
    void abortsAnActiveJenaQuery() throws Exception {
        String requestId = java.util.UUID.randomUUID().toString();
        String values = java.util.stream.IntStream.range(0, 500)
                .mapToObj(Integer::toString)
                .collect(java.util.stream.Collectors.joining(" "));
        String query = "SELECT (COUNT(*) AS ?count) WHERE { "
                + "VALUES ?a { " + values + " } "
                + "VALUES ?b { " + values + " } "
                + "VALUES ?c { " + values + " } }";

        CompletableFuture<Throwable> future = CompletableFuture.supplyAsync(() -> {
            try {
                sparqlQueryService.execute(query, requestId);
                return null;
            } catch (Throwable error) {
                return error;
            }
        });

        boolean cancelled = false;
        for (int attempt = 0; attempt < 50 && !cancelled; attempt++) {
            Thread.sleep(10);
            cancelled = sparqlQueryService.cancel(requestId);
        }

        assertThat(cancelled).isTrue();
        Throwable error = future.get(3, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(error).isInstanceOf(SparqlQueryException.class);
        assertThat(((SparqlQueryException) error).type()).isEqualTo("SPARQL_CANCELLED");
    }

    @Test
    void allowsBrowserCorsPreflightForQueryCancellation() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options(
                        "/api/sparql/query/{requestId}", java.util.UUID.randomUUID())
                        .header("Origin", "http://localhost:5177")
                        .header("Access-Control-Request-Method", "DELETE"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("DELETE")));
    }

    @Test
    void allowsBrowserCorsPreflightForConnectionSettingsSave() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options(
                        "/api/settings/connections")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "PUT"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("PUT")));
    }

    @Test
    void exposesAndPersistsSafeConnectionSettingsWithoutReturningPasswords() throws Exception {
        mvc.perform(get("/api/settings/connections"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jena.type").value("MEMORY"))
                .andExpect(jsonPath("$.postgres.host").value("localhost"))
                .andExpect(jsonPath("$.postgres.password").doesNotExist())
                .andExpect(jsonPath("$.postgres.passwordConfigured").isBoolean())
                .andExpect(jsonPath("$.redis.password").doesNotExist());

        Map<String, Object> payload = java.util.Map.of(
                "jena", java.util.Map.of("type", "TDB2", "path", "C:/data/jena"),
                "postgres", java.util.Map.of(
                        "host", "localhost", "port", 5432, "database", "db_cim", "schema", "public",
                        "username", "postgres", "password", ""),
                "redis", java.util.Map.of(
                        "host", "localhost", "port", 6379, "database", 0, "username", "",
                        "password", "", "timeoutMs", 5000));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/settings/connections")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.restartRequired").value(true));

        String stored = java.nio.file.Files.readString(java.nio.file.Path.of("target/test-connection-settings.json"));
        assertThat(stored).contains("C:/data/jena");
        mvc.perform(get("/api/settings/connections"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jena.path").value("C:/data/jena"))
                .andExpect(jsonPath("$.postgres.password").doesNotExist())
                .andExpect(jsonPath("$.redis.password").doesNotExist());
    }

    @Test
    void managesConnectionProfilesWithoutExposingSecrets() throws Exception {
        mvc.perform(get("/api/settings/connections/profiles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeProfileId").value("default"))
                .andExpect(jsonPath("$.profiles[0].name").value("Текущее подключение"))
                .andExpect(jsonPath("$.profiles[0].postgres.password").doesNotExist());

        String createBody = objectMapper.writeValueAsString(java.util.Map.of(
                "name", "SIM2 Test", "copyCurrent", true, "sourceProfileId", "default"));
        String createdJson = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/settings/connections/profiles")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profiles.length()").value(2))
                .andExpect(jsonPath("$.profiles[1].postgres.password").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String profileId = objectMapper.readTree(createdJson).path("profiles").get(1).path("id").asText();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/settings/connections/profiles/{id}/name", profileId)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"SIM2 Renamed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profiles[1].name").value("SIM2 Renamed"));

        String duplicateBody = objectMapper.writeValueAsString(java.util.Map.of(
                "name", "SIM2 Renamed", "copyCurrent", true, "sourceProfileId", "default"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/settings/connections/profiles")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(duplicateBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Профиль с таким названием уже существует."));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/api/settings/connections/profiles/{id}", "default"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("активный профиль")));
    }

    @Test
    void persistsSelectedCimInformationModel() throws Exception {
        Map<String, Object> cim = java.util.Map.of(
                "baseUrl", "https://cim.example.test",
                "authBaseUrl", "https://auth.example.test",
                "username", "client",
                "password", "secret",
                "modelId", 77,
                "modelName", "Selected model",
                "connectTimeoutMs", 5000,
                "readTimeoutMs", 30000,
                "trustUntrustedCertificates", true);
        Map<String, Object> payload = java.util.Map.of(
                "jena", java.util.Map.of("sourceType", "CIM_API", "type", "TDB2", "path", "", "cimApi", cim),
                "redis", java.util.Map.of("host", "localhost", "port", 6379, "database", 0,
                        "username", "", "password", "", "timeoutMs", 5000));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/settings/connections")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk());

        mvc.perform(get("/api/settings/connections"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jena.cimApi.modelId").value(77))
                .andExpect(jsonPath("$.jena.cimApi.modelName").value("Selected model"))
                .andExpect(jsonPath("$.jena.cimApi.trustUntrustedCertificates").value(true));
        JsonNode stored = objectMapper.readTree(java.nio.file.Path.of("target/test-connection-settings.json").toFile());
        assertThat(stored.path("profiles").get(0).path("jena").path("cimApi").path("modelId").asLong()).isEqualTo(77L);
    }

    @Test
    void migratesLegacySettingsIntoDefaultProfile() throws Exception {
        Map<String, Object> legacy = java.util.Map.of(
                "jena", java.util.Map.of("type", "tdb2", "path", "D:/legacy-dataset"),
                "postgres", java.util.Map.of("host", "legacy-pg", "port", 5432, "database", "cim",
                        "schema", "public", "username", "reader", "password", "secret"),
                "redis", java.util.Map.of("host", "legacy-redis", "port", 6379, "database", 2,
                        "username", "", "password", "redis-secret", "timeoutMs", 3000));
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/test-connection-settings.json"),
                objectMapper.writeValueAsString(legacy));

        mvc.perform(get("/api/settings/connections/profiles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profiles[0].jena.path").value("D:/legacy-dataset"))
                .andExpect(jsonPath("$.profiles[0].postgres.passwordConfigured").value(true))
                .andExpect(jsonPath("$.profiles[0].postgres.password").doesNotExist());
        JsonNode migrated = objectMapper.readTree(java.nio.file.Path.of("target/test-connection-settings.json").toFile());
        assertThat(migrated.has("activeProfileId")).isTrue();
        assertThat(migrated.has("profiles")).isTrue();
        assertThat(migrated.has("jena")).isFalse();
    }

    @Test
    void validatesConnectionTestsWithoutLeakingTechnicalExceptions() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/settings/connections/test-redis")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"host\":\"\",\"port\":0,\"database\":-1,\"timeoutMs\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Redis")));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/settings/connections/test-jena")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"TDB2\",\"path\":\"Z:/definitely/missing/dataset\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Указанная папка Dataset не существует."));
    }

    private org.springframework.test.web.servlet.ResultActions sparql(String query) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/sparql/query")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of("query", query))));
    }
}
