package org.jenaripper.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.jenaripper.dto.UserDataDto;
import org.jenaripper.settings.UserDataRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class UserDataService {
    private static final int SPARQL_TEMPLATE_LIMIT = 100;
    private static final int REDIS_TEMPLATE_LIMIT = 50;
    private static final int HISTORY_LIMIT = 20;

    private final UserDataRepository repository;

    public synchronized UserDataDto current() {
        return repository.current();
    }

    public synchronized UserDataDto update(String area, String collection, List<JsonNode> values) {
        UserDataDto current = repository.current();
        List<JsonNode> safe = values == null ? List.of() : values;
        UserDataDto updated = switch (area + "/" + collection) {
            case "sparql/templates" -> new UserDataDto(1, limited(safe, SPARQL_TEMPLATE_LIMIT),
                    current.sparqlHistory(), current.redisTemplates(), current.redisHistory());
            case "sparql/history" -> new UserDataDto(1, current.sparqlTemplates(),
                    limited(safe, HISTORY_LIMIT), current.redisTemplates(), current.redisHistory());
            case "redis/templates" -> new UserDataDto(1, current.sparqlTemplates(),
                    current.sparqlHistory(), limited(safe, REDIS_TEMPLATE_LIMIT), current.redisHistory());
            case "redis/history" -> new UserDataDto(1, current.sparqlTemplates(),
                    current.sparqlHistory(), current.redisTemplates(), limited(safe, HISTORY_LIMIT));
            default -> throw new IllegalArgumentException("Неизвестная коллекция пользовательских данных.");
        };
        return repository.save(updated);
    }

    public synchronized UserDataDto migrate(UserDataDto legacy) {
        if (legacy == null) return repository.current();
        UserDataDto current = repository.current();
        return repository.save(new UserDataDto(1,
                mergeTemplates(current.sparqlTemplates(), legacy.sparqlTemplates(), "query", SPARQL_TEMPLATE_LIMIT),
                mergeHistory(current.sparqlHistory(), legacy.sparqlHistory()),
                mergeTemplates(current.redisTemplates(), legacy.redisTemplates(), "command", REDIS_TEMPLATE_LIMIT),
                mergeHistory(current.redisHistory(), legacy.redisHistory())));
    }

    private static List<JsonNode> mergeTemplates(List<JsonNode> current, List<JsonNode> legacy,
                                                  String contentField, int limit) {
        return merge(current, legacy, value -> {
            String id = text(value, "id");
            return !id.isBlank() ? "id:" + id : "content:" + text(value, "name") + "\u0000" + text(value, contentField);
        }, limit);
    }

    private static List<JsonNode> mergeHistory(List<JsonNode> current, List<JsonNode> legacy) {
        return merge(current, legacy, JsonNode::toString, HISTORY_LIMIT);
    }

    private static List<JsonNode> merge(List<JsonNode> current, List<JsonNode> legacy,
                                        Function<JsonNode, String> key, int limit) {
        Map<String, JsonNode> values = new LinkedHashMap<>();
        for (JsonNode value : concat(current, legacy)) {
            if (value != null && value.isObject()) values.putIfAbsent(key.apply(value), value);
            if (values.size() == limit) break;
        }
        return List.copyOf(values.values());
    }

    private static List<JsonNode> concat(List<JsonNode> first, List<JsonNode> second) {
        List<JsonNode> result = new ArrayList<>(first.size() + second.size());
        result.addAll(first);
        result.addAll(second);
        return result;
    }

    private static List<JsonNode> limited(List<JsonNode> values, int limit) {
        return List.copyOf(values.subList(0, Math.min(values.size(), limit)));
    }

    private static String text(JsonNode value, String field) {
        JsonNode node = value.get(field);
        return node == null || node.isNull() ? "" : node.asText();
    }
}
