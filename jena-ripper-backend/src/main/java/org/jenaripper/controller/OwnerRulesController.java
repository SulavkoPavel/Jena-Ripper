package org.jenaripper.controller;

import org.jenaripper.dto.OwnerRulesResponse;
import org.jenaripper.service.OwnerRulesService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/nodes/owner-rules")
public class OwnerRulesController {
    private final OwnerRulesService service;

    public OwnerRulesController(OwnerRulesService service) {
        this.service = service;
    }

    @GetMapping
    public OwnerRulesResponse ownerRules(@RequestParam String uri) {
        return service.resolve(uri);
    }
}
