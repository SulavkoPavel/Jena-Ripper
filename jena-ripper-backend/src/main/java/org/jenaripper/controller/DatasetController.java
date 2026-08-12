package org.jenaripper.controller;

import org.jenaripper.dto.DatasetInfoDto;
import org.jenaripper.service.DatasetInfoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dataset")
public class DatasetController {
    private final DatasetInfoService service;
    public DatasetController(DatasetInfoService service) { this.service = service; }
    @GetMapping
    public DatasetInfoDto info() { return service.info(); }
}

