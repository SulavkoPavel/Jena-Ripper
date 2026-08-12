package org.jenaripper.owner;

import org.jenaripper.dto.OwnerPathDto;
import org.jenaripper.dto.OwnerResourceDto;
import org.jenaripper.dto.OwnerRuleDto;

import java.util.List;

public record OwnerResolution(
        OwnerResourceDto resource,
        List<OwnerResourceDto> assetOwners,
        List<OwnerResourceDto> dataSources,
        List<OwnerRuleDto> assetOwnerMatchedRules,
        List<OwnerRuleDto> dataSourceMatchedRules,
        List<OwnerPathDto> assetOwnerPaths,
        List<OwnerPathDto> dataSourcePaths,
        List<OwnerResourceDto> owners,
        List<OwnerRuleDto> matchedRules,
        List<OwnerPathDto> paths) {
}
