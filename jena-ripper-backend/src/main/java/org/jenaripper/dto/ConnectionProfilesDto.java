package org.jenaripper.dto;

import java.util.List;

public record ConnectionProfilesDto(String activeProfileId, List<Profile> profiles) {
    public record Profile(
            String id,
            String name,
            boolean active,
            ConnectionSettingsDto.JenaSettings jena,
            ConnectionSettingsDto.PostgresSettings postgres,
            ConnectionSettingsDto.RedisSettings redis) {}
}
