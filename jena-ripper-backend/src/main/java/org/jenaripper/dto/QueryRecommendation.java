package org.jenaripper.dto;

import java.util.Map;

public record QueryRecommendation(String code, String severity, Map<String, Object> data) {
    public QueryRecommendation(String code, String severity) {
        this(code, severity, Map.of());
    }
}
