package org.jenaripper.dto;

import jakarta.validation.constraints.NotBlank;

public record SparqlQueryRequest(@NotBlank String query, String requestId, Boolean useOwnerRules) {
    public SparqlQueryRequest(String query, String requestId) { this(query, requestId, false); }
}
