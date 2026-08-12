package org.jenaripper.dto;

import java.util.List;

public record OwnerRulesResponse(
        OwnerResourceDto resource,
        List<OwnerResourceDto> assetOwners,
        List<OwnerResourceDto> dataSources,
        List<OwnerRuleDto> assetOwnerMatchedRules,
        List<OwnerRuleDto> dataSourceMatchedRules,
        List<OwnerPathDto> assetOwnerPaths,
        List<OwnerPathDto> dataSourcePaths,
        List<OwnerResourceDto> owners,
        List<OwnerRuleDto> matchedRules,
        List<OwnerPathDto> paths,
        long executionTimeMs,
        String message) {
}
