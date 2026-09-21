package org.jenaripper;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.jenaripper.config.JenaRipperProperties;
import org.jenaripper.owner.OwnerAssociation;
import org.jenaripper.owner.OwnerMetadataSource;
import org.jenaripper.owner.OwnerRuleGenerator;
import org.jenaripper.owner.OwnerRulesResolver;
import org.jenaripper.owner.OwnerRulesModelReasoner;
import org.jenaripper.exception.OwnerRulesUnavailableException;
import org.jenaripper.owner.OwnerResolution;
import org.jenaripper.service.GraphMapper;
import org.jenaripper.service.PrefixService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OwnerRulesResolverTest {
    private static final String CIM = "http://iec.ch/TC57/CIM100#";
    private static final String RF = "http://gost.ru/2019/schema-cim01#";
    private static final String UPS = "https://cim.so-ups.ru#";
    private static final String TRANSFORMER = UPS + "_transformer";
    private static final String SUBSTATION = UPS + "_substation";

    @Test
    void resolvesOwnerWithTheSameGeneratedAssociationRulesAndReturnsPath() {
        Model model = ownerGraph(2, 0, false);
        OwnerRulesResolver resolver = resolver(metadata());
        OwnerResolution resolution = resolver.resolve(model, TRANSFORMER);

        assertThat(resolution.owners()).extracting(owner -> owner.label())
                .containsExactlyInAnyOrder("Owner 1", "Owner 2");
        assertThat(resolution.assetOwners()).hasSize(2);
        assertThat(resolution.dataSources()).isEmpty();
        assertThat(resolution.matchedRules()).isNotEmpty();
        assertThat(resolution.paths()).hasSize(2);
        assertThat(resolution.paths().get(0).steps())
                .extracting(step -> step.predicate())
                .contains("cim:Equipment.EquipmentContainer", "rf:IdentifiedObject.OrganisationRoles",
                        "cim:OrganisationRole.Organisation");
        assertThat(resolver.resolve(model, TRANSFORMER).owners()).hasSize(2);
    }

    @Test
    void returnsNormalEmptyResultWhenOwnerDoesNotExist() {
        OwnerResolution resolution = resolver(metadata()).resolve(ownerGraph(0, 0, false), TRANSFORMER);
        assertThat(resolution.assetOwners()).isEmpty();
        assertThat(resolution.dataSources()).isEmpty();
        assertThat(resolution.owners()).isEmpty();
        assertThat(resolution.matchedRules()).isEmpty();
        assertThat(resolution.paths()).isEmpty();
    }

    @Test
    void returnsAssetOwnerAndDataSourceAsIndependentResults() {
        OwnerResolution resolution = resolver(metadata()).resolve(ownerGraph(1, 1, false), TRANSFORMER);

        assertThat(resolution.assetOwners()).extracting(owner -> owner.label()).containsExactly("Owner 1");
        assertThat(resolution.dataSources()).extracting(owner -> owner.label()).containsExactly("Data Source 1");
    }

    @Test
    void keepsBothRolesWhenAssetOwnerAndDataSourceUrisAreEqual() {
        OwnerResolution resolution = resolver(metadata()).resolve(ownerGraph(1, 1, true), TRANSFORMER);

        assertThat(resolution.assetOwners()).extracting(owner -> owner.uri()).containsExactly(UPS + "_owner1");
        assertThat(resolution.dataSources()).extracting(owner -> owner.uri()).containsExactly(UPS + "_owner1");
    }

    @Test
    void doesNotLoseDataSourceWhenAssetOwnerIsMissing() {
        OwnerResolution resolution = resolver(metadata()).resolve(ownerGraph(0, 1, false), TRANSFORMER);

        assertThat(resolution.assetOwners()).isEmpty();
        assertThat(resolution.dataSources()).extracting(owner -> owner.label()).containsExactly("Data Source 1");
    }

    @Test
    void propagatesMetadataFailureWithoutAffectingApplicationBootstrap() {
        OwnerMetadataSource unavailable = classId -> {
            throw new OwnerRulesUnavailableException("metadata unavailable");
        };
        assertThatThrownBy(() -> resolver(unavailable).resolve(ownerGraph(1, 0, false), TRANSFORMER))
                .isInstanceOf(OwnerRulesUnavailableException.class)
                .hasMessageContaining("metadata unavailable");
    }

    @Test
    void exposesInferredOwnerTriplesToLocalSparql() {
        PrefixService prefixes = new PrefixService();
        OwnerRulesModelReasoner reasoner = new OwnerRulesModelReasoner(new OwnerRuleGenerator(metadata(), prefixes));
        Model inferred = reasoner.apply(ownerGraph(1, 0, false));
        String query = "SELECT ?owner WHERE { <" + TRANSFORMER + "> <http://so-ups.ru/2015/schema-cim16#Object.OwnersToBottom> ?owner }";

        try (QueryExecution execution = QueryExecution.model(inferred).query(query).build()) {
            ResultSet results = execution.execSelect();
            assertThat(results.hasNext()).isTrue();
            assertThat(results.next().getResource("owner").getURI()).isEqualTo(UPS + "_owner1");
        }
    }

    private static OwnerRulesResolver resolver(OwnerMetadataSource metadata) {
        PrefixService prefixes = new PrefixService();
        JenaRipperProperties properties = new JenaRipperProperties(
                new JenaRipperProperties.Dataset("memory", "unused"),
                new JenaRipperProperties.Graph(100),
                new JenaRipperProperties.Labels(List.of(RDFS.label.getURI())),
                new JenaRipperProperties.Api(List.of(), 25),
                new JenaRipperProperties.Sparql(Duration.ofSeconds(5), 100, 100, List.of()),
                new JenaRipperProperties.OwnerRules(
                        true, "jdbc:unused", "", "", "public", Duration.ofSeconds(1), 4),
                null,
                null);
        return new OwnerRulesResolver(new OwnerRuleGenerator(metadata, prefixes), prefixes, new GraphMapper(prefixes, properties));
    }

    private static OwnerMetadataSource metadata() {
        return classId -> switch (classId) {
            case "cim:Plant" -> List.of(new OwnerAssociation(
                    classId, "rf:Plant.Substations", "rf:Substation.Plant", List.of("cim:Substation"), null));
            case "cim:Substation" -> List.of(new OwnerAssociation(
                    classId, "cim:EquipmentContainer.Equipments", "cim:Equipment.EquipmentContainer",
                    List.of("cim:PowerTransformer"), null));
            default -> List.of();
        };
    }

    private static Model ownerGraph(int ownerCount, int dataSourceCount, boolean sameResource) {
        Model model = ModelFactory.createDefaultModel();
        Resource transformer = model.createResource(TRANSFORMER)
                .addProperty(RDFS.label, "T-2")
                .addProperty(RDF.type, model.createResource(CIM + "PowerTransformer"));
        Resource substation = model.createResource(SUBSTATION)
                .addProperty(RDFS.label, "Substation")
                .addProperty(RDF.type, model.createResource(CIM + "Substation"));
        transformer.addProperty(ResourceFactory.createProperty(CIM + "Equipment.EquipmentContainer"), substation);
        for (int index = 1; index <= ownerCount; index++) {
            Resource role = model.createResource(UPS + "_role" + index)
                    .addProperty(RDF.type, model.createResource(CIM + "AssetOwner"));
            Resource owner = model.createResource(UPS + "_owner" + index).addProperty(RDFS.label, "Owner " + index);
            substation.addProperty(ResourceFactory.createProperty(RF + "IdentifiedObject.OrganisationRoles"), role);
            role.addProperty(ResourceFactory.createProperty(CIM + "OrganisationRole.Organisation"), owner);
        }
        for (int index = 1; index <= dataSourceCount; index++) {
            Resource role = model.createResource(UPS + "_data-source-role" + index)
                    .addProperty(RDF.type, model.createResource("http://so-ups.ru/2015/schema-cim16#AssetDataSource"));
            String resourceUri = sameResource ? UPS + "_owner" + index : UPS + "_data-source" + index;
            Resource dataSource = model.createResource(resourceUri).addProperty(RDFS.label,
                    sameResource ? "Owner " + index : "Data Source " + index);
            substation.addProperty(ResourceFactory.createProperty(RF + "IdentifiedObject.OrganisationRoles"), role);
            role.addProperty(ResourceFactory.createProperty(CIM + "OrganisationRole.Organisation"), dataSource);
        }
        return model;
    }
}
