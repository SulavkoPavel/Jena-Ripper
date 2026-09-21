package org.jenaripper.controller;

import lombok.RequiredArgsConstructor;
import org.jenaripper.dto.RedisRulesResponse;
import org.jenaripper.service.RedisRulesService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/nodes/redis-rules")
@RequiredArgsConstructor
public class RedisRulesController {
    private final RedisRulesService service;

    @GetMapping
    public RedisRulesResponse redisRules(@RequestParam String uri) {
        return service.inspect(uri);
    }
}
