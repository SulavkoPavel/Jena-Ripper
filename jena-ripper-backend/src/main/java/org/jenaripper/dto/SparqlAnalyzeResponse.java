package org.jenaripper.dto;

public record SparqlAnalyzeResponse(String type, SparqlQueryAnalysis analysis, SparqlAlgebraDto algebra) {}
