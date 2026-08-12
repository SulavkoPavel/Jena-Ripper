package org.jenaripper.controller;

import org.jenaripper.dto.*;
import org.jenaripper.service.ConnectionSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings/connections")
public class ConnectionSettingsController {
    private final ConnectionSettingsService service;

    public ConnectionSettingsController(ConnectionSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public ConnectionSettingsDto current() { return service.current(); }

    @PutMapping
    public ConnectionSettingsSaveResponse save(@RequestBody ConnectionSettingsUpdateRequest request) {
        return service.save(request);
    }

    @GetMapping("/profiles")
    public ConnectionProfilesDto profiles() { return service.profiles(); }

    @PostMapping("/profiles")
    public ConnectionProfilesDto createProfile(@RequestBody CreateConnectionProfileRequest request) {
        return service.createProfile(request);
    }

    @PutMapping("/profiles/{profileId}")
    public ConnectionSettingsSaveResponse saveProfile(@PathVariable String profileId,
                                                       @RequestBody ConnectionSettingsUpdateRequest request) {
        return service.saveProfile(profileId, request);
    }

    @PutMapping("/profiles/{profileId}/name")
    public ConnectionProfilesDto renameProfile(@PathVariable String profileId,
                                                @RequestBody RenameConnectionProfileRequest request) {
        return service.renameProfile(profileId, request);
    }

    @DeleteMapping("/profiles/{profileId}")
    public ConnectionProfilesDto deleteProfile(@PathVariable String profileId) {
        return service.deleteProfile(profileId);
    }

    @PostMapping("/test-jena")
    public ConnectionTestResponse testJena(@RequestBody ConnectionSettingsUpdateRequest.JenaSettings request,
                                           @RequestParam(required = false) String profileId) {
        return service.testJena(request, profileId);
    }

    @PostMapping("/cim-models")
    public java.util.List<org.jenaripper.remote.CimApiModel> cimModels(
            @RequestBody ConnectionSettingsUpdateRequest.JenaSettings request,
            @RequestParam(required = false) String profileId) {
        return service.cimModels(request, profileId);
    }

    @PostMapping("/test-postgres")
    public ConnectionTestResponse testPostgres(@RequestBody ConnectionSettingsUpdateRequest.PostgresSettings request,
                                               @RequestParam(required = false) String profileId) {
        return service.testPostgres(request, profileId);
    }

    @PostMapping("/test-redis")
    public ConnectionTestResponse testRedis(@RequestBody ConnectionSettingsUpdateRequest.RedisSettings request,
                                            @RequestParam(required = false) String profileId) {
        return service.testRedis(request, profileId);
    }
}
