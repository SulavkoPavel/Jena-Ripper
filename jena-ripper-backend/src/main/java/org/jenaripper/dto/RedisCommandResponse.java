package org.jenaripper.dto;

public record RedisCommandResponse(
        String command,
        String category,
        String resultType,
        Object value,
        String cursor,
        int count,
        long executionTimeMs) {}
