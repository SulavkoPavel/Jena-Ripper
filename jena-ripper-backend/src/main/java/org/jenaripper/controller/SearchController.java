package org.jenaripper.controller;

import org.jenaripper.dto.SearchResultDto;
import org.jenaripper.service.GraphService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/search")
public class SearchController {
    private final GraphService service;
    public SearchController(GraphService service) { this.service = service; }
    @GetMapping
    public List<SearchResultDto> search(@RequestParam(defaultValue = "") String q) { return service.search(q); }
}

