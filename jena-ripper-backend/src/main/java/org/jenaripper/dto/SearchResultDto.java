package org.jenaripper.dto;

import java.util.List;

public record SearchResultDto(String uri, String compactUri, String label, List<String> types) {}

