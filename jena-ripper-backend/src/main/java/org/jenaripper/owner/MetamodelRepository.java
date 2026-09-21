package org.jenaripper.owner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jenaripper.config.JenaRipperProperties;
import org.jenaripper.exception.MetamodelUnavailableException;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

@Repository
public class MetamodelRepository {
    private final String schema;
    private final int queryTimeoutSeconds;
    private final OwnerMetadataDataSource dataSource;
    private final ObjectMapper objectMapper;

    public MetamodelRepository(JenaRipperProperties properties,
                               OwnerMetadataDataSource dataSource,
                               ObjectMapper objectMapper) {
        JenaRipperProperties.OwnerRules metadata = properties.ownerRules();
        this.schema = safeIdentifier(metadata.schema());
        this.queryTimeoutSeconds = Math.max(1, (int) metadata.connectTimeout().toSeconds());
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    public MetamodelData load() {
        try (Connection connection = dataSource.connection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                MetamodelData metamodel = new MetamodelData(readClasses(connection), readAssociations(connection));
                connection.commit();
                return metamodel;
            } catch (Exception exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (Exception exception) {
            throw new MetamodelUnavailableException(
                    "Метамодель недоступна: не удалось прочитать metadata PostgreSQL.", exception);
        }
    }

    public List<MetamodelAttributeRow> attributes(String classId) {
        try (Connection connection = dataSource.connection()) {
            connection.setReadOnly(true);
            return readAttributes(connection, classId);
        } catch (Exception exception) {
            throw new MetamodelUnavailableException(
                    "Метамодель недоступна: не удалось прочитать атрибуты класса из PostgreSQL.", exception);
        }
    }

    private static void rollback(Connection connection, Exception originalException) {
        try {
            connection.rollback();
        } catch (Exception rollbackException) {
            originalException.addSuppressed(rollbackException);
        }
    }

    private List<MetamodelClassRow> readClasses(Connection connection) throws Exception {
        String sql = """
                select id, label, label_ru, parents::text, children::text, show, enumeration,
                       compound, is_cim_data_type, dictionary
                  from %s.metamodel_classes
                 order by id
                """.formatted(schema);
        List<MetamodelClassRow> classes = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(queryTimeoutSeconds);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    classes.add(new MetamodelClassRow(
                            rows.getString("id"),
                            rows.getString("label"),
                            rows.getString("label_ru"),
                            strings(rows.getString("parents")),
                            strings(rows.getString("children")),
                            booleanValue(rows, "show"),
                            booleanValue(rows, "enumeration"),
                            booleanValue(rows, "compound"),
                            booleanValue(rows, "is_cim_data_type"),
                            booleanValue(rows, "dictionary")));
                }
            }
        }
        return List.copyOf(classes);
    }

    private List<MetamodelAssociationRow> readAssociations(Connection connection) throws Exception {
        String sql = """
                select id, metamodel_class_id, name, label, label_ru, range, ranges::text,
                       ranges_need::text, auto_create::text, data_type, data_type_info, is_table,
                       inverse_role_name, show
                  from %s.metamodel_associations
                 order by id
                """.formatted(schema);
        List<MetamodelAssociationRow> associations = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(queryTimeoutSeconds);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    associations.add(new MetamodelAssociationRow(
                            rows.getLong("id"),
                            rows.getString("metamodel_class_id"),
                            rows.getString("name"),
                            rows.getString("label"),
                            rows.getString("label_ru"),
                            rows.getString("range"),
                            strings(rows.getString("ranges")),
                            rangeNeeds(rows.getString("ranges_need")),
                            strings(rows.getString("auto_create")),
                            rows.getString("data_type"),
                            rows.getString("data_type_info"),
                            integerValue(rows, "is_table"),
                            rows.getString("inverse_role_name"),
                            booleanValue(rows, "show")));
                }
            }
        }
        return List.copyOf(associations);
    }

    private List<MetamodelAttributeRow> readAttributes(Connection connection, String classId) throws Exception {
        String sql = """
                select id, name, label, label_ru, type, factor, unit, range, order_num,
                       data_type, data_type_info, show
                  from %s.metamodel_attributes
                 where metamodel_class_id = ?
                 order by order_num nulls last, name, id
                """.formatted(schema);
        List<MetamodelAttributeRow> attributes = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, classId);
            statement.setQueryTimeout(queryTimeoutSeconds);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    attributes.add(new MetamodelAttributeRow(
                            rows.getLong("id"),
                            rows.getString("name"),
                            rows.getString("label"),
                            rows.getString("label_ru"),
                            rows.getString("type"),
                            floatValue(rows, "factor"),
                            rows.getString("unit"),
                            rows.getString("range"),
                            integerValue(rows, "order_num"),
                            rows.getString("data_type"),
                            rows.getString("data_type_info"),
                            booleanValue(rows, "show")));
                }
            }
        }
        return List.copyOf(attributes);
    }

    private List<String> strings(String json) throws Exception {
        if (json == null || json.isBlank()) return List.of();
        JsonNode values = objectMapper.readTree(json);
        if (!values.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        values.forEach(value -> {
            if (value.isTextual() && !value.asText().isBlank()) {
                result.add(value.asText());
            }
        });
        return List.copyOf(result);
    }

    private List<RangeNeedRow> rangeNeeds(String json) throws Exception {
        if (json == null || json.isBlank()) return List.of();
        JsonNode values = objectMapper.readTree(json);
        if (!values.isArray()) return List.of();
        List<RangeNeedRow> result = new ArrayList<>();
        values.forEach(value -> {
            String range = value.path("range").asText();
            if (!range.isBlank() && value.hasNonNull("need")) {
                result.add(new RangeNeedRow(range, value.path("need").asLong()));
            }
        });
        return List.copyOf(result);
    }

    private static Boolean booleanValue(ResultSet rows, String column) throws Exception {
        boolean value = rows.getBoolean(column);
        return rows.wasNull() ? null : value;
    }

    private static Integer integerValue(ResultSet rows, String column) throws Exception {
        int value = rows.getInt(column);
        return rows.wasNull() ? null : value;
    }

    private static Float floatValue(ResultSet rows, String column) throws Exception {
        float value = rows.getFloat(column);
        return rows.wasNull() ? null : value;
    }

    private static String safeIdentifier(String value) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Некорректная PostgreSQL schema для Metamodel");
        }
        return value;
    }

    public record MetamodelData(
            List<MetamodelClassRow> classes,
            List<MetamodelAssociationRow> associations) {
    }

    public record MetamodelClassRow(
            String id,
            String label,
            String labelRu,
            List<String> parents,
            List<String> children,
            Boolean show,
            Boolean enumeration,
            Boolean compound,
            Boolean cimDataType,
            Boolean dictionary) {
    }

    public record MetamodelAssociationRow(
            long databaseId,
            String source,
            String name,
            String label,
            String labelRu,
            String range,
            List<String> ranges,
            List<RangeNeedRow> rangeNeeds,
            List<String> autoCreate,
            String dataType,
            String dataTypeInfo,
            Integer table,
            String inverseRoleName,
            Boolean show) {
    }

    public record RangeNeedRow(String range, long need) {
    }

    public record MetamodelAttributeRow(
            long databaseId,
            String name,
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
}
