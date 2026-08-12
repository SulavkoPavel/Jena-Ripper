package org.jenaripper.redis;

import java.util.ArrayList;
import java.util.List;

public final class RedisPermissionKeyFactory {
    public static final String READ = "rb:";
    public static final String READ_TOP = "rt:";
    public static final String WRITE = "wb:";
    public static final String REVERSE = "rev:/";

    private RedisPermissionKeyFactory() {}

    public static String key(long datasetId, String prefix, String id) {
        return datasetId + "/" + prefix + id;
    }

    public static String diffKey(long diffId, long datasetId, String prefix, String id) {
        return diffId + "/" + datasetId + "/" + prefix + id;
    }

    public static List<String> contextualKeys(long datasetId, Long diffId, String modelType, String prefix, String id) {
        List<String> keys = new ArrayList<>();
        keys.add(key(datasetId, prefix, id));
        if (diffId != null && ("PIM".equalsIgnoreCase(modelType) || "PIM_DIFF".equalsIgnoreCase(modelType))) {
            keys.add(diffKey(diffId, datasetId, prefix, id));
        }
        return keys;
    }
}
