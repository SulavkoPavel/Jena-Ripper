package org.jenaripper.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.riot.system.StreamRDFLib;
import org.jenaripper.config.JenaRipperProperties;
import org.jenaripper.dto.UploadedModelDto;
import org.jenaripper.exception.ModelInUseException;
import org.jenaripper.exception.UploadedModelException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadedModelServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void importsPersistsAndDeletesFullModel() {
        UploadedModelService service = service();
        String rdf = "<?xml version=\"1.0\"?><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + "<rdf:Description rdf:about=\"urn:test:subject\"><value xmlns=\"urn:test:\">ok</value>"
                + "</rdf:Description></rdf:RDF>";

        UploadedModelDto uploaded = service.upload(new MockMultipartFile(
                "file", "FullModel.xml", "application/rdf+xml", rdf.getBytes(StandardCharsets.UTF_8)));

        assertThat(uploaded.status()).isEqualTo(UploadedModelService.READY);
        assertThat(uploaded.type()).isEqualTo(UploadedModelService.FULL);
        assertThat(uploaded.name()).isEqualTo("FullModel");
        assertThat(uploaded.originalFileName()).isEqualTo("FullModel.xml");
        UploadedModelDto renamed = service.rename(uploaded.id(), "  Основная модель  ");
        assertThat(renamed.name()).isEqualTo("Основная модель");
        assertThat(renamed.originalFileName()).isEqualTo("FullModel.xml");
        assertThat(service().list()).singleElement().extracting(UploadedModelDto::name)
                .isEqualTo("Основная модель");
        assertThat(service().list()).extracting(UploadedModelDto::id).containsExactly(uploaded.id());
        assertThat(Files.isDirectory(service.datasetPath(uploaded.id()))).isTrue();

        service.delete(uploaded.id());

        assertThat(service.list()).isEmpty();
        assertThat(Files.exists(service.datasetPath(uploaded.id()).getParent())).isFalse();
    }

    @Test
    void keepsErrorStatusWhenRdfCannotBeParsed() {
        UploadedModelService service = service();

        assertThatThrownBy(() -> service.upload(new MockMultipartFile(
                "file", "broken.rdf", "application/rdf+xml", "not rdf".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(UploadedModelException.class);

        assertThat(service.list()).singleElement().satisfies(model -> {
            assertThat(model.status()).isEqualTo(UploadedModelService.ERROR);
            assertThat(model.error()).isNotBlank();
        });
    }

    @Test
    void resolvesRelativeCimResourcesAgainstUpsNamespace() {
        String rdf = "<?xml version=\"1.0\"?><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" "
                + "xmlns:test=\"urn:test:\"><rdf:Description rdf:about=\"#_source\">"
                + "<test:IdentifiedObjects rdf:resource=\"#_target\"/></rdf:Description>"
                + "<rdf:Description rdf:about=\"#_target\"/></rdf:RDF>";
        Model model = ModelFactory.createDefaultModel();
        ByteArrayInputStream input = new ByteArrayInputStream(rdf.getBytes(StandardCharsets.UTF_8));
        UploadedModelService.readRdfXml(input, RDFLanguages.RDFXML, StreamRDFLib.graph(model.getGraph()));
        String sourceUri = "https://cim.so-ups.ru#_source";
        String targetUri = "https://cim.so-ups.ru#_target";
        RDFNode relation = model.getResource(sourceUri)
                .getProperty(model.createProperty("urn:test:IdentifiedObjects"))
                .getObject();

        assertThat(model.containsResource(model.getResource(sourceUri))).isTrue();
        assertThat(new PrefixService().compact(sourceUri)).isEqualTo("ups:_source");
        assertThat(relation.isResource()).isTrue();
        assertThat(relation.asResource().getURI()).isEqualTo(targetUri);
        assertThat(model.listSubjects().toList()).noneMatch(resource ->
                resource.isURIResource() && resource.getURI().startsWith("file:"));
    }

    @Test
    void backfillsNamesForExistingIndexEntries() throws Exception {
        Path storage = temporaryDirectory.resolve("uploaded-models");
        Files.createDirectories(storage);
        Files.writeString(storage.resolve("models.json"), """
                [{"id":"12345678-1234-1234-1234-123456789abc","originalFileName":"legacy.xml",
                  "storedFileName":"source.xml","size":12,"uploadedAt":"2026-09-08T00:00:00Z",
                  "status":"READY","type":"FULL","error":null}]
                """);

        assertThat(service().list()).singleElement().extracting(UploadedModelDto::name).isEqualTo("legacy");
        assertThat(Files.readString(storage.resolve("models.json"))).contains("\"name\" : \"legacy\"");
    }

    @Test
    void validatesRenamedModelName() {
        UploadedModelService service = service();

        assertThatThrownBy(() -> service.rename("12345678-1234-1234-1234-123456789abc", "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не может быть пустым");
        assertThatThrownBy(() -> service.rename("12345678-1234-1234-1234-123456789abc", "x".repeat(256)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("255");
    }

    @Test
    void blocksReferencedModelAndDeletesItAfterProfileSwitchesToLocalTdb2() throws Exception {
        UploadedModelService service = service();
        String rdf = "<?xml version=\"1.0\"?><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"/>";
        UploadedModelDto uploaded = service.upload(new MockMultipartFile(
                "file", "UsedModel.xml", "application/rdf+xml", rdf.getBytes(StandardCharsets.UTF_8)));
        Path settings = temporaryDirectory.resolve("settings.json");
        Files.writeString(settings, "{\"activeProfileId\":\"12\",\"profiles\":[{\"id\":\"12\",\"name\":\"Local TDB2\","
                + "\"jena\":{\"sourceType\":\"FILE\",\"uploadedModelId\":\"" + uploaded.id() + "\"}}]}");

        assertThatThrownBy(() -> service.delete(uploaded.id()))
                .isInstanceOfSatisfying(ModelInUseException.class, exception -> {
                    assertThat(exception.profiles()).singleElement().satisfies(profile -> {
                        assertThat(profile.id()).isEqualTo("12");
                        assertThat(profile.name()).isEqualTo("Local TDB2");
                    });
                });
        assertThat(service.list()).extracting(UploadedModelDto::id).contains(uploaded.id());
        assertThat(Files.exists(service.datasetPath(uploaded.id()).getParent())).isTrue();

        Files.writeString(settings, "{\"activeProfileId\":\"12\",\"profiles\":[{\"id\":\"12\",\"name\":\"Local TDB2\","
                + "\"jena\":{\"sourceType\":\"LOCAL_TDB2\",\"uploadedModelId\":null}}]}");
        service.delete(uploaded.id());

        assertThat(service.list()).isEmpty();
        assertThat(Files.exists(service.datasetPath(uploaded.id()).getParent())).isFalse();
    }

    private UploadedModelService service() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        JenaRipperProperties properties = new JenaRipperProperties(null, null, null, null, null, null, null,
                new JenaRipperProperties.Settings(temporaryDirectory.resolve("settings.json").toString()));
        return new UploadedModelService(objectMapper, properties);
    }
}
