package org.jenaripper.dto;

public record RedisKeyDto(String key, String role, String type, long valueCount) {
}
