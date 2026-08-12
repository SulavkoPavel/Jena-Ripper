package org.jenaripper.dto;

import java.util.List;
public record GraphNodeDto(
        String id,
        String uri,
        String compactUri,
        String label,
        String localName,
        List<String> types,
        String nodeType) {}
