package org.jenaripper.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public record UserDataDto(
        int version,
        List<JsonNode> sparqlTemplates,
        List<JsonNode> sparqlHistory,
        List<JsonNode> redisTemplates,
        List<JsonNode> redisHistory) {

    public UserDataDto {
        version = 1;
        sparqlTemplates = safe(sparqlTemplates);
        sparqlHistory = safe(sparqlHistory);
        redisTemplates = safe(redisTemplates);
        redisHistory = safe(redisHistory);
    }

    public static UserDataDto empty() {
        return new UserDataDto(1, List.of(), List.of(), List.of(), List.of());
    }

    private static List<JsonNode> safe(List<JsonNode> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
