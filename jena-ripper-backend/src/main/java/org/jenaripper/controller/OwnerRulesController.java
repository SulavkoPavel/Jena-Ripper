package org.jenaripper.controller;

import lombok.RequiredArgsConstructor;
import org.jenaripper.dto.OwnerRulesResponse;
import org.jenaripper.service.OwnerRulesService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/nodes/owner-rules")
@RequiredArgsConstructor
public class OwnerRulesController {
    private final OwnerRulesService service;

    @GetMapping
    public OwnerRulesResponse ownerRules(@RequestParam String uri) {
        return service.resolve(uri);
    }
}
