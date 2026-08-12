package org.jenaripper.dto;

import java.util.List;
import java.util.Map;

public record SparqlQueryResponse(
        String type,
        List<String> variables,
        List<Map<String, SparqlBindingDto>> rows,
        Boolean value,
        Long tripleCount,
        List<SparqlStatementDto> statements,
        List<GraphNodeDto> nodes,
        List<GraphEdgeDto> edges,
        long executionTimeMs,
        boolean truncated,
        int limit,
        SparqlExecutionMetrics metrics,
        SparqlQueryAnalysis analysis,
        SparqlAlgebraDto algebra) {}
