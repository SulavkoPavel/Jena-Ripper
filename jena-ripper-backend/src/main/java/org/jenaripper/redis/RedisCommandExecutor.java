package org.jenaripper.redis;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface RedisCommandExecutor {
    Set<String> smembers(String key);
    boolean sismember(String key, String member);
    long scard(String key);
    String get(String key);
    String type(String key);
    boolean exists(String key);
    long ttl(String key);
    long pttl(String key);
    String hget(String key, String field);
    Map<String, String> hgetall(String key);
    Map<String, String> hmget(String key, List<String> fields);
    long hlen(String key);
    List<String> lrange(String key, long start, long stop);
    long llen(String key);
    List<String> zrange(String key, long start, long stop);
    long zcard(String key);
    Double zscore(String key, String member);
    ScanResult scan(String cursor, String pattern, long count);

    record ScanResult(String cursor, List<String> keys) {}
}
