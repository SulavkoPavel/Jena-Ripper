package org.jenaripper.controller;

import lombok.RequiredArgsConstructor;
import org.jenaripper.dto.MetamodelAttributeDto;
import org.jenaripper.dto.MetamodelGraphDto;
import org.jenaripper.service.MetamodelService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/metamodel")
@RequiredArgsConstructor
public class MetamodelController {
    private final MetamodelService service;

    @GetMapping("/graph")
    public MetamodelGraphDto graph() {
        return service.graph();
    }

    @GetMapping("/classes/{classId}/attributes")
    public List<MetamodelAttributeDto> attributes(@PathVariable String classId) {
        return service.attributes(classId);
    }
}
