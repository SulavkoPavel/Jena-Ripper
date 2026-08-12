package org.jenaripper.redis;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@Primary
public class RoutingRedisCommandExecutor implements RedisCommandExecutor {
    private final LettuceRedisCommandExecutor local;

    public RoutingRedisCommandExecutor(LettuceRedisCommandExecutor local) {
        this.local = local;
    }

    private RedisCommandExecutor delegate() { return local; }
    @Override public Set<String> smembers(String key) { return delegate().smembers(key); }
    @Override public boolean sismember(String key, String member) { return delegate().sismember(key, member); }
    @Override public long scard(String key) { return delegate().scard(key); }
    @Override public String get(String key) { return delegate().get(key); }
    @Override public String type(String key) { return delegate().type(key); }
    @Override public boolean exists(String key) { return delegate().exists(key); }
    @Override public long ttl(String key) { return delegate().ttl(key); }
    @Override public long pttl(String key) { return delegate().pttl(key); }
    @Override public String hget(String key, String field) { return delegate().hget(key, field); }
    @Override public Map<String, String> hgetall(String key) { return delegate().hgetall(key); }
    @Override public Map<String, String> hmget(String key, List<String> fields) { return delegate().hmget(key, fields); }
    @Override public long hlen(String key) { return delegate().hlen(key); }
    @Override public List<String> lrange(String key, long start, long stop) { return delegate().lrange(key, start, stop); }
    @Override public long llen(String key) { return delegate().llen(key); }
    @Override public List<String> zrange(String key, long start, long stop) { return delegate().zrange(key, start, stop); }
    @Override public long zcard(String key) { return delegate().zcard(key); }
    @Override public Double zscore(String key, String member) { return delegate().zscore(key, member); }
    @Override public ScanResult scan(String cursor, String pattern, long count) { return delegate().scan(cursor, pattern, count); }
}
