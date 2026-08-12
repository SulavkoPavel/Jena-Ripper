package org.jenaripper.dto;

import java.util.List;

public record SparqlBenchmarkResponse(
        String type,
        List<Long> warmupTimesMs,
        List<Long> runTimesMs,
        long minTimeMs,
        long medianTimeMs,
        double averageTimeMs,
        long maxTimeMs,
        long resultCount) {}
