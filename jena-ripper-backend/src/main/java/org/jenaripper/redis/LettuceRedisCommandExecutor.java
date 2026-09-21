package org.jenaripper.redis;

import org.jenaripper.exception.RedisCommandException;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.KeyValue;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.annotation.PreDestroy;
import org.jenaripper.config.JenaRipperProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class LettuceRedisCommandExecutor implements RedisCommandExecutor {
    private final JenaRipperProperties properties;
    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;

    public LettuceRedisCommandExecutor(JenaRipperProperties properties) {
        this.properties = properties;
    }

    @Override public Set<String> smembers(String key) { return commands().smembers(key); }
    @Override public boolean sismember(String key, String member) { return commands().sismember(key, member); }
    @Override public long scard(String key) { return commands().scard(key); }
    @Override public String get(String key) { return commands().get(key); }
    @Override public String type(String key) { return commands().type(key); }
    @Override public boolean exists(String key) { return commands().exists(key) > 0; }
    @Override public long ttl(String key) { return commands().ttl(key); }
    @Override public long pttl(String key) { return commands().pttl(key); }
    @Override public String hget(String key, String field) { return commands().hget(key, field); }
    @Override public Map<String, String> hgetall(String key) { return commands().hgetall(key); }
    @Override public Map<String, String> hmget(String key, List<String> fields) {
        List<KeyValue<String, String>> values = commands().hmget(key, fields.toArray(String[]::new));
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < fields.size(); i++) result.put(fields.get(i), values.get(i).getValueOrElse(null));
        return result;
    }
    @Override public long hlen(String key) { return commands().hlen(key); }
    @Override public List<String> lrange(String key, long start, long stop) { return commands().lrange(key, start, stop); }
    @Override public long llen(String key) { return commands().llen(key); }
    @Override public List<String> zrange(String key, long start, long stop) { return commands().zrange(key, start, stop); }
    @Override public long zcard(String key) { return commands().zcard(key); }
    @Override public Double zscore(String key, String member) { return commands().zscore(key, member); }
    @Override public ScanResult scan(String cursor, String pattern, long count) {
        ScanArgs args = ScanArgs.Builder.limit(count);
        if (pattern != null && !pattern.isBlank()) args.match(pattern);
        KeyScanCursor<String> result = commands().scan(ScanCursor.of(cursor), args);
        return new ScanResult(result.getCursor(), result.getKeys());
    }

    private RedisCommands<String, String> commands() {
        try {
            return connection().sync();
        } catch (RuntimeException exception) {
            throw normalized(exception);
        }
    }

    private synchronized StatefulRedisConnection<String, String> connection() {
        if (connection != null && connection.isOpen()) return connection;
        JenaRipperProperties.RedisRules config = properties.redisRules();
        Duration timeout = config.timeout() == null ? Duration.ofSeconds(5) : config.timeout();
        RedisURI.Builder builder = RedisURI.builder().withHost(config.host()).withPort(config.port())
                .withDatabase(config.database()).withTimeout(timeout);
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

    private RedisCommandException normalized(RuntimeException exception) {
        String message = String.valueOf(exception.getMessage());
        String lower = (exception.getClass().getSimpleName() + " " + message).toLowerCase();
        if (lower.contains("timeout")) return new RedisCommandException("REDIS_TIMEOUT", "Redis не ответил за установленное время.", exception);
        if (message.toUpperCase().contains("WRONGTYPE")) return new RedisCommandException("REDIS_WRONG_TYPE", "Команда не подходит для типа этого Redis key.", exception);
        return new RedisCommandException("REDIS_UNAVAILABLE", "Redis недоступен для текущего профиля.", exception);
    }

    @PreDestroy
    public synchronized void close() {
        if (connection != null) connection.close();
        if (client != null) client.shutdown();
    }
}
