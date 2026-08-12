package org.jenaripper.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jenaripper.dto.UserDataDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

@Repository
public class UserDataRepository {
    private static final Logger log = LoggerFactory.getLogger(UserDataRepository.class);
    private static final String FILE_NAME = "jena-ripper-user-data.json";

    private final ObjectMapper objectMapper;
    private final Path path;
    private UserDataDto data;

    public UserDataRepository(ObjectMapper objectMapper,
                              @Value("${jena-ripper.user-data.path:}") String configuredPath,
                              @Value("${jena-ripper.settings.path:${user.home}/.jena-ripper/jena-ripper-settings.json}") String settingsPath) {
        this.objectMapper = objectMapper;
        this.path = resolvePath(configuredPath, settingsPath);
        this.data = load();
    }

    public synchronized UserDataDto current() {
        return data;
    }

    public synchronized UserDataDto save(UserDataDto updated) {
        UserDataDto normalized = updated == null ? UserDataDto.empty() : updated;
        write(normalized);
        data = normalized;
        return data;
    }

    Path path() {
        return path;
    }

    private UserDataDto load() {
        if (!Files.isRegularFile(path)) return UserDataDto.empty();
        try {
            return objectMapper.readValue(path.toFile(), UserDataDto.class);
        } catch (Exception exception) {
            backupCorruptedFile();
            log.error("Could not read Jena Ripper user data. Empty collections will be used. Backup created for {}", path);
            return UserDataDto.empty();
        }
    }

    private void write(UserDataDto updated) {
        try {
            Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            byte[] content = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(updated);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Не удалось сохранить пользовательские шаблоны и историю.", exception);
        }
    }

    private void backupCorruptedFile() {
        try {
            String suffix = ".corrupt-" + Instant.now().toEpochMilli() + ".bak";
            Files.copy(path, path.resolveSibling(path.getFileName() + suffix));
        } catch (Exception exception) {
            log.error("Could not create a backup of corrupted Jena Ripper user data: {}", path);
        }
    }

    private static Path resolvePath(String configuredPath, String settingsPath) {
        if (configuredPath != null && !configuredPath.isBlank()) {
            return Path.of(configuredPath).toAbsolutePath().normalize();
        }
        Path settings = Path.of(settingsPath).toAbsolutePath().normalize();
        return settings.resolveSibling(FILE_NAME);
    }
}
