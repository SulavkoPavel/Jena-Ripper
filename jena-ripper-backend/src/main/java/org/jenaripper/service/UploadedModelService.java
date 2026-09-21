package org.jenaripper.service;

import org.jenaripper.exception.ModelInUseException;
import org.jenaripper.exception.UploadedModelException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParserRegistry;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.riot.ReaderRIOT;
import org.apache.jena.riot.ReaderRIOTFactory;
import org.apache.jena.riot.system.ErrorHandlerFactory;
import org.apache.jena.riot.system.RiotLib;
import org.apache.jena.riot.system.StreamRDF;
import org.apache.jena.riot.system.StreamRDFLib;
import org.apache.jena.sparql.util.Context;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.tdb2.TDB2Factory;
import org.apache.jena.tdb2.sys.TDBInternal;
import org.jenaripper.config.JenaRipperProperties;
import org.jenaripper.config.RdfSourceProperties;
import org.jenaripper.dto.UploadedModelDto;
import org.jenaripper.settings.ConnectionProfilePaths;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class UploadedModelService {
    static final String CIM_RESOURCE_BASE_URI = "https://cim.so-ups.ru#";
    private static final String RDF_EXTENSION = ".rdf";
    private static final String XML_EXTENSION = ".xml";
    private static final String INDEX_FILE_NAME = "models.json";
    private static final String INDEX_TEMPORARY_FILE_NAME = "models.json.tmp";
    private static final int MAX_MODEL_NAME_LENGTH = 255;
    private static final int DELETE_ATTEMPTS = 3;
    public static final String FULL = "FULL";
    public static final String PROCESSING = "PROCESSING";
    public static final String READY = "READY";
    public static final String ERROR = "ERROR";

    private final ObjectMapper objectMapper;
    private final Path settingsPath;
    private final Path storageRoot;

    public UploadedModelService(ObjectMapper objectMapper, JenaRipperProperties properties) {
        this.objectMapper = objectMapper;
        String configured = properties.settings() == null ? null : properties.settings().path();
        this.settingsPath = ConnectionProfilePaths.resolve(configured);
        this.storageRoot = this.settingsPath.getParent()
                .resolve(ConnectionProfilePaths.UPLOADED_MODELS_DIRECTORY).toAbsolutePath().normalize();
    }

    public synchronized List<UploadedModelDto> list() {
        return readIndex().stream()
                .sorted(Comparator.comparing(UploadedModelDto::uploadedAt).reversed())
                .toList();
    }

    public synchronized UploadedModelDto upload(MultipartFile file) {
        validateFile(file);
        String id = UUID.randomUUID().toString();
        String originalName = safeOriginalName(file.getOriginalFilename());
        String name = defaultName(originalName);
        String extension = originalName.toLowerCase(Locale.ROOT).endsWith(RDF_EXTENSION)
                ? RDF_EXTENSION : XML_EXTENSION;
        String storedFileName = "source" + extension;
        UploadedModelDto processing = new UploadedModelDto(id, name, originalName, storedFileName, file.getSize(),
                Instant.now(), PROCESSING, FULL, null);
        List<UploadedModelDto> models = new ArrayList<>(readIndex());
        models.add(processing);
        writeIndex(models);

        Path modelRoot = modelRoot(id);
        Path source = modelRoot.resolve(storedFileName);
        Path datasetPath = modelRoot.resolve(ConnectionProfilePaths.MODEL_DATASET_DIRECTORY);
        try {
            Files.createDirectories(modelRoot);
            file.transferTo(source);
            importDataset(source, datasetPath, extension);
            UploadedModelDto ready = new UploadedModelDto(id, name, originalName, storedFileName, file.getSize(),
                    processing.uploadedAt(), READY, FULL, null);
            replace(models, ready);
            writeIndex(models);
            return ready;
        } catch (Exception exception) {
            UploadedModelDto error = new UploadedModelDto(id, name, originalName, storedFileName, file.getSize(),
                    processing.uploadedAt(), ERROR, FULL, cleanMessage(exception));
            replace(models, error);
            writeIndex(models);
            try {
                deleteDirectory(datasetPath);
            } catch (UploadedModelException ignored) {
                // The ERROR metadata remains available even if Windows delays releasing a failed TDB2 import.
            }
            throw new UploadedModelException("Не удалось обработать RDF/XML FULL-модель: " + cleanMessage(exception), exception);
        }
    }

    public synchronized void delete(String id) {
        UploadedModelDto model = readIndex().stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Загруженная модель не найдена."));
        List<ModelInUseException.ProfileReference> profiles = referencedProfiles(id);
        if (!profiles.isEmpty()) throw new ModelInUseException(profiles);
        List<UploadedModelDto> remaining = readIndex().stream().filter(item -> !item.id().equals(model.id())).toList();
        deleteDirectory(modelRoot(id));
        writeIndex(remaining);
    }

    public synchronized UploadedModelDto rename(String id, String requestedName) {
        String name = validateName(requestedName);
        List<UploadedModelDto> models = new ArrayList<>(readIndex());
        UploadedModelDto current = models.stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Загруженная модель не найдена."));
        UploadedModelDto renamed = new UploadedModelDto(current.id(), name, current.originalFileName(),
                current.storedFileName(), current.size(), current.uploadedAt(), current.status(), current.type(),
                current.error());
        replace(models, renamed);
        writeIndex(models);
        return renamed;
    }

    public Path datasetPath(String id) {
        return modelRoot(id).resolve(ConnectionProfilePaths.MODEL_DATASET_DIRECTORY);
    }

    public synchronized boolean ready(String id) {
        return id != null && readIndex().stream().anyMatch(model -> model.id().equals(id) && READY.equals(model.status())
                && Files.isDirectory(datasetPath(id)));
    }

    private void importDataset(Path source, Path datasetPath, String extension) {
        Lang language = RDFLanguages.filenameToLang("model" + extension, RDFLanguages.RDFXML);
        Dataset dataset = TDB2Factory.connectDataset(datasetPath.toString());
        DatasetGraph datasetGraph = dataset.asDatasetGraph();
        try {
            dataset.begin(ReadWrite.WRITE);
            try (InputStream input = Files.newInputStream(source)) {
                StreamRDF destination = StreamRDFLib.graph(dataset.getDefaultModel().getGraph());
                readRdfXml(input, language, destination);
                dataset.commit();
            } catch (IOException | RuntimeException | LinkageError exception) {
                dataset.abort();
                throw new UploadedModelException("Ошибка импорта RDF/XML: " + cleanMessage(exception), exception);
            } finally {
                dataset.end();
            }
        } finally {
            dataset.close();
            TDBInternal.expel(datasetGraph, true);
        }
    }

    static void readRdfXml(InputStream input, Lang language, StreamRDF destination) {
        ReaderRIOTFactory factory = RDFParserRegistry.getFactory(language);
        ReaderRIOT reader = factory.create(language,
                RiotLib.profile(language, CIM_RESOURCE_BASE_URI, ErrorHandlerFactory.errorHandlerStd));
        reader.read(input, CIM_RESOURCE_BASE_URI, language.getContentType(), destination, new Context());
    }

    private List<UploadedModelDto> readIndex() {
        Path index = storageRoot.resolve(INDEX_FILE_NAME);
        if (!Files.isRegularFile(index)) return new ArrayList<>();
        try {
            List<UploadedModelDto> stored = objectMapper.readValue(index.toFile(), new TypeReference<>() {});
            List<UploadedModelDto> normalized = stored.stream().map(UploadedModelService::withDefaultName).toList();
            if (!normalized.equals(stored)) writeIndex(normalized);
            return new ArrayList<>(normalized);
        } catch (IOException exception) {
            throw new UploadedModelException("Не удалось прочитать список загруженных моделей.", exception);
        }
    }

    private void writeIndex(List<UploadedModelDto> models) {
        Path index = storageRoot.resolve(INDEX_FILE_NAME);
        Path temporary = storageRoot.resolve(INDEX_TEMPORARY_FILE_NAME);
        try {
            Files.createDirectories(storageRoot);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), models);
            try {
                Files.move(temporary, index, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, index, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new UploadedModelException("Не удалось сохранить список загруженных моделей.", exception);
        }
    }

    private List<ModelInUseException.ProfileReference> referencedProfiles(String id) {
        if (!Files.isRegularFile(settingsPath)) return List.of();
        try {
            JsonNode root = objectMapper.readTree(settingsPath.toFile());
            List<ModelInUseException.ProfileReference> references = new ArrayList<>();
            if (root.has("profiles")) {
                for (JsonNode profile : root.path("profiles")) {
                    JsonNode jena = profile.path("jena");
                    if (RdfSourceProperties.FILE.equalsIgnoreCase(jena.path("sourceType").asText())
                            && id.equals(jena.path("uploadedModelId").asText())) {
                        references.add(new ModelInUseException.ProfileReference(
                                profile.path("id").asText(), profile.path("name").asText()));
                    }
                }
                return references;
            }
            JsonNode jena = root.path("jena");
            if (RdfSourceProperties.FILE.equalsIgnoreCase(jena.path("sourceType").asText())
                    && id.equals(jena.path("uploadedModelId").asText())) {
                references.add(new ModelInUseException.ProfileReference("default", "Текущий профиль"));
            }
            return references;
        } catch (IOException exception) {
            throw new UploadedModelException("Не удалось проверить профили подключений перед удалением.", exception);
        }
    }

    private Path modelRoot(String id) {
        if (id == null || !id.matches("[0-9a-fA-F-]{36}")) throw new IllegalArgumentException("Некорректный идентификатор модели.");
        Path resolved = storageRoot.resolve(id).normalize();
        if (!resolved.startsWith(storageRoot)) throw new IllegalArgumentException("Некорректный идентификатор модели.");
        return resolved;
    }

    private static void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Выберите непустой RDF/XML-файл.");
        String name = safeOriginalName(file.getOriginalFilename()).toLowerCase(Locale.ROOT);
        if (!name.endsWith(XML_EXTENSION) && !name.endsWith(RDF_EXTENSION)) {
            throw new IllegalArgumentException("Поддерживаются только файлы .xml и .rdf.");
        }
    }

    private static String safeOriginalName(String value) {
        if (value == null || value.isBlank()) return "model.xml";
        String name = Path.of(value).getFileName().toString();
        return name.length() > MAX_MODEL_NAME_LENGTH
                ? name.substring(name.length() - MAX_MODEL_NAME_LENGTH) : name;
    }

    private static String defaultName(String originalFileName) {
        String lower = originalFileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(XML_EXTENSION) || lower.endsWith(RDF_EXTENSION)) {
            return originalFileName.substring(0, originalFileName.length() - XML_EXTENSION.length());
        }
        return originalFileName;
    }

    private static String validateName(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Название модели не может быть пустым.");
        }
        String name = value.trim();
        if (name.length() > MAX_MODEL_NAME_LENGTH) {
            throw new IllegalArgumentException("Название модели не должно превышать 255 символов.");
        }
        return name;
    }

    private static UploadedModelDto withDefaultName(UploadedModelDto model) {
        if (model.name() != null && !model.name().isBlank()) return model;
        return new UploadedModelDto(model.id(), defaultName(model.originalFileName()), model.originalFileName(),
                model.storedFileName(), model.size(), model.uploadedAt(), model.status(), model.type(), model.error());
    }

    private static void replace(List<UploadedModelDto> models, UploadedModelDto replacement) {
        for (int index = 0; index < models.size(); index++) {
            if (models.get(index).id().equals(replacement.id())) {
                models.set(index, replacement);
                return;
            }
        }
    }

    private static void deleteDirectory(Path directory) {
        if (!Files.exists(directory)) return;
        IOException failure = null;
        for (int attempt = 0; attempt < DELETE_ATTEMPTS && Files.exists(directory); attempt++) {
            if (attempt > 0 && System.getProperty("os.name", "").toLowerCase().contains("win")) System.gc();
            try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
                List<Path> ordered = paths.sorted(Comparator.reverseOrder()).toList();
                failure = null;
                for (Path path : ordered) {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException exception) {
                        failure = exception;
                    }
                }
            } catch (IOException exception) {
                failure = exception;
            }
        }
        if (Files.exists(directory)) throw new UploadedModelException("Не удалось удалить файлы модели.", failure);
    }

    private static String cleanMessage(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
