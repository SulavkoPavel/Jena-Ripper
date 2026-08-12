package org.jenaripper.controller;

import com.fasterxml.jackson.databind.JsonNode;
import org.jenaripper.dto.UserDataDto;
import org.jenaripper.service.UserDataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/user-data")
public class UserDataController {
    private final UserDataService service;

    public UserDataController(UserDataService service) {
        this.service = service;
    }

    @GetMapping
    public UserDataDto current() {
        return service.current();
    }

    @PutMapping("/{area}/{collection}")
    public UserDataDto update(@PathVariable String area, @PathVariable String collection,
                              @RequestBody List<JsonNode> values) {
        return service.update(area, collection, values);
    }

    @PostMapping("/migrate")
    public UserDataDto migrate(@RequestBody UserDataDto legacy) {
        return service.migrate(legacy);
    }
}
