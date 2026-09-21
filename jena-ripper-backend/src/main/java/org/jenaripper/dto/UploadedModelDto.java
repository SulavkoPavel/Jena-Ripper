package org.jenaripper.dto;

import java.time.Instant;

public record UploadedModelDto(
        String id,
        String name,
        String originalFileName,
        String storedFileName,
        long size,
        Instant uploadedAt,
        String status,
        String type,
        String error) {
}
