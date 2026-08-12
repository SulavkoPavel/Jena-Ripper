package org.jenaripper.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jenaripper.remote.CimApiClient;
import org.jenaripper.remote.CimApiException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class CimApiRedisCommandExecutor implements RedisCommandExecutor {
    private final CimApiClient client;
    private final ObjectMapper mapper;

    public CimApiRedisCommandExecutor(CimApiClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    @Override public Set<String> smembers(String key) { return new LinkedHashSet<>(strings(result("SMEMBERS", List.of(key)).value())); }
    @Override public boolean sismember(String key, String member) { return result("SISMEMBER", List.of(key, member)).value().asBoolean(); }
    @Override public long scard(String key) { return result("SCARD", List.of(key)).value().asLong(); }
    @Override public String get(String key) { return text(result("GET", List.of(key)).value()); }
    @Override public String type(String key) { return text(result("TYPE", List.of(key)).value()); }
    @Override public boolean exists(String key) { return result("EXISTS", List.of(key)).value().asBoolean(); }
    @Override public long ttl(String key) { return result("TTL", List.of(key)).value().asLong(); }
    @Override public long pttl(String key) { return result("PTTL", List.of(key)).value().asLong(); }
    @Override public String hget(String key, String field) { return text(result("HGET", List.of(key, field)).value()); }
    @Override public Map<String, String> hgetall(String key) { return stringMap(result("HGETALL", List.of(key)).value()); }
    @Override public Map<String, String> hmget(String key, List<String> fields) {
        List<String> args = new ArrayList<>(); args.add(key); args.addAll(fields);
        return stringMap(result("HMGET", args).value());
    }
    @Override public long hlen(String key) { return result("HLEN", List.of(key)).value().asLong(); }
    @Override public List<String> lrange(String key, long start, long stop) { return strings(result("LRANGE", List.of(key, String.valueOf(start), String.valueOf(stop))).value()); }
    @Override public long llen(String key) { return result("LLEN", List.of(key)).value().asLong(); }
    @Override public List<String> zrange(String key, long start, long stop) { return strings(result("ZRANGE", List.of(key, String.valueOf(start), String.valueOf(stop))).value()); }
    @Override public long zcard(String key) { return result("ZCARD", List.of(key)).value().asLong(); }
    @Override public Double zscore(String key, String member) {
        String value = text(result("ZSCORE", List.of(key, member)).value());
        return value == null || value.isBlank() ? null : Double.valueOf(value);
    }
    @Override public ScanResult scan(String cursor, String pattern, long count) {
        List<String> args = new ArrayList<>(); args.add(cursor);
        if (pattern != null && !pattern.isBlank()) { args.add("MATCH"); args.add(pattern); }
        args.add("COUNT"); args.add(String.valueOf(count));
        CimApiClient.RedisResult result = result("SCAN", args);
        return new ScanResult(result.cursor(), strings(result.value()));
    }

    private CimApiClient.RedisResult result(String command, List<String> args) {
        try { return client.redis(command, args); }
        catch (CimApiException exception) { throw normalized(exception); }
    }

    private RedisCommandException normalized(CimApiException exception) {
        String type = switch (exception.type()) {
            case "CIM_REDIS_TIMEOUT" -> "CIM_REDIS_TIMEOUT";
            case "CIM_REDIS_FORBIDDEN" -> "CIM_REDIS_FORBIDDEN";
            case "CIM_REDIS_AUTH" -> "CIM_REDIS_AUTH";
            case "REDIS_COMMAND_FORBIDDEN", "REDIS_COMMAND_INVALID", "REDIS_WRONG_TYPE" -> exception.type();
            default -> "CIM_REDIS_UNAVAILABLE";
        };
        return new RedisCommandException(type, exception.getMessage(), exception);
    }

    private List<String> strings(com.fasterxml.jackson.databind.JsonNode value) {
        return value == null || value.isNull() ? List.of()
                : mapper.convertValue(value, new TypeReference<List<String>>() {});
    }

    private Map<String, String> stringMap(com.fasterxml.jackson.databind.JsonNode value) {
        return value == null || value.isNull() ? Map.of()
                : mapper.convertValue(value, new TypeReference<Map<String, String>>() {});
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode value) {
        return value == null || value.isNull() ? null : value.asText();
    }
}
