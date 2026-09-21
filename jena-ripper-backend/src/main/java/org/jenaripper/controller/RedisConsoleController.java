package org.jenaripper.controller;

import lombok.RequiredArgsConstructor;
import org.jenaripper.dto.RedisCommandRequest;
import org.jenaripper.dto.RedisCommandResponse;
import org.jenaripper.dto.RedisConsoleMetadataDto;
import org.jenaripper.dto.RedisKeyRequest;
import org.jenaripper.dto.RedisKeyResponse;
import org.jenaripper.service.RedisCommandService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/redis")
@RequiredArgsConstructor
public class RedisConsoleController {
    private final RedisCommandService service;

    @GetMapping("/metadata")
    public RedisConsoleMetadataDto metadata() { return service.metadata(); }

    @PostMapping("/command")
    public RedisCommandResponse command(@RequestBody RedisCommandRequest request) { return service.execute(request); }

    @PostMapping("/key")
    public RedisKeyResponse key(@RequestBody RedisKeyRequest request) { return service.key(request); }
}
