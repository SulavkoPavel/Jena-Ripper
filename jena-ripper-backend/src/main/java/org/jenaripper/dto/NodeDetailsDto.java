package org.jenaripper.dto;

import java.util.List;

public record NodeDetailsDto(
        String uri,
        String compactUri,
        String label,
        String localName,
        String mrid,
        List<String> types,
        String nodeType,
        List<RdfPropertyDto> properties,
        long incomingCount,
        long outgoingCount,
        boolean propertiesTruncated) {}

