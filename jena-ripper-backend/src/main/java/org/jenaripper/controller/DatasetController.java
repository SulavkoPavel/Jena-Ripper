package org.jenaripper.controller;

import lombok.RequiredArgsConstructor;
import org.jenaripper.dto.DatasetInfoDto;
import org.jenaripper.service.DatasetInfoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dataset")
@RequiredArgsConstructor
public class DatasetController {
    private final DatasetInfoService service;
    @GetMapping
    public DatasetInfoDto info() { return service.info(); }
}
