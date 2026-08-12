package org.jenaripper.controller;

import jakarta.validation.constraints.NotBlank;
import org.jenaripper.dto.GraphNodeDto;
import org.jenaripper.dto.GraphResponseDto;
import org.jenaripper.dto.NodeDetailsDto;
import org.jenaripper.service.GraphService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/nodes")
public class GraphController {
    private final GraphService service;
    public GraphController(GraphService service) { this.service = service; }
    @GetMapping
    public GraphNodeDto node(@RequestParam @NotBlank String uri) { return service.node(uri); }
    @GetMapping("/neighbors")
    public GraphResponseDto neighbors(@RequestParam @NotBlank String uri) { return service.neighbors(uri); }
    @GetMapping("/details")
    public NodeDetailsDto details(@RequestParam @NotBlank String uri) { return service.details(uri); }
}
