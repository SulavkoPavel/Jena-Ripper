package org.jenaripper.dto;

import java.util.List;

public record SparqlQueryAnalysis(
        int triplePatterns,
        int variables,
        int projectedVariables,
        int filters,
        int optionals,
        int unions,
        int propertyPaths,
        boolean selectStar,
        boolean distinct,
        boolean ordered,
        boolean grouped,
        List<String> aggregates,
        List<QueryRecommendation> recommendations) {}
