package org.jenaripper.dto;

import java.util.List;

public record RedisConsoleMetadataDto(
        boolean available,
        long datasetId,
        List<Command> commands,
        List<Template> templates) {
    public record Command(String name, String category, String accessLevel, String syntax, String description) {}
    public record Template(String id, String name, String description, String command) {}
}
