package org.jenaripper.config;

import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.tdb2.TDB2Factory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class DatasetConfiguration {
    @Bean(destroyMethod = "close")
    Dataset dataset(JenaRipperProperties properties, DatasetRuntimeState runtimeState) {
        JenaRipperProperties.Dataset config = properties.dataset();
        if (config == null || config.type() == null) {
            throw new IllegalArgumentException("jena-ripper.dataset.type is required");
        }
        return switch (config.type().toLowerCase()) {
            case "tdb2" -> openTdb2(config.path(), runtimeState);
            case "memory", "in-memory" -> DatasetFactory.createTxnMem();
            default -> throw new IllegalArgumentException("Unsupported dataset type: " + config.type());
        };
    }

    private Dataset openTdb2(String path, DatasetRuntimeState runtimeState) {
        try {
            return TDB2Factory.connectDataset(Path.of(path).toAbsolutePath().normalize().toString());
        } catch (RuntimeException exception) {
            runtimeState.fallback("Локальный TDB2 недоступен: " + cleanMessage(exception));
            return DatasetFactory.createTxnMem();
        }
    }

    private static String cleanMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
