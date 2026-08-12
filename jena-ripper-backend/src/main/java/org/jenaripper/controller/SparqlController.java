package org.jenaripper.controller;

import jakarta.validation.Valid;
import org.jenaripper.dto.SparqlQueryRequest;
import org.jenaripper.dto.SparqlQueryResponse;
import org.jenaripper.dto.SparqlAnalyzeResponse;
import org.jenaripper.dto.SparqlBenchmarkRequest;
import org.jenaripper.dto.SparqlBenchmarkResponse;
import org.jenaripper.service.SparqlBenchmarkService;
import org.jenaripper.service.SparqlQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/sparql")
public class SparqlController {
    private final SparqlQueryService service;
    private final SparqlBenchmarkService benchmarkService;

    public SparqlController(SparqlQueryService service, SparqlBenchmarkService benchmarkService) {
        this.service = service;
        this.benchmarkService = benchmarkService;
    }

    @PostMapping("/query")
    public SparqlQueryResponse query(@Valid @RequestBody SparqlQueryRequest request) {
        return service.execute(request.query(), request.requestId(), Boolean.TRUE.equals(request.useOwnerRules()));
    }

    @DeleteMapping("/query/{requestId}")
    public Map<String, Boolean> cancel(@PathVariable String requestId) {
        return Map.of("cancelled", service.cancel(requestId));
    }

    @PostMapping("/analyze")
    public SparqlAnalyzeResponse analyze(@Valid @RequestBody SparqlQueryRequest request) {
        return service.analyze(request.query());
    }

    @PostMapping("/benchmark")
    public SparqlBenchmarkResponse benchmark(@Valid @RequestBody SparqlBenchmarkRequest request) {
        return benchmarkService.benchmark(request);
    }

    @GetMapping("/prefixes")
    public Map<String, String> prefixes() {
        return service.prefixes();
    }
}
