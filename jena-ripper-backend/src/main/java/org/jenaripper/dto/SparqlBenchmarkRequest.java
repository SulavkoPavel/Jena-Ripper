package org.jenaripper.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record SparqlBenchmarkRequest(
        @NotBlank String query,
        @Min(0) @Max(3) Integer warmup,
        @Min(1) @Max(10) Integer runs,
        Boolean useOwnerRules) {
    public SparqlBenchmarkRequest(String query, Integer warmup, Integer runs) { this(query, warmup, runs, false); }
    public int effectiveWarmup() { return warmup == null ? 1 : warmup; }
    public int effectiveRuns() { return runs == null ? 5 : runs; }
    public boolean effectiveUseOwnerRules() { return Boolean.TRUE.equals(useOwnerRules); }
}
