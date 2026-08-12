package org.jenaripper.dto;

import java.util.List;

public record RedisPermissionsDto(
        List<OwnerResourceDto> read,
        List<OwnerResourceDto> readTop,
        List<OwnerResourceDto> write) {
}
