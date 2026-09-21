package org.jenaripper.dto;

import org.jenaripper.settings.StoredConnectionSettings;

import java.time.Instant;

public record ConnectionProfileTransfer(
        String id,
        String name,
        long version,
        Instant updatedAt,
        Connections connections) {
    public record Connections(
            StoredConnectionSettings.Jena jena,
            StoredConnectionSettings.Postgres postgres,
            StoredConnectionSettings.Redis redis) {}
}
