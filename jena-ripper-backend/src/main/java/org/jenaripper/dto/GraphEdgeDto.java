package org.jenaripper.dto;

public record GraphEdgeDto(
        String id,
        String source,
        String target,
        String predicateUri,
        String predicate,
        String label,
        String direction) {}
