package org.jenaripper.dto;

public record NeighborStatsDto(
        String requestedUri,
        long incomingTotal,
        long outgoingTotal,
        int limit,
        boolean truncated) {}

