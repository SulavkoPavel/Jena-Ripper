package org.jenaripper.dto;

public record RdfPropertyDto(
        String predicate,
        String predicateUri,
        String value,
        String fullValue,
        String valueType,
        String datatype,
        String language) {}

