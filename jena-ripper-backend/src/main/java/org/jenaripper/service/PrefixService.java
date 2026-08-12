package org.jenaripper.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

@Service
public class PrefixService {
    private final Map<String, String> prefixes;

    public PrefixService() {
        Properties loaded = new Properties();
        try (InputStreamReader reader = new InputStreamReader(
                new ClassPathResource("prefixes.properties").getInputStream(), StandardCharsets.UTF_8)) {
            loaded.load(reader);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load prefixes.properties", exception);
        }
        Map<String, String> sorted = new LinkedHashMap<>();
        loaded.stringPropertyNames().stream().sorted().forEach(prefix -> sorted.put(prefix, loaded.getProperty(prefix)));
        this.prefixes = Collections.unmodifiableMap(sorted);
    }

    public String compact(String uri) {
        if (uri == null) return null;
        return findPrefix(uri)
                .map(prefix -> prefix + ":" + uri.substring(prefixes.get(prefix).length()))
                .orElse(uri);
    }

    public String expand(String value) {
        if (value == null) return null;
        int separator = value.indexOf(':');
        if (separator <= 0) return value;
        String namespace = prefixes.get(value.substring(0, separator));
        return namespace == null ? value : namespace + value.substring(separator + 1);
    }

    public Optional<String> findPrefix(String uri) {
        if (uri == null) return Optional.empty();
        return prefixes.entrySet().stream()
                .filter(entry -> uri.startsWith(entry.getValue()))
                .max(Comparator.comparingInt(entry -> entry.getValue().length()))
                .map(Map.Entry::getKey);
    }

    public Map<String, String> prefixes() {
        return prefixes;
    }
}
