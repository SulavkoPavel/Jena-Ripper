package org.jenaripper.service;

import org.jenaripper.dto.SparqlBenchmarkRequest;
import org.jenaripper.dto.SparqlBenchmarkResponse;
import org.jenaripper.dto.SparqlQueryResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class SparqlBenchmarkService {
    private final SparqlQueryService queryService;

    public SparqlBenchmarkService(SparqlQueryService queryService) {
        this.queryService = queryService;
    }

    public SparqlBenchmarkResponse benchmark(SparqlBenchmarkRequest request) {
        List<Long> warmups = new ArrayList<>();
        List<Long> runs = new ArrayList<>();
        SparqlQueryResponse latest = null;
        for (int index = 0; index < request.effectiveWarmup(); index++) {
            latest = queryService.execute(request.query(), null, request.effectiveUseOwnerRules());
            warmups.add(latest.metrics().totalTimeMs());
        }
        for (int index = 0; index < request.effectiveRuns(); index++) {
            latest = queryService.execute(request.query(), null, request.effectiveUseOwnerRules());
            runs.add(latest.metrics().totalTimeMs());
        }
        List<Long> sorted = new ArrayList<>(runs);
        Collections.sort(sorted);
        long median = sorted.size() % 2 == 1
                ? sorted.get(sorted.size() / 2)
                : Math.round((sorted.get(sorted.size() / 2 - 1) + sorted.get(sorted.size() / 2)) / 2.0);
        return new SparqlBenchmarkResponse(
                latest.type(), List.copyOf(warmups), List.copyOf(runs), sorted.get(0), median,
                runs.stream().mapToLong(Long::longValue).average().orElse(0), sorted.get(sorted.size() - 1),
                latest.metrics().resultCount());
    }
}
