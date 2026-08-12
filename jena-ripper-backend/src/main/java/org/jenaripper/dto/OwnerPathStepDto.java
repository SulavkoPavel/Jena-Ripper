package org.jenaripper.dto;

public record OwnerPathStepDto(
        OwnerResourceDto from,
        String predicate,
        String predicateUri,
        String direction,
        OwnerResourceDto to) {
}
