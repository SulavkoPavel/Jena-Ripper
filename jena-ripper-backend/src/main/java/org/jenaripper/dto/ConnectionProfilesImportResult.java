package org.jenaripper.dto;

import java.util.List;

public record ConnectionProfilesImportResult(boolean applied, int created, int updated, int skipped,
                                             List<Item> items, ConnectionProfilesDto profiles) {
    public enum Status {
        NAME_CONFLICT,
        NEW,
        CURRENT,
        UPDATE,
        OLDER,
        CONFLICT
    }

    public enum Action {
        CREATE,
        UPDATE,
        REPLACE,
        COPY,
        REPLACE_NAME,
        SKIP
    }

    public record Item(String id, String name, Status status, String message, List<Action> actions) {}
}
