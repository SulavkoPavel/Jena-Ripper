package org.jenaripper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jenaripper.remote.CimApiClient;
import org.jenaripper.exception.CimApiException;
import org.jenaripper.settings.StoredConnectionSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CimApiClientTest {
    private HttpServer server;
    private HttpServer authServer;

    @AfterEach void stop() {
        if (server != null) server.stop(0);
        if (authServer != null) authServer.stop(0);
    }

    @Test void enablesUntrustedCertificateModeByDefaultForNewSettings() {
        StoredConnectionSettings.CimApi settings = new StoredConnectionSettings.CimApi("https://cim.example.test", "client", "secret",
                42L, "SIM2", 1000, 1000);
        assertThat(settings.trustUntrustedCertificates()).isTrue();
    }

    @Test void authenticatesLoadsModelsAndExecutesSelectWithOwnerRules() throws Exception {
        AtomicReference<String> selectBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/oauth/token", exchange -> respond(exchange, 200,
                "{\"accessToken\":\"access\",\"expiresIn\":900,\"refreshToken\":\"refresh\"}"));
        server.createContext("/api/info-models", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer access");
            respond(exchange, 200, "{\"content\":[{\"id\":42,\"name\":\"SIM2\"}]}");
        });
        server.createContext("/api/query/select", exchange -> {
            selectBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertThat(exchange.getRequestURI().getQuery()).isEqualTo("modelId=42");
            respond(exchange, 200, "[{\"s\":\"ups:_1\"}]");
        });
        server.start();

        CimApiClient client = client("secret", 42L);
        assertThat(client.models()).singleElement().satisfies(model -> {
            assertThat(model.id()).isEqualTo(42L);
            assertThat(model.name()).isEqualTo("SIM2");
        });
        assertThat(client.select("SELECT ?s WHERE {?s ?p ?o}", 100, true)).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("s", "ups:_1"));
        assertThat(selectBody.get()).contains("\"useOwnerRules\":true", "\"limit\":100");

        client.select("SELECT ?s WHERE {?s ?p ?o} LIMIT 1", 1, false);
        assertThat(selectBody.get()).contains("\"query\":\"SELECT ?s WHERE {?s ?p ?o} limit 1\"")
                .doesNotContain("\"limit\":");
    }

    @Test void reportsWrongCredentialsAsAuthenticationError() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/oauth/token", exchange -> respond(exchange, 401, "{}"));
        server.start();
        assertThatThrownBy(() -> client("wrong", null).models())
                .isInstanceOfSatisfying(CimApiException.class,
                        error -> assertThat(error.type()).isEqualTo("CIM_API_AUTH_FAILED"));
    }

    @Test void loadsMetamodelWithEmbeddedAssociations() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/oauth/token", exchange -> respond(exchange, 200,
                "{\"accessToken\":\"access\",\"expiresIn\":900}"));
        server.createContext("/api/core/metamodel/metamodels", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer access");
            assertThat(exchange.getRequestURI().getQuery()).isEqualTo("simple=false");
            respond(exchange, 200, "[{\"id\":\"cim:PowerTransformer\",\"label\":\"Transformer\"," +
                    "\"parents\":[],\"children\":[],\"associations\":[{" +
                    "\"id\":42,\"name\":\"cim:PowerTransformer.End\"," +
                    "\"range\":\"cim:PowerTransformerEnd\",\"ranges\":[\"cim:PowerTransformerEnd\"]," +
                    "\"rangesNeed\":[{\"range\":\"cim:PowerTransformerEnd\",\"need\":1}]}]}]");
        });
        server.start();

        assertThat(client("secret", 42L).metamodelClasses()).singleElement().satisfies(metamodelClass -> {
            assertThat(metamodelClass.id()).isEqualTo("cim:PowerTransformer");
            assertThat(metamodelClass.associations()).singleElement().satisfies(association -> {
                assertThat(association.id()).isEqualTo(42L);
                assertThat(association.ranges()).containsExactly("cim:PowerTransformerEnd");
                assertThat(association.rangesNeed()).singleElement()
                        .satisfies(need -> assertThat(need.need()).isEqualTo(1L));
            });
        });
    }

    @Test void loadsMetamodelThroughBusinessGatewayWhenAuthUrlDiffers() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/core/metamodel/metamodels", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer access");
            assertThat(exchange.getRequestURI().getQuery()).isEqualTo("simple=false");
            respond(exchange, 200, "[{\"id\":\"cim:Substation\",\"label\":\"Substation\"}]");
        });
        server.createContext("/api/core/metamodel/classes/", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer access");
            assertThat(exchange.getRequestURI().getRawPath()).endsWith("/cim%3ASubstation");
            respond(exchange, 200, "{\"id\":\"cim:Substation\",\"label\":\"Substation\"}");
        });
        server.start();
        authServer = HttpServer.create(new InetSocketAddress(0), 0);
        authServer.createContext("/oauth/token", exchange -> respond(exchange, 200,
                "{\"accessToken\":\"access\",\"expiresIn\":900}"));
        authServer.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        String authBaseUrl = "http://127.0.0.1:" + authServer.getAddress().getPort();
        StoredConnectionSettings.CimApi settings = new StoredConnectionSettings.CimApi(
                baseUrl, authBaseUrl, "client", "secret", 42L, "SIM2", 1000, 1000);

        CimApiClient client = CimApiClient.forSettings(new ObjectMapper(), settings);
        assertThat(client.metamodelClasses())
                .singleElement()
                .satisfies(metamodelClass -> assertThat(metamodelClass.id()).isEqualTo("cim:Substation"));
        assertThat(client.metamodelClass("cim:Substation").id()).isEqualTo("cim:Substation");
    }

    @Test void reportsExactMissingBusinessEndpoint() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/oauth/token", exchange -> respond(exchange, 200,
                "{\"accessToken\":\"access\",\"expiresIn\":900}"));
        server.start();

        assertThatThrownBy(() -> client("secret", 42L).models())
                .isInstanceOfSatisfying(CimApiException.class, error -> {
                    assertThat(error.type()).isEqualTo("CIM_API_DATASET_NOT_FOUND");
                    assertThat(error.getMessage()).contains("HTTP 404", "GET /api/info-models");
                });
    }

    @Test void reportsOfflineApplication() {
        StoredConnectionSettings.CimApi settings = new StoredConnectionSettings.CimApi("http://127.0.0.1:1", "client", "secret",
                null, null, 200, 200);
        assertThatThrownBy(() -> CimApiClient.forSettings(new ObjectMapper(), settings).models())
                .isInstanceOfSatisfying(CimApiException.class,
                        error -> assertThat(error.type()).isEqualTo("CIM_API_UNAVAILABLE"));
    }

    @Test void reusesSessionForRedisApiAndPassesInformationModelContext() throws Exception {
        AtomicReference<String> commandBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/oauth/token", exchange -> respond(exchange, 200,
                "{\"accessToken\":\"access\",\"expiresIn\":900}"));
        server.createContext("/api/redis/inspection/capabilities", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer access");
            assertThat(exchange.getRequestURI().getQuery()).isEqualTo("informationModelId=42");
            respond(exchange, 200, "{\"available\":true,\"datasetId\":2,\"modelType\":\"IM\",\"commands\":[\"TYPE\"]}");
        });
        server.createContext("/api/redis/inspection/command", exchange -> {
            commandBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertThat(exchange.getRequestURI().getQuery()).isEqualTo("informationModelId=42");
            respond(exchange, 200, "{\"command\":\"TYPE\",\"resultType\":\"STRING\",\"value\":\"set\",\"count\":1,\"datasetId\":2,\"modelType\":\"IM\"}");
        });
        server.createContext("/api/redis/inspection/rules", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer access");
            assertThat(exchange.getRequestURI().getQuery())
                    .isEqualTo("resourceUri=ups:_1&informationModelId=42");
            respond(exchange, 200, "{\"resourceId\":\"ups:_1\",\"datasetId\":2,\"modelType\":\"IM\"," +
                    "\"permissions\":{\"read\":[\"ups:_company\"],\"readTop\":[],\"write\":[\"ups:_company\"]}," +
                    "\"technicalKeys\":[{\"key\":\"2/rb:ups:_1\",\"role\":\"READ\",\"type\":\"SET\",\"size\":1}]," +
                    "\"executionTimeMs\":4}");
        });
        server.start();

        CimApiClient client = client("secret", 42L);
        assertThat(client.redisCapabilities().available()).isTrue();
        assertThat(client.redis("TYPE", java.util.List.of("2/rb:ups:_1")).value().asText()).isEqualTo("set");
        assertThat(client.redisRules("ups:_1").permissions().read()).containsExactly("ups:_company");
        assertThat(commandBody.get()).contains("\"command\":\"TYPE\"", "2/rb:ups:_1");
    }

    @Test void mapsCimRedisDomainErrorWithoutExposingResponseInternals() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/oauth/token", exchange -> respond(exchange, 200,
                "{\"accessToken\":\"access\",\"expiresIn\":900}"));
        server.createContext("/api/redis/inspection/command", exchange -> respond(exchange, 400,
                "{\"type\":\"REDIS_COMMAND_FORBIDDEN\",\"message\":\"READ ONLY\"}"));
        server.start();

        assertThatThrownBy(() -> client("secret", 42L).redis("DEL", java.util.List.of("key")))
                .isInstanceOfSatisfying(CimApiException.class, error -> {
                    assertThat(error.type()).isEqualTo("REDIS_COMMAND_FORBIDDEN");
                    assertThat(error.getMessage()).isEqualTo("READ ONLY");
                });
    }

    private CimApiClient client(String password, Long modelId) {
        StoredConnectionSettings.CimApi settings = new StoredConnectionSettings.CimApi("http://127.0.0.1:" + server.getAddress().getPort(),
                "client", password, modelId, "SIM2", 1000, 1000);
        return CimApiClient.forSettings(new ObjectMapper(), settings);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
