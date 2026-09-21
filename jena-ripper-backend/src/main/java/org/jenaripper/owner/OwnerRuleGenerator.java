package org.jenaripper.owner;

import lombok.RequiredArgsConstructor;
import org.apache.jena.reasoner.rulesys.Rule;
import org.jenaripper.service.PrefixService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class OwnerRuleGenerator {
    private static final Set<String> ASSOCIATION_DATA_TYPES = Set.of("OBJECT_ENUM", "MULTI_OBJECT_ENUM");
    private static final String EQUIPMENTS = "cim:EquipmentContainer.Equipments";
    private static final List<String> ROOT_CLASSES = List.of("cim:Plant", "cim:Line");

    private final OwnerMetadataSource metadataSource;
    private final PrefixService prefixService;
    private volatile OwnerRuleBundle cached;

    public OwnerRuleBundle rules() {
        OwnerRuleBundle result = cached;
        if (result != null) return result;
        synchronized (this) {
            if (cached == null) cached = generate();
            return cached;
        }
    }

    public synchronized void invalidate() {
        cached = null;
    }

    private OwnerRuleBundle generate() {
        List<Rule> direct = loadRules("has-direct-assets.rules");
        List<Rule> bottomMain = loadRules("owners-to-bottom-main.rules");
        List<Rule> all = new ArrayList<>();
        all.addAll(bottomMain);
        all.addAll(generatedRules(bottomMain.size() + 1));
        all.addAll(loadRules("data-source-to-bottom-main.rules"));
        all.addAll(loadRules("data-source-to-top.rules"));
        all.addAll(loadRules("owners-to-top.rules"));
        return new OwnerRuleBundle(List.copyOf(direct), List.copyOf(all));
    }

    private List<Rule> generatedRules(int startNumber) {
        List<String> bodies = generateRecursive(ROOT_CLASSES, new HashSet<>());
        List<String> wrapped = new ArrayList<>();
        String wrapper = resource("wrap-rule-template.txt");
        for (int index = 0; index < bodies.size(); index++) {
            wrapped.add(wrapper
                    .replace("{{RULE_NUM}}", "%03d".formatted(index + startNumber))
                    .replace("{{RULE}}", bodies.get(index)));
        }
        return Rule.parseRules(prefixes() + String.join("\n\n", wrapped));
    }

    private List<String> generateRecursive(List<String> classIds, Set<String> visited) {
        LinkedList<String> result = new LinkedList<>();
        for (String classId : classIds) {
            if (!visited.add(classId)) continue;
            List<String> ranges = new ArrayList<>();
            for (OwnerAssociation association : metadataSource.findVisibleByClass(classId)) {
                if ((association.dataType() != null && ASSOCIATION_DATA_TYPES.contains(association.dataType()))
                        || association.ranges().isEmpty()) continue;
                addLastUnique(result, rulesFor(association));
                ranges.addAll(association.ranges());
            }
            addLastUnique(result, generateRecursive(ranges, visited));
        }
        return result;
    }

    private List<String> rulesFor(OwnerAssociation association) {
        if (association.inverseRoleName() == null || association.inverseRoleName().isBlank()) return List.of();
        if (EQUIPMENTS.equals(association.name())) {
            return List.of(
                    resource("owners-to-bottom-template-equipment.txt")
                            .replace("{{CLASS_TYPE}}", association.classId()),
                    resource("dataSource-to-bottom-template-equipment.txt")
                            .replace("{{CLASS_TYPE}}", association.classId()));
        }
        if (EQUIPMENTS.equals(association.inverseRoleName())) return List.of();
        return List.of(
                template("owners-to-bottom-template1.txt", association),
                template("owners-to-bottom-template2.txt", association),
                template("data-source-to-bottom-template1.txt", association),
                template("data-source-to-bottom-template2.txt", association));
    }

    private String template(String name, OwnerAssociation association) {
        return resource(name)
                .replace("{{ASSOC_NAME}}", association.name())
                .replace("{{INVERSE_ROLE}}", association.inverseRoleName());
    }

    private static void addLastUnique(List<String> target, List<String> values) {
        for (String value : values) {
            target.remove(value);
            target.add(value);
        }
    }

    private List<Rule> loadRules(String name) {
        return Rule.parseRules(resource(name));
    }

    private String prefixes() {
        StringBuilder result = new StringBuilder();
        prefixService.prefixes().forEach((prefix, uri) -> result
                .append("@prefix ").append(prefix).append(": <").append(uri).append(">.\n"));
        return result.append('\n').toString();
    }

    private static String resource(String name) {
        try {
            return new ClassPathResource("jena-rules/" + name).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load Owner Rules resource " + name, exception);
        }
    }
}
