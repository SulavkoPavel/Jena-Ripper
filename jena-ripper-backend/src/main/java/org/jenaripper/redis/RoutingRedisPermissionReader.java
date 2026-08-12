package org.jenaripper.redis;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@Primary
public class RoutingRedisPermissionReader implements RedisPermissionReader {
    private final LettuceRedisPermissionReader local;

    public RoutingRedisPermissionReader(LettuceRedisPermissionReader local) {
        this.local = local;
    }

    @Override public Set<String> members(String key) {
        try { return local.members(key); }
        catch (RedisCommandException exception) { throw unavailable(exception); }
    }
    @Override public boolean contains(String key, String member) {
        try { return local.contains(key, member); }
        catch (RedisCommandException exception) { throw unavailable(exception); }
    }
    @Override public long size(String key) {
        try { return local.size(key); }
        catch (RedisCommandException exception) { throw unavailable(exception); }
    }

    private RedisRulesUnavailableException unavailable(RedisCommandException exception) {
        boolean timeout = "CIM_REDIS_TIMEOUT".equals(exception.type()) || "REDIS_TIMEOUT".equals(exception.type());
        return new RedisRulesUnavailableException(exception.getMessage(), timeout, exception);
    }
}
