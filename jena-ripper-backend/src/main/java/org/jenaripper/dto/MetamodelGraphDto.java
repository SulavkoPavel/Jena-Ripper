package org.jenaripper.dto;

import java.util.List;

public record MetamodelGraphDto(
        List<MetamodelClassDto> classes,
        List<MetamodelAssociationDto> associations) {

    public record MetamodelClassDto(
            String id,
            String name,
            String uri,
            String label,
            String labelRu,
            List<String> parents,
            List<String> children,
            Boolean show,
            Boolean enumeration,
            Boolean compound,
            Boolean cimDataType,
            Boolean dictionary,
            CimClassReference reference) {}

    public record MetamodelAssociationDto(
            String id,
            String name,
            String uri,
            String label,
            String labelRu,
            String sourceClassId,
            String targetClassId,
            String sourceRole,
            String targetRole,
            String range,
            List<String> ranges,
            List<MetamodelRangeNeedDto> rangesNeed,
            List<String> autoCreate,
            String dataType,
            String dataTypeInfo,
            Integer tableColumns,
            Boolean show) {}

    public record MetamodelRangeNeedDto(String range, Long need) {}
}
