package org.jenaripper.controller;

import lombok.RequiredArgsConstructor;
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
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/settings/connections")
@RequiredArgsConstructor
public class ConnectionSettingsController {
    private final ConnectionSettingsService service;
    private final ObjectMapper objectMapper;

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

    @PostMapping("/profiles/{profileId}/activate")
    public ConnectionProfilesDto activateProfile(@PathVariable String profileId) {
        return service.activateProfile(profileId);
    }

    @GetMapping("/profiles/{profileId}/export")
    public ResponseEntity<byte[]> exportProfile(@PathVariable String profileId) throws Exception {
        ConnectionProfileTransfer profile = service.exportOne(profileId);
        return download(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(profile),
                "jena-ripper-profile-" + safeFileName(profile.name()) + ".json");
    }

    @GetMapping("/profiles/export")
    public ResponseEntity<byte[]> exportProfiles() throws Exception {
        return download(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(service.exportAll()),
                "jena-ripper-profiles.json");
    }

    @PostMapping("/profiles/import")
    public ConnectionProfilesImportResult importProfiles(@RequestBody ConnectionProfilesImportRequest request) {
        return service.importProfiles(request);
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

    private static ResponseEntity<byte[]> download(byte[] content, String fileName) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setContentDisposition(ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).body(content);
    }

    private static String safeFileName(String value) {
        String normalized = value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9а-яё]+", "-")
                .replaceAll("^-|-$", "");
        return normalized.isBlank() ? "profile" : normalized;
    }
}
