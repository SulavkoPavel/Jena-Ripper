package org.jenaripper.dto;

public record SparqlBindingDto(
        String type,
        String value,
        String displayValue,
        String datatype,
        String language) {}
