package org.jenaripper.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jenaripper.dto.UserDataDto;
import org.jenaripper.settings.UserDataRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class UserDataServiceTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void persistsCollectionsAndLoadsThemAgain() {
        Path path = directory.resolve("jena-ripper-user-data.json");
        UserDataService service = service(path);

        service.update("sparql", "templates", List.of(value("id", "sparql-1")));
        service.update("redis", "history", List.of(value("command", "GET key")));

        UserDataDto reloaded = service(path).current();
        assertThat(reloaded.sparqlTemplates()).extracting(value -> value.get("id").asText())
                .containsExactly("sparql-1");
        assertThat(reloaded.redisHistory()).extracting(value -> value.get("command").asText())
                .containsExactly("GET key");
        assertThat(Files.exists(path.resolveSibling(path.getFileName() + ".tmp"))).isFalse();
    }

    @Test
    void mergesLegacyDataWithoutDuplicatesAndKeepsHistoryLimit() {
        Path path = directory.resolve("jena-ripper-user-data.json");
        UserDataService service = service(path);
        service.update("sparql", "templates", List.of(template("same", "Existing", "SELECT * {}")));
        List<JsonNode> history = java.util.stream.IntStream.range(0, 25)
                .mapToObj(index -> (JsonNode) value("query", "SELECT " + index)).toList();

        UserDataDto migrated = service.migrate(new UserDataDto(1,
                List.of(template("same", "Duplicate", "SELECT * {}"), template("new", "New", "ASK {}")),
                List.copyOf(history), List.of(), List.of()));

        assertThat(migrated.sparqlTemplates()).hasSize(2);
        assertThat(migrated.sparqlTemplates().get(0).get("name").asText()).isEqualTo("Existing");
        assertThat(migrated.sparqlHistory()).hasSize(20);
    }

    @Test
    void serializesParallelCollectionUpdates() throws Exception {
        UserDataService service = service(directory.resolve("jena-ripper-user-data.json"));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            executor.submit(() -> service.update("sparql", "history", List.of(value("query", "SELECT * {}"))));
            executor.submit(() -> service.update("redis", "history", List.of(value("command", "GET key"))));
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        assertThat(service.current().sparqlHistory()).hasSize(1);
        assertThat(service.current().redisHistory()).hasSize(1);
    }

    @Test
    void backsUpCorruptedFileAndStartsWithEmptyCollections() throws Exception {
        Path path = directory.resolve("jena-ripper-user-data.json");
        Files.writeString(path, "{broken");

        UserDataDto data = service(path).current();

        assertThat(data.sparqlTemplates()).isEmpty();
        try (Stream<Path> files = Files.list(directory)) {
            assertThat(files.map(value -> value.getFileName().toString()))
                    .anyMatch(name -> name.startsWith("jena-ripper-user-data.json.corrupt-") && name.endsWith(".bak"));
        }
    }

    private UserDataService service(Path path) {
        return new UserDataService(new UserDataRepository(mapper, path.toString(), "ignored-settings.json"));
    }

    private ObjectNode template(String id, String name, String query) {
        ObjectNode value = mapper.createObjectNode();
        value.put("id", id);
        value.put("name", name);
        value.put("query", query);
        return value;
    }

    private ObjectNode value(String field, String content) {
        ObjectNode value = mapper.createObjectNode();
        value.put(field, content);
        return value;
    }
}
