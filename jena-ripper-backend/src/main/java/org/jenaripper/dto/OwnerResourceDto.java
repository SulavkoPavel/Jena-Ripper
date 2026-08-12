package org.jenaripper.dto;

import java.util.List;

public record OwnerResourceDto(String uri, String compactUri, String label, List<String> classes) {
}
