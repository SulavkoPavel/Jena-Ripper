package org.jenaripper.redis;

import java.util.Set;

public interface RedisPermissionReader {
    Set<String> members(String key);
    boolean contains(String key, String member);
    long size(String key);
}
