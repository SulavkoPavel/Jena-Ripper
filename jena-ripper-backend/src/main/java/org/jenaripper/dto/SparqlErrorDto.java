package org.jenaripper.dto;

public record SparqlErrorDto(String type, String message, Integer line, Integer column) {}
