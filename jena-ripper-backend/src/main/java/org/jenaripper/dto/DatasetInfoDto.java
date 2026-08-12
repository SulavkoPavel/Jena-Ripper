package org.jenaripper.dto;

import java.util.List;

public record DatasetInfoDto(String type, String path, long statementCount, List<String> namedGraphs) {}

