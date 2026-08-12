package org.jenaripper.dto;

import java.util.List;

public record OwnerPathDto(String ownerUri, List<OwnerPathStepDto> steps) {
}
