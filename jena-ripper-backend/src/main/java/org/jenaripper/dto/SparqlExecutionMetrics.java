package org.jenaripper.dto;

public record SparqlExecutionMetrics(
        long executionTimeMs,
        long serializationTimeMs,
        long totalTimeMs,
        long resultCount,
        String status,
        String speed) {}
