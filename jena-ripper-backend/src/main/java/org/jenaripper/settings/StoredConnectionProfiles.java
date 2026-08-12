package org.jenaripper.settings;

import java.util.List;

public record StoredConnectionProfiles(String activeProfileId, List<Profile> profiles) {
    public record Profile(
            String id,
            String name,
            StoredConnectionSettings.Jena jena,
            StoredConnectionSettings.Postgres postgres,
            StoredConnectionSettings.Redis redis) {}
}
