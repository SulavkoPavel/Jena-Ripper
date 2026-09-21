package org.jenaripper.service;

import org.jenaripper.config.JenaRipperProperties;
import org.jenaripper.config.RdfSourceProperties;
import org.jenaripper.dto.RedisCommandRequest;
import org.jenaripper.dto.RedisCommandResponse;
import org.jenaripper.dto.RedisConsoleMetadataDto;
import org.jenaripper.dto.RedisKeyRequest;
import org.jenaripper.dto.RedisKeyResponse;
import org.jenaripper.exception.RedisCommandException;
import org.jenaripper.redis.RedisCommandExecutor;
import org.jenaripper.redis.RedisCommandRegistry;
import org.jenaripper.redis.RedisPermissionKeyFactory;
import org.jenaripper.remote.CimApiClient;
import org.jenaripper.exception.CimApiException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Locale;

@Service
public class RedisCommandService {
    private final RedisCommandRegistry registry;
    private final RedisCommandExecutor executor;
    private final JenaRipperProperties properties;
    private final RdfSourceProperties sourceProperties;
    private final CimApiClient cimApi;

    @Autowired
    public RedisCommandService(RedisCommandRegistry registry, RedisCommandExecutor executor,
                               JenaRipperProperties properties, RdfSourceProperties sourceProperties,
                               CimApiClient cimApi) {
        this.registry = registry;
        this.executor = executor;
        this.properties = properties;
        this.sourceProperties = sourceProperties;
        this.cimApi = cimApi;
    }

    public RedisCommandService(RedisCommandRegistry registry, RedisCommandExecutor executor,
                               JenaRipperProperties properties, RdfSourceProperties sourceProperties) {
        this(registry, executor, properties, sourceProperties, null);
    }

    public RedisCommandResponse execute(RedisCommandRequest request) {
        ensureAvailable();
        if (request == null) throw new RedisCommandException("REDIS_COMMAND_INVALID", "Redis-команда не задана.");
        long started = System.nanoTime();
        try {
            RedisCommandRegistry.Dispatch dispatch = registry.dispatch(request.command(), request.args(), executor);
            long elapsed = Math.max(0, (System.nanoTime() - started) / 1_000_000);
            RedisCommandRegistry.Result result = dispatch.result();
            return new RedisCommandResponse(dispatch.metadata().name(), dispatch.metadata().category(),
                    result.type(), result.value(), result.cursor(), result.count(), elapsed);
        } catch (RedisCommandException exception) {
            if ("REDIS_WRONG_TYPE".equals(exception.type())) throw wrongType(request, exception);
            throw exception;
        } catch (RuntimeException exception) {
            String message = String.valueOf(exception.getMessage());
            String lower = (exception.getClass().getSimpleName() + " " + message).toLowerCase(Locale.ROOT);
            if (message.toUpperCase(Locale.ROOT).contains("WRONGTYPE")) throw wrongType(request, exception);
            if (lower.contains("timeout")) throw new RedisCommandException("REDIS_TIMEOUT", "Redis не ответил за установленное время.", exception);
            throw new RedisCommandException("REDIS_UNAVAILABLE", "Redis недоступен для текущего профиля.", exception);
        }
    }

    public RedisConsoleMetadataDto metadata() {
        long datasetId = datasetId();
        return new RedisConsoleMetadataDto(available(), datasetId, registry.metadata(), List.of(
                new RedisConsoleMetadataDto.Template("read", "READ объекта", "Показать READ rules для resource.", "SMEMBERS {{readKey}}"),
                new RedisConsoleMetadataDto.Template("read-top", "READ TOP объекта", "Показать READ TOP rules для resource.", "SMEMBERS {{readTopKey}}"),
                new RedisConsoleMetadataDto.Template("write", "WRITE объекта", "Показать WRITE rules для resource.", "SMEMBERS {{writeKey}}"),
                new RedisConsoleMetadataDto.Template("owner", "Проверить owner", "Проверить owner в permission SET.", "SISMEMBER {{key}} {{owner}}"),
                new RedisConsoleMetadataDto.Template("size", "Размер permission set", "Показать число owners.", "SCARD {{key}}"),
                new RedisConsoleMetadataDto.Template("scan", "Найти keys Dataset",
                        "Обойти keys Dataset без KEYS.",
                        "SCAN 0 MATCH {{datasetId}}/* COUNT " + RedisCommandRegistry.DEFAULT_SCAN_COUNT)
        ));
    }

    public RedisKeyResponse key(RedisKeyRequest request) {
        if (request == null || request.datasetId() == null || request.datasetId() < 0) {
            throw new RedisCommandException("REDIS_COMMAND_INVALID", "Dataset ID не задан.");
        }
        String resource = request.resource() == null ? "" : request.resource().trim();
        if (resource.isBlank()) throw new RedisCommandException("REDIS_COMMAND_INVALID", "Resource не задан.");
        String prefix = switch (request.rule() == null ? "" : request.rule().trim().toUpperCase(Locale.ROOT)) {
            case "READ" -> RedisPermissionKeyFactory.READ;
            case "READ_TOP" -> RedisPermissionKeyFactory.READ_TOP;
            case "WRITE" -> RedisPermissionKeyFactory.WRITE;
            default -> throw new RedisCommandException("REDIS_COMMAND_INVALID", "Rule должно быть READ, READ_TOP или WRITE.");
        };
        String key = RedisPermissionKeyFactory.key(request.datasetId(), prefix, resource);
        return new RedisKeyResponse(key, "SMEMBERS " + key);
    }

    private boolean available() {
        JenaRipperProperties.RedisRules config = properties.redisRules();
        return config != null && config.enabled()
                && config.host() != null && !config.host().isBlank() && config.port() > 0;
    }

    private void ensureAvailable() {
        if (!available()) throw new RedisCommandException("REDIS_UNAVAILABLE",
                "Redis не настроен для текущего профиля. Откройте настройки подключения.");
    }

    private long datasetId() {
        JenaRipperProperties.RedisRules config = properties.redisRules();
        return config == null ? 0 : config.datasetId();
    }

    private RedisCommandException wrongType(RedisCommandRequest request, RuntimeException cause) {
        String actual = null;
        try {
            if (request.args() != null && !request.args().isEmpty()) actual = executor.type(request.args().get(0));
        } catch (RuntimeException ignored) {}
        String suffix = actual == null || actual.isBlank() ? "" : " Фактический тип: " + actual.toUpperCase(Locale.ROOT) + ".";
        return new RedisCommandException("REDIS_WRONG_TYPE", "Команда не подходит для типа этого Redis key." + suffix, cause);
    }
}
