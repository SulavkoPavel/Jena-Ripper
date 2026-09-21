package org.jenaripper.settings;

import java.util.List;
import java.time.Instant;

public record StoredConnectionProfiles(String activeProfileId, List<Profile> profiles) {
    public record Profile(
            String id,
            String name,
            Long version,
            Instant updatedAt,
            StoredConnectionSettings.Jena jena,
            StoredConnectionSettings.Postgres postgres,
            StoredConnectionSettings.Redis redis) {
        public Profile(String id, String name, StoredConnectionSettings.Jena jena,
                       StoredConnectionSettings.Postgres postgres, StoredConnectionSettings.Redis redis) {
            this(id, name, 1L, null, jena, postgres, redis);
        }
    }
}
