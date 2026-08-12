package org.jenaripper.dto;

import java.util.List;

public record RedisRulesResponse(
        OwnerResourceDto resource,
        long datasetId,
        String modelType,
        RedisPermissionsDto permissions,
        List<RedisKeyDto> rawKeys,
        long executionTimeMs,
        String message) {
}
