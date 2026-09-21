package org.jenaripper.controller;

import lombok.RequiredArgsConstructor;
import org.jenaripper.dto.UploadedModelDto;
import org.jenaripper.service.UploadedModelService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
public class UploadedModelController {
    private final UploadedModelService service;

    @GetMapping
    public List<UploadedModelDto> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UploadedModelDto upload(@RequestPart("file") MultipartFile file) {
        return service.upload(file);
    }

    @PatchMapping("/{id}")
    public UploadedModelDto rename(@PathVariable String id, @RequestBody RenameUploadedModelRequest request) {
        return service.rename(id, request.name());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        service.delete(id);
    }

    public record RenameUploadedModelRequest(String name) {
    }
}
