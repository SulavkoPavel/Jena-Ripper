package org.jenaripper.dto;

import java.util.List;
import java.time.Instant;

public record ConnectionProfilesDto(String activeProfileId, List<Profile> profiles) {
    public record Profile(
            String id,
            String name,
            long version,
            Instant updatedAt,
            boolean active,
            ConnectionSettingsDto.JenaSettings jena,
            ConnectionSettingsDto.PostgresSettings postgres,
            ConnectionSettingsDto.RedisSettings redis) {}
}
