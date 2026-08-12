package org.jenaripper.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DatasetStatusDtoTest {
    @Test
    void localDatasetAdvertisesSparqlOwnerRulesWhenOwnerRulesAreAvailable() {
        DatasetStatusDto.FeaturesDto features = new DatasetStatusDto.FeaturesDto(true, true, true);

        assertTrue(features.ownerRules());
        assertTrue(features.sparqlOwnerRules());
    }
}
