package org.jenaripper.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.jenaripper.dto.ConnectionProfileFormat;
import org.jenaripper.dto.ConnectionProfileTransfer;
import org.jenaripper.dto.ConnectionProfilesImportRequest;
import org.jenaripper.dto.ConnectionProfilesImportResult;
import org.jenaripper.settings.StoredConnectionProfiles;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ConnectionProfileImportService {
    private final ObjectMapper objectMapper;

    public ImportOutcome importProfiles(ConnectionProfilesImportRequest request, StoredConnectionProfiles localProfiles) {
        List<StoredConnectionProfiles.Profile> importedProfiles = parse(request == null ? null : request.document());
        List<ConnectionProfilesImportResult.Item> plan = plan(localProfiles, importedProfiles);
        Map<String, String> decisions = request == null || request.decisions() == null
                ? Map.of()
                : request.decisions();
        boolean unresolved = plan.stream().anyMatch(item ->
                !item.actions().isEmpty() && !decisions.containsKey(item.id()));
        if (unresolved) {
            return new ImportOutcome(false, 0, 0, 0, plan, localProfiles);
        }
        return apply(localProfiles, importedProfiles, plan, decisions);
    }

    private ImportOutcome apply(StoredConnectionProfiles localProfiles,
                                List<StoredConnectionProfiles.Profile> importedProfiles,
                                List<ConnectionProfilesImportResult.Item> plan,
                                Map<String, String> decisions) {
        List<StoredConnectionProfiles.Profile> mergedProfiles = new ArrayList<>(localProfiles.profiles());
        String activeProfileId = localProfiles.activeProfileId();
        int created = 0;
        int updated = 0;
        int skipped = 0;
        for (int index = 0; index < importedProfiles.size(); index++) {
            StoredConnectionProfiles.Profile candidate = importedProfiles.get(index);
            ConnectionProfilesImportResult.Item item = plan.get(index);
            ConnectionProfilesImportResult.Action action = importAction(candidate, item, decisions);
            switch (action) {
                case SKIP -> skipped++;
                case COPY -> {
                    mergedProfiles.add(copyOf(candidate, uniqueCopyName(mergedProfiles, candidate.name())));
                    created++;
                }
                case REPLACE_NAME -> {
                    activeProfileId = replaceByName(mergedProfiles, candidate, activeProfileId);
                    created++;
                }
                case CREATE -> {
                    mergedProfiles.add(candidate);
                    created++;
                }
                case UPDATE, REPLACE -> {
                    mergedProfiles.replaceAll(profile -> profile.id().equals(candidate.id()) ? candidate : profile);
                    updated++;
                }
            }
        }
        StoredConnectionProfiles result = new StoredConnectionProfiles(activeProfileId, mergedProfiles);
        return new ImportOutcome(true, created, updated, skipped, plan, result);
    }

    private static ConnectionProfilesImportResult.Action importAction(
            StoredConnectionProfiles.Profile candidate,
            ConnectionProfilesImportResult.Item item,
            Map<String, String> decisions) {
        String requestedAction = decisions.get(candidate.id());
        ConnectionProfilesImportResult.Action action = item.actions().isEmpty()
                ? defaultAction(item.status())
                : parseAction(requestedAction);
        if (action == null || (!item.actions().isEmpty() && !item.actions().contains(action))) {
            throw new IllegalArgumentException(
                    "Недопустимое действие импорта для профиля " + candidate.name() + ".");
        }
        return action;
    }

    private static String replaceByName(List<StoredConnectionProfiles.Profile> profiles,
                                        StoredConnectionProfiles.Profile candidate,
                                        String activeProfileId) {
        StoredConnectionProfiles.Profile replaced = profiles.stream()
                .filter(profile -> profile.name().equalsIgnoreCase(candidate.name()))
                .findFirst()
                .orElse(null);
        String resultingActiveId = replaced != null && replaced.id().equals(activeProfileId)
                ? candidate.id()
                : activeProfileId;
        profiles.removeIf(profile -> profile.id().equals(candidate.id())
                || profile.name().equalsIgnoreCase(candidate.name()));
        profiles.add(candidate);
        return resultingActiveId;
    }

    private List<StoredConnectionProfiles.Profile> parse(JsonNode document) {
        if (document == null || document.isNull()) {
            throw new IllegalArgumentException("Файл профилей пуст.");
        }
        try {
            List<ConnectionProfileTransfer> transfers = readTransfers(document);
            if (transfers.isEmpty()) {
                throw new IllegalArgumentException("В файле нет профилей.");
            }
            return validateTransfers(transfers);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Не удалось прочитать файл профилей.", exception);
        }
    }

    private List<ConnectionProfileTransfer> readTransfers(JsonNode document) throws Exception {
        if (!document.has("formatVersion")) {
            return List.of(objectMapper.treeToValue(document, ConnectionProfileTransfer.class));
        }
        if (document.path("formatVersion").asInt() != ConnectionProfileFormat.CURRENT_VERSION) {
            throw new IllegalArgumentException("Версия формата файла не поддерживается.");
        }
        if (!document.path("profiles").isArray()) {
            throw new IllegalArgumentException("В файле нет списка профилей.");
        }
        List<ConnectionProfileTransfer> transfers = new ArrayList<>();
        for (JsonNode profile : document.path("profiles")) {
            transfers.add(objectMapper.treeToValue(profile, ConnectionProfileTransfer.class));
        }
        return transfers;
    }

    private static List<StoredConnectionProfiles.Profile> validateTransfers(
            List<ConnectionProfileTransfer> transfers) {
        List<StoredConnectionProfiles.Profile> profiles = new ArrayList<>();
        Set<String> profileIds = new HashSet<>();
        for (ConnectionProfileTransfer transfer : transfers) {
            StoredConnectionProfiles.Profile profile = validateTransfer(transfer);
            if (!profileIds.add(profile.id())) {
                throw new IllegalArgumentException("Файл содержит повторяющийся UUID профиля.");
            }
            profiles.add(profile);
        }
        return profiles;
    }

    private static StoredConnectionProfiles.Profile validateTransfer(ConnectionProfileTransfer transfer) {
        if (transfer == null || transfer.connections() == null || transfer.connections().jena() == null
                || transfer.connections().postgres() == null || transfer.connections().redis() == null) {
            throw new IllegalArgumentException("Профиль содержит неполные настройки подключений.");
        }
        validateUuid(transfer.id());
        String profileName = validateName(transfer.name());
        if (transfer.version() < 1 || transfer.updatedAt() == null) {
            throw new IllegalArgumentException("Профиль содержит некорректную версию или дату обновления.");
        }
        return new StoredConnectionProfiles.Profile(
                transfer.id(), profileName, transfer.version(), transfer.updatedAt(),
                transfer.connections().jena(), transfer.connections().postgres(), transfer.connections().redis());
    }

    private static List<ConnectionProfilesImportResult.Item> plan(
            StoredConnectionProfiles localProfiles,
            List<StoredConnectionProfiles.Profile> importedProfiles) {
        List<ConnectionProfilesImportResult.Item> items = new ArrayList<>();
        for (StoredConnectionProfiles.Profile candidate : importedProfiles) {
            StoredConnectionProfiles.Profile sameId = findById(localProfiles, candidate.id());
            StoredConnectionProfiles.Profile sameName = findByNameWithDifferentId(localProfiles, candidate);
            if (sameName != null) {
                items.add(item(candidate, ConnectionProfilesImportResult.Status.NAME_CONFLICT,
                        "Профиль с таким названием имеет другой UUID.",
                        List.of(ConnectionProfilesImportResult.Action.COPY,
                                ConnectionProfilesImportResult.Action.REPLACE_NAME,
                                ConnectionProfilesImportResult.Action.SKIP)));
            } else if (sameId == null) {
                items.add(item(candidate, ConnectionProfilesImportResult.Status.NEW,
                        "Будет создан новый профиль.", List.of()));
            } else if (sameContent(sameId, candidate) && version(sameId) == version(candidate)) {
                items.add(item(candidate, ConnectionProfilesImportResult.Status.CURRENT,
                        "Профиль актуален.", List.of()));
            } else if (version(candidate) > version(sameId)) {
                items.add(item(candidate, ConnectionProfilesImportResult.Status.UPDATE,
                        "Найдена более новая версия профиля.",
                        List.of(ConnectionProfilesImportResult.Action.UPDATE,
                                ConnectionProfilesImportResult.Action.COPY,
                                ConnectionProfilesImportResult.Action.SKIP)));
            } else if (version(candidate) < version(sameId)) {
                items.add(item(candidate, ConnectionProfilesImportResult.Status.OLDER,
                        "Импортируемый профиль старше локального.",
                        List.of(ConnectionProfilesImportResult.Action.REPLACE,
                                ConnectionProfilesImportResult.Action.COPY,
                                ConnectionProfilesImportResult.Action.SKIP)));
            } else {
                items.add(item(candidate, ConnectionProfilesImportResult.Status.CONFLICT,
                        "Версии совпадают, но содержимое отличается.",
                        List.of(ConnectionProfilesImportResult.Action.REPLACE,
                                ConnectionProfilesImportResult.Action.COPY,
                                ConnectionProfilesImportResult.Action.SKIP)));
            }
        }
        return items;
    }

    private static StoredConnectionProfiles.Profile findById(StoredConnectionProfiles profiles, String profileId) {
        return profiles.profiles().stream()
                .filter(profile -> profile.id().equals(profileId))
                .findFirst()
                .orElse(null);
    }

    private static StoredConnectionProfiles.Profile findByNameWithDifferentId(
            StoredConnectionProfiles profiles, StoredConnectionProfiles.Profile candidate) {
        return profiles.profiles().stream()
                .filter(profile -> profile.name().equalsIgnoreCase(candidate.name())
                        && !profile.id().equals(candidate.id()))
                .findFirst()
                .orElse(null);
    }

    private static ConnectionProfilesImportResult.Item item(StoredConnectionProfiles.Profile profile,
                                                              ConnectionProfilesImportResult.Status status,
                                                              String message,
                                                              List<ConnectionProfilesImportResult.Action> actions) {
        return new ConnectionProfilesImportResult.Item(profile.id(), profile.name(), status, message, actions);
    }

    private static boolean sameContent(StoredConnectionProfiles.Profile left, StoredConnectionProfiles.Profile right) {
        return left.name().equals(right.name())
                && Objects.equals(left.jena(), right.jena())
                && Objects.equals(left.postgres(), right.postgres())
                && Objects.equals(left.redis(), right.redis());
    }

    private static StoredConnectionProfiles.Profile copyOf(StoredConnectionProfiles.Profile source, String name) {
        return new StoredConnectionProfiles.Profile(
                UUID.randomUUID().toString(), name, ConnectionProfileFormat.INITIAL_PROFILE_VERSION, Instant.now(),
                source.jena(), source.postgres(), source.redis());
    }

    private static String uniqueCopyName(List<StoredConnectionProfiles.Profile> profiles, String sourceName) {
        int suffix = 2;
        String candidate = sourceName + " (" + suffix + ")";
        while (containsName(profiles, candidate)) {
            suffix++;
            candidate = sourceName + " (" + suffix + ")";
        }
        return candidate;
    }

    private static boolean containsName(List<StoredConnectionProfiles.Profile> profiles, String name) {
        return profiles.stream().anyMatch(profile -> profile.name().equalsIgnoreCase(name));
    }

    private static ConnectionProfilesImportResult.Action defaultAction(ConnectionProfilesImportResult.Status status) {
        return status == ConnectionProfilesImportResult.Status.NEW
                ? ConnectionProfilesImportResult.Action.CREATE
                : ConnectionProfilesImportResult.Action.SKIP;
    }

    private static ConnectionProfilesImportResult.Action parseAction(String value) {
        if (value == null) return null;
        try {
            return ConnectionProfilesImportResult.Action.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static void validateUuid(String profileId) {
        try {
            UUID.fromString(profileId);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Профиль содержит некорректный UUID.");
        }
    }

    private static String validateName(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Введите название профиля.");
        }
        if (value.trim().length() > ConnectionProfileFormat.MAX_PROFILE_NAME_LENGTH) {
            throw new IllegalArgumentException("Название профиля не должно превышать 80 символов.");
        }
        return value.trim();
    }

    private static long version(StoredConnectionProfiles.Profile profile) {
        return profile.version() == null || profile.version() < 1 ? 1 : profile.version();
    }

    public record ImportOutcome(
            boolean applied,
            int created,
            int updated,
            int skipped,
            List<ConnectionProfilesImportResult.Item> items,
            StoredConnectionProfiles profiles) {
    }
}
