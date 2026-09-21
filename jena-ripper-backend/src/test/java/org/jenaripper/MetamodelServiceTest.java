package org.jenaripper;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jenaripper.config.JenaRipperProperties;
import org.jenaripper.config.RdfSourceProperties;
import org.jenaripper.dto.MetamodelGraphDto;
import org.jenaripper.owner.OwnerMetadataDataSource;
import org.jenaripper.owner.MetamodelRepository;
import org.jenaripper.remote.CimApiClient;
import org.jenaripper.service.MetamodelService;
import org.jenaripper.service.PrefixService;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MetamodelServiceTest {
    @Test
    void usesAllowedRangesAsConcreteTargetsAndSingularRangeAsFallback() throws Exception {
        JenaRipperProperties properties = mock(JenaRipperProperties.class);
        JenaRipperProperties.OwnerRules metadata = mock(JenaRipperProperties.OwnerRules.class);
        OwnerMetadataDataSource dataSource = mock(OwnerMetadataDataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement classesStatement = mock(PreparedStatement.class);
        PreparedStatement associationsStatement = mock(PreparedStatement.class);
        ResultSet classes = mock(ResultSet.class);
        ResultSet associations = mock(ResultSet.class);

        when(properties.ownerRules()).thenReturn(metadata);
        when(metadata.schema()).thenReturn("public");
        when(metadata.connectTimeout()).thenReturn(Duration.ofSeconds(5));
        when(dataSource.connection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(classesStatement, associationsStatement);
        when(classesStatement.executeQuery()).thenReturn(classes);
        when(associationsStatement.executeQuery()).thenReturn(associations);

        when(classes.next()).thenReturn(true, true, true, false);
        when(classes.getString("id")).thenReturn("cim:PowerTransformer", "cim:PowerTransformerEnd", "cim:UnknownReference");
        when(classes.getString("label")).thenReturn("Power transformer", "Transformer end");
        when(classes.getString("label_ru")).thenReturn((String) null, (String) null);
        when(classes.getString("parents")).thenReturn("[\"cim:Equipment\"]", "[]");
        when(classes.getString("children")).thenReturn("[]", "[]");

        when(associations.next()).thenReturn(true, true, false);
        when(associations.getLong("id")).thenReturn(42L, 43L);
        when(associations.getString("metamodel_class_id"))
                .thenReturn("cim:PowerTransformer", "cim:PowerTransformer");
        when(associations.getString("name")).thenReturn(
                "cim:PowerTransformer.PowerTransformerEnd", "cim:PowerTransformer.Self");
        when(associations.getString("ranges")).thenReturn(
                "[\"cim:PowerTransformerEnd\",\"cim:PowerTransformer\",\"cim:Missing\"]", "[]");
        when(associations.getString("range"))
                .thenReturn("cim:PowerTransformerEnd", "cim:PowerTransformer");
        when(associations.getString("ranges_need")).thenReturn(
                "[{\"range\":\"cim:PowerTransformerEnd\",\"need\":1}]", (String) null);
        when(associations.getString("inverse_role_name")).thenReturn(
                "cim:PowerTransformerEnd.PowerTransformer", "cim:PowerTransformer.SelfInverse");
        when(associations.getBoolean("show")).thenReturn(true);
        when(associations.wasNull()).thenReturn(false);

        ObjectMapper objectMapper = new ObjectMapper();
        MetamodelRepository repository = new MetamodelRepository(properties, dataSource, objectMapper);
        CimApiClient cimApiClient = mock(CimApiClient.class);
        RdfSourceProperties sourceProperties = new RdfSourceProperties(RdfSourceProperties.LOCAL_TDB2, null);
        MetamodelService service = new MetamodelService(
                repository, cimApiClient, sourceProperties, objectMapper, new PrefixService());
        MetamodelGraphDto graph = service.graph();

        assertThat(graph.classes()).extracting(MetamodelGraphDto.MetamodelClassDto::name)
                .containsExactly("PowerTransformer", "PowerTransformerEnd", "UnknownReference");
        assertThat(graph.classes().get(0).reference().titleRu()).isEqualTo("Силовой трансформатор");
        assertThat(graph.classes().get(2).reference()).isNull();
        assertThat(new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(graph))
                .path("classes").get(2).path("reference").isNull()).isTrue();
        assertThat(graph.associations()).hasSize(3);
        assertThat(graph.associations().get(0).targetClassId()).isEqualTo("cim:PowerTransformerEnd");
        assertThat(graph.associations().get(1).targetClassId()).isEqualTo("cim:PowerTransformer");
        assertThat(graph.associations().get(0).ranges())
                .containsExactly("cim:PowerTransformerEnd", "cim:PowerTransformer", "cim:Missing");
        assertThat(graph.associations().get(0).rangesNeed()).singleElement()
                .satisfies(need -> assertThat(need.need()).isEqualTo(1L));
        assertThat(graph.associations().get(2).sourceClassId())
                .isEqualTo(graph.associations().get(2).targetClassId());
        verify(connection).setReadOnly(true);
        verify(connection).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        verify(connection).setAutoCommit(false);
        verify(connection).commit();
        verifyNoInteractions(cimApiClient);
    }

    @Test
    void loadsMetamodelFromCimApiForRemoteSource() throws Exception {
        MetamodelRepository repository = mock(MetamodelRepository.class);
        CimApiClient cimApiClient = mock(CimApiClient.class);
        RdfSourceProperties sourceProperties = new RdfSourceProperties(RdfSourceProperties.CIM_API, null);
        CimApiClient.CimMetamodelAssociation association = new CimApiClient.CimMetamodelAssociation(
                42L, "cim:PowerTransformer.PowerTransformerEnd", "Transformer end", null,
                "cim:PowerTransformerEnd", true, List.of("cim:PowerTransformerEnd"),
                List.of(new CimApiClient.CimMetamodelRangeNeed("cim:PowerTransformerEnd", 1L)),
                null, null, null, List.of(), null);
        when(cimApiClient.metamodelClasses()).thenReturn(List.of(
                new CimApiClient.CimMetamodelClass(
                        "cim:PowerTransformer", "Power transformer", null, true, false,
                        false, false, false, List.of(), List.of(), List.of(), List.of(association)),
                new CimApiClient.CimMetamodelClass(
                        "cim:PowerTransformerEnd", "Transformer end", null, true, false,
                        false, false, false, List.of(), List.of(), List.of(), List.of())));

        ObjectMapper objectMapper = new ObjectMapper();
        MetamodelService service = new MetamodelService(
                repository, cimApiClient, sourceProperties, objectMapper, new PrefixService());
        MetamodelGraphDto graph = service.graph();

        assertThat(graph.classes()).extracting(MetamodelGraphDto.MetamodelClassDto::id)
                .containsExactly("cim:PowerTransformer", "cim:PowerTransformerEnd");
        assertThat(graph.associations()).singleElement().satisfies(value -> {
            assertThat(value.sourceClassId()).isEqualTo("cim:PowerTransformer");
            assertThat(value.targetClassId()).isEqualTo("cim:PowerTransformerEnd");
            assertThat(value.rangesNeed()).singleElement()
                    .satisfies(need -> assertThat(need.need()).isEqualTo(1L));
        });
        verifyNoInteractions(repository);
    }

    @Test
    void loadsClassAttributesFromPostgresForLocalSource() throws Exception {
        MetamodelRepository repository = mock(MetamodelRepository.class);
        CimApiClient cimApiClient = mock(CimApiClient.class);
        when(repository.attributes("cim:Substation")).thenReturn(List.of(
                new MetamodelRepository.MetamodelAttributeRow(
                        7L, "cim:IdentifiedObject.name", "Name", "Наименование", null,
                        null, null, null, 1, "STRING", null, true)));
        ObjectMapper objectMapper = new ObjectMapper();
        MetamodelService service = new MetamodelService(
                repository, cimApiClient,
                new RdfSourceProperties(RdfSourceProperties.LOCAL_TDB2, null),
                objectMapper, new PrefixService());

        assertThat(service.attributes("cim:Substation")).singleElement().satisfies(attribute -> {
            assertThat(attribute.id()).isEqualTo(7L);
            assertThat(attribute.name()).isEqualTo("cim:IdentifiedObject.name");
            assertThat(attribute.uri()).endsWith("#IdentifiedObject.name");
            assertThat(attribute.labelRu()).isEqualTo("Наименование");
            assertThat(attribute.dataType()).isEqualTo("STRING");
        });
        verify(repository).attributes("cim:Substation");
        verifyNoInteractions(cimApiClient);
    }

    @Test
    void loadsClassAttributesLazilyFromCimApiForRemoteSource() throws Exception {
        MetamodelRepository repository = mock(MetamodelRepository.class);
        CimApiClient cimApiClient = mock(CimApiClient.class);
        CimApiClient.CimMetamodelAttribute attribute = new CimApiClient.CimMetamodelAttribute(
                9L, "cim:IdentifiedObject.mRID", "Master resource identifier", "Идентификатор",
                null, null, null, null, 2, "STRING", null, true);
        when(cimApiClient.metamodelClass("cim:Substation")).thenReturn(
                new CimApiClient.CimMetamodelClass(
                        "cim:Substation", "Substation", "Подстанция", true, false,
                        false, false, false, List.of(), List.of(), List.of(attribute), List.of()));
        ObjectMapper objectMapper = new ObjectMapper();
        MetamodelService service = new MetamodelService(
                repository, cimApiClient,
                new RdfSourceProperties(RdfSourceProperties.CIM_API, null),
                objectMapper, new PrefixService());

        assertThat(service.attributes("cim:Substation")).singleElement().satisfies(value -> {
            assertThat(value.id()).isEqualTo(9L);
            assertThat(value.labelRu()).isEqualTo("Идентификатор");
            assertThat(value.dataType()).isEqualTo("STRING");
        });
        verify(cimApiClient).metamodelClass("cim:Substation");
        verifyNoInteractions(repository);
    }
}
