package org.jenaripper.dto;

import java.util.List;

public record GraphResponseDto(List<GraphNodeDto> nodes, List<GraphEdgeDto> edges, NeighborStatsDto stats) {}
