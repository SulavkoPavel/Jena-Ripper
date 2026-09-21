package org.jenaripper.controller;

import lombok.RequiredArgsConstructor;
import org.jenaripper.dto.DatasetStatusDto;
import org.jenaripper.service.DatasetInfoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/status")
@RequiredArgsConstructor
public class StatusController {
    private final DatasetInfoService service;

    @GetMapping
    public DatasetStatusDto status() {
        return service.status();
    }
}
