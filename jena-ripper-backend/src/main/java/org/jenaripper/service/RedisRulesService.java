package org.jenaripper.service;

import org.jenaripper.config.JenaRipperProperties;
import org.jenaripper.config.RdfSourceProperties;
import org.jenaripper.dto.OwnerResourceDto;
import org.jenaripper.dto.RedisKeyDto;
import org.jenaripper.dto.RedisPermissionsDto;
import org.jenaripper.dto.RedisRulesResponse;
import org.jenaripper.dto.GraphNodeDto;
import org.jenaripper.redis.RedisPermissionKeyFactory;
import org.jenaripper.redis.RedisPermissionReader;
import org.jenaripper.redis.RedisRulesUnavailableException;
import org.jenaripper.remote.CimApiClient;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class RedisRulesService {
    private final RedisPermissionReader reader;
    private final GraphService graphService;
    private final PrefixService prefixService;
    private final JenaRipperProperties properties;
    private final RdfSourceProperties source;
    private final CimApiClient cimApi;

    @Autowired
    public RedisRulesService(RedisPermissionReader reader, GraphService graphService, PrefixService prefixService,
                             JenaRipperProperties properties, RdfSourceProperties source, CimApiClient cimApi) {
        this.reader = reader;
        this.graphService = graphService;
        this.prefixService = prefixService;
        this.properties = properties;
        this.source = source;
        this.cimApi = cimApi;
    }

    public RedisRulesService(RedisPermissionReader reader, GraphService graphService, PrefixService prefixService,
                             JenaRipperProperties properties) {
        this(reader, graphService, prefixService, properties, new RdfSourceProperties("LOCAL_TDB2", null), null);
    }

    public RedisRulesResponse inspect(String input) {
        JenaRipperProperties.RedisRules config = properties.redisRules();
        if (config == null || !config.enabled()) {
            throw new RedisRulesUnavailableException("Redis Rules отключены в конфигурации.", false, null);
        }
        String uri = graphService.resolveResource(input);
        String objectId = prefixService.compact(uri);
        long started = System.nanoTime();
        LinkedHashMap<String, RedisKeyDto> raw = new LinkedHashMap<>();

        List<Long> inherited = config.additionalDatasetIds() == null ? List.of() : config.additionalDatasetIds();
        Set<String> read = new LinkedHashSet<>();
        Set<String> readTop = new LinkedHashSet<>();
        for (Long datasetId : inherited) {
            read.addAll(read(raw, RedisPermissionKeyFactory.key(datasetId, RedisPermissionKeyFactory.READ, objectId), "READ inherited"));
            readTop.addAll(read(raw, RedisPermissionKeyFactory.key(datasetId, RedisPermissionKeyFactory.READ_TOP, objectId), "READ TOP inherited"));
        }
        for (String key : RedisPermissionKeyFactory.contextualKeys(
                config.datasetId(), config.diffId(), config.modelType(), RedisPermissionKeyFactory.READ, objectId)) {
            read.addAll(read(raw, key, "READ"));
        }
        for (String key : RedisPermissionKeyFactory.contextualKeys(
                config.datasetId(), config.diffId(), config.modelType(), RedisPermissionKeyFactory.READ_TOP, objectId)) {
            readTop.addAll(read(raw, key, "READ TOP"));
        }

        Set<String> writeCandidates = new LinkedHashSet<>(read);
        writeCandidates.addAll(readTop);
        Set<String> write = new LinkedHashSet<>();
        for (String companyId : writeCandidates) {
            boolean allowed = false;
            for (Long datasetId : inherited) {
                String key = RedisPermissionKeyFactory.key(datasetId, RedisPermissionKeyFactory.WRITE, companyId);
                allowed |= contains(raw, key, objectId, "WRITE inherited");
            }
            for (String key : RedisPermissionKeyFactory.contextualKeys(
                    config.datasetId(), config.diffId(), config.modelType(), RedisPermissionKeyFactory.WRITE, companyId)) {
                allowed |= contains(raw, key, objectId, "WRITE");
            }
            if (allowed) write.add(companyId);
        }

        if ("PIM_DIFF".equalsIgnoreCase(config.modelType())) {
            Set<String> reverseRead = reverseMembers(raw, config, objectId, RedisPermissionKeyFactory.READ, "REVERSE READ");
            Set<String> reverseTop = reverseMembers(raw, config, objectId, RedisPermissionKeyFactory.READ_TOP, "REVERSE READ TOP");
            read.removeAll(reverseRead);
            readTop.removeAll(reverseTop);
            for (String companyId : new ArrayList<>(write)) {
                boolean reversed = false;
                for (String key : RedisPermissionKeyFactory.contextualKeys(config.datasetId(), config.diffId(),
                        config.modelType(), RedisPermissionKeyFactory.REVERSE + RedisPermissionKeyFactory.WRITE, companyId)) {
                    reversed |= contains(raw, key, objectId, "REVERSE WRITE");
                }
                if (reversed) write.remove(companyId);
            }
        }

        RedisPermissionsDto permissions = new RedisPermissionsDto(resources(read), resources(readTop), resources(write));
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        boolean empty = read.isEmpty() && readTop.isEmpty() && write.isEmpty();
        return new RedisRulesResponse(resource(uri), config.datasetId(), config.modelType(), permissions,
                List.copyOf(raw.values()), elapsed,
                empty ? "Для этого объекта в Redis нет прав/владельцев." : null);
    }

    private RedisRulesResponse inspectRemote(String input) {
        if (cimApi == null) {
            throw new RedisRulesUnavailableException("Redis API CIM App недоступен.", false, null);
        }
        String uri = graphService.resolveResource(input);
        CimApiClient.RedisRulesResult remote = cimApi.redisRules(uri);
        CimApiClient.RedisRulesPermissions permissions = remote.permissions();
        List<String> read = permissions == null || permissions.read() == null ? List.of() : permissions.read();
        List<String> readTop = permissions == null || permissions.readTop() == null ? List.of() : permissions.readTop();
        List<String> write = permissions == null || permissions.write() == null ? List.of() : permissions.write();
        List<RedisKeyDto> technicalKeys = remote.technicalKeys() == null ? List.of() : remote.technicalKeys().stream()
                .map(key -> new RedisKeyDto(key.key(), key.role(), key.type(), key.size()))
                .toList();
        boolean empty = read.isEmpty() && readTop.isEmpty() && write.isEmpty();
        return new RedisRulesResponse(
                resource(uri),
                remote.datasetId(),
                remote.modelType(),
                new RedisPermissionsDto(resources(read), resources(readTop), resources(write)),
                technicalKeys,
                remote.executionTimeMs(),
                empty ? "Для этого объекта в Redis нет прав/владельцев." : null);
    }

    private Set<String> reverseMembers(LinkedHashMap<String, RedisKeyDto> raw, JenaRipperProperties.RedisRules config,
                                       String objectId, String prefix, String role) {
        Set<String> values = new LinkedHashSet<>();
        for (String key : RedisPermissionKeyFactory.contextualKeys(config.datasetId(), config.diffId(), config.modelType(),
                RedisPermissionKeyFactory.REVERSE + prefix, objectId)) {
            values.addAll(read(raw, key, role));
        }
        return values;
    }

    private Set<String> read(LinkedHashMap<String, RedisKeyDto> raw, String key, String role) {
        Set<String> values = reader.members(key);
        raw.put(key, new RedisKeyDto(key, role, "SET", values.size()));
        return values;
    }

    private boolean contains(LinkedHashMap<String, RedisKeyDto> raw, String key, String member, String role) {
        boolean found = reader.contains(key, member);
        raw.put(key, new RedisKeyDto(key, role, "SET", reader.size(key)));
        return found;
    }

    private List<OwnerResourceDto> resources(Collection<String> ids) {
        return ids.stream().map(this::resource).sorted(Comparator.comparing(OwnerResourceDto::label)).toList();
    }

    private OwnerResourceDto resource(String id) {
        try {
            GraphNodeDto node = graphService.node(id);
            return new OwnerResourceDto(node.uri(), node.compactUri(), node.label(), node.types());
        } catch (RuntimeException ignored) {
            String expanded = prefixService.expand(id);
            return new OwnerResourceDto(expanded, id, id, List.of());
        }
    }
}
