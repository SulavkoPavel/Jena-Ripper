package org.jenaripper.dto;

import java.util.List;

public record OwnerRuleDto(String name, String conclusion, List<String> premises) {
}
