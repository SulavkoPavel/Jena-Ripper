package org.jenaripper.service;

import org.jenaripper.exception.MetamodelUnavailableException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jenaripper.config.RdfSourceProperties;
import org.jenaripper.dto.CimClassReference;
import org.jenaripper.dto.MetamodelAttributeDto;
import org.jenaripper.dto.MetamodelGraphDto;
import org.jenaripper.owner.MetamodelRepository;
import org.jenaripper.remote.CimApiClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class MetamodelService {
    private final MetamodelRepository repository;
    private final CimApiClient cimApiClient;
    private final RdfSourceProperties sourceProperties;
    private final PrefixService prefixService;
    private final Map<String, CimClassReference> classReferences;

    public MetamodelService(MetamodelRepository repository,
                            CimApiClient cimApiClient,
                            RdfSourceProperties sourceProperties,
                            ObjectMapper objectMapper,
                            PrefixService prefixService) throws IOException {
        this.repository = repository;
        this.cimApiClient = cimApiClient;
        this.sourceProperties = sourceProperties;
        this.prefixService = prefixService;
        try (InputStream input = new ClassPathResource("cim-class-reference.json").getInputStream()) {
            this.classReferences = Map.copyOf(objectMapper.readValue(
                    input, new TypeReference<Map<String, CimClassReference>>() {}));
        }
    }

    public MetamodelGraphDto graph() {
        MetamodelRepository.MetamodelData metamodel = sourceProperties.remote()
                ? remoteMetamodel()
                : repository.load();
        List<MetamodelGraphDto.MetamodelClassDto> classes = metamodel.classes().stream()
                .map(this::metamodelClass)
                .toList();
        Set<String> classIds = new LinkedHashSet<>();
        classes.forEach(metamodelClass -> classIds.add(metamodelClass.id()));
        return new MetamodelGraphDto(
                classes,
                associations(metamodel.associations(), classIds));
    }

    public List<MetamodelAttributeDto> attributes(String classId) {
        if (sourceProperties.remote()) {
            return values(cimApiClient.metamodelClass(classId).attributes()).stream()
                    .map(this::metamodelAttribute)
                    .toList();
        }
        return repository.attributes(classId).stream()
                .map(this::metamodelAttribute)
                .toList();
    }

    private MetamodelRepository.MetamodelData remoteMetamodel() {
        List<MetamodelRepository.MetamodelClassRow> classes = new ArrayList<>();
        List<MetamodelRepository.MetamodelAssociationRow> associations = new ArrayList<>();
        for (CimApiClient.CimMetamodelClass metamodelClass : cimApiClient.metamodelClasses()) {
            classes.add(new MetamodelRepository.MetamodelClassRow(
                    metamodelClass.id(),
                    metamodelClass.label(),
                    metamodelClass.labelRu(),
                    values(metamodelClass.parents()),
                    values(metamodelClass.children()),
                    metamodelClass.show(),
                    metamodelClass.enumeration(),
                    metamodelClass.compound(),
                    metamodelClass.isCimDataType(),
                    metamodelClass.dictionary()));
            for (CimApiClient.CimMetamodelAssociation association : values(metamodelClass.associations())) {
                List<MetamodelRepository.RangeNeedRow> rangesNeed = values(association.rangesNeed()).stream()
                        .filter(value -> value.range() != null && value.need() != null)
                        .map(value -> new MetamodelRepository.RangeNeedRow(value.range(), value.need()))
                        .toList();
                associations.add(new MetamodelRepository.MetamodelAssociationRow(
                        association.id() == null ? 0L : association.id(),
                        metamodelClass.id(),
                        association.name(),
                        association.label(),
                        association.labelRu(),
                        association.range(),
                        values(association.ranges()),
                        rangesNeed,
                        values(association.autoCreate()),
                        association.dataType(),
                        association.dataTypeInfo(),
                        association.isTable(),
                        association.inverseRoleName(),
                        association.show()));
            }
        }
        return new MetamodelRepository.MetamodelData(List.copyOf(classes), List.copyOf(associations));
    }

    private MetamodelAttributeDto metamodelAttribute(MetamodelRepository.MetamodelAttributeRow row) {
        return new MetamodelAttributeDto(
                row.databaseId(), row.name(), prefixService.expand(row.name()), row.label(), row.labelRu(),
                row.type(), row.factor(), row.unit(), row.range(), row.orderNum(), row.dataType(),
                row.dataTypeInfo(), row.show());
    }

    private MetamodelAttributeDto metamodelAttribute(CimApiClient.CimMetamodelAttribute row) {
        return new MetamodelAttributeDto(
                row.id() == null ? 0L : row.id(), row.name(), prefixService.expand(row.name()), row.label(),
                row.labelRu(), row.type(), row.factor(), row.unit(), row.range(), row.orderNum(),
                row.dataType(), row.dataTypeInfo(), row.show());
    }

    private static <T> List<T> values(List<T> values) {
        return values == null ? List.of() : values;
    }

    private MetamodelGraphDto.MetamodelClassDto metamodelClass(MetamodelRepository.MetamodelClassRow row) {
        String expandedId = prefixService.expand(row.id());
        return new MetamodelGraphDto.MetamodelClassDto(
                row.id(),
                localName(row.id()),
                expandedId,
                row.label(),
                row.labelRu(),
                row.parents(),
                row.children(),
                row.show(),
                row.enumeration(),
                row.compound(),
                row.cimDataType(),
                row.dictionary(),
                classReferences.get(expandedId));
    }

    private List<MetamodelGraphDto.MetamodelAssociationDto> associations(
            List<MetamodelRepository.MetamodelAssociationRow> rows,
            Set<String> classIds) {
        List<MetamodelGraphDto.MetamodelAssociationDto> associations = new ArrayList<>();
        for (MetamodelRepository.MetamodelAssociationRow row : rows) {
            List<String> targets = targets(row);
            for (String target : new LinkedHashSet<>(targets)) {
                if (classIds.contains(row.source()) && classIds.contains(target)) {
                    associations.add(metamodelAssociation(row, target, targets.size()));
                }
            }
        }
        return List.copyOf(associations);
    }

    private MetamodelGraphDto.MetamodelAssociationDto metamodelAssociation(
            MetamodelRepository.MetamodelAssociationRow row,
            String target,
            int targetCount) {
        List<MetamodelGraphDto.MetamodelRangeNeedDto> rangeNeeds = row.rangeNeeds().stream()
                .map(value -> new MetamodelGraphDto.MetamodelRangeNeedDto(value.range(), value.need()))
                .toList();
        return new MetamodelGraphDto.MetamodelAssociationDto(
                targetCount == 1 ? String.valueOf(row.databaseId()) : row.databaseId() + ":" + target,
                row.name(),
                prefixService.expand(row.name()),
                row.label(),
                row.labelRu(),
                row.source(),
                target,
                row.name(),
                row.inverseRoleName(),
                row.range(),
                row.ranges(),
                rangeNeeds,
                row.autoCreate(),
                row.dataType(),
                row.dataTypeInfo(),
                row.table(),
                row.show());
    }

    private static List<String> targets(MetamodelRepository.MetamodelAssociationRow row) {
        if (Boolean.TRUE.equals(row.show()) && !row.ranges().isEmpty()) {
            return row.ranges();
        }
        if (row.range() == null || row.range().isBlank()) {
            return row.ranges();
        }
        return List.of(row.range());
    }

    private static String localName(String value) {
        if (value == null) return "";
        int hash = value.lastIndexOf('#');
        int slash = value.lastIndexOf('/');
        int colon = value.lastIndexOf(':');
        int separator = Math.max(Math.max(hash, slash), colon);
        return separator >= 0 ? value.substring(separator + 1) : value;
    }
}
