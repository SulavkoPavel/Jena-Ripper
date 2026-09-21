package org.jenaripper.dto;

public record MetamodelAttributeDto(
        long id,
        String name,
        String uri,
        String label,
        String labelRu,
        String type,
        Float factor,
        String unit,
        String range,
        Integer orderNum,
        String dataType,
        String dataTypeInfo,
        Boolean show) {
}
