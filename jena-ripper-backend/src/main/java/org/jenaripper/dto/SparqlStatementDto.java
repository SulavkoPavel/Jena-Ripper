package org.jenaripper.dto;

public record SparqlStatementDto(
        SparqlBindingDto subject,
        SparqlBindingDto predicate,
        SparqlBindingDto object) {}
