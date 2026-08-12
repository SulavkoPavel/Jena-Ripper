package org.jenaripper.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import jakarta.annotation.PreDestroy;
import org.jenaripper.config.JenaRipperProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

@Component
public class LettuceRedisPermissionReader implements RedisPermissionReader {
    private final JenaRipperProperties properties;
    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;

    public LettuceRedisPermissionReader(JenaRipperProperties properties) {
        this.properties = properties;
    }

    @Override
    public Set<String> members(String key) {
        try {
            return new LinkedHashSet<>(connection().sync().smembers(key));
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public boolean contains(String key, String member) {
        try {
            return connection().sync().sismember(key, member);
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public long size(String key) {
        try {
            return connection().sync().scard(key);
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private synchronized StatefulRedisConnection<String, String> connection() {
        if (connection != null && connection.isOpen()) return connection;
        JenaRipperProperties.RedisRules config = properties.redisRules();
        Duration timeout = config.timeout() == null ? Duration.ofSeconds(5) : config.timeout();
        RedisURI.Builder builder = RedisURI.builder()
                .withHost(config.host())
                .withPort(config.port())
                .withDatabase(config.database())
                .withTimeout(timeout);
        if (config.username() != null && !config.username().isBlank()) {
            builder.withAuthentication(config.username(), config.password() == null ? new char[0] : config.password().toCharArray());
        } else if (config.password() != null && !config.password().isBlank()) {
            builder.withPassword(config.password().toCharArray());
        }
        client = RedisClient.create(builder.build());
        client.setDefaultTimeout(timeout);
        connection = client.connect();
        return connection;
    }

    private RedisRulesUnavailableException unavailable(RuntimeException exception) {
        String name = exception.getClass().getSimpleName().toLowerCase();
        boolean timeout = name.contains("timeout") || String.valueOf(exception.getMessage()).toLowerCase().contains("timeout");
        return new RedisRulesUnavailableException(
                timeout ? "Redis не ответил за установленное время." : "Redis временно недоступен.",
                timeout, exception);
    }

    @PreDestroy
    public synchronized void close() {
        if (connection != null) connection.close();
        if (client != null) client.shutdown();
    }
}
