package org.jenaripper.owner;

import org.jenaripper.exception.OwnerRulesUnavailableException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jenaripper.config.JenaRipperProperties;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

@Repository
public class PostgresOwnerMetadataSource implements OwnerMetadataSource {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final JenaRipperProperties.OwnerRules properties;
    private final ObjectMapper objectMapper;
    private final OwnerMetadataDataSource dataSource;

    public PostgresOwnerMetadataSource(JenaRipperProperties properties, ObjectMapper objectMapper, OwnerMetadataDataSource dataSource) {
        this.properties = properties.ownerRules();
        this.objectMapper = objectMapper;
        this.dataSource = dataSource;
    }

    @Override
    public List<OwnerAssociation> findVisibleByClass(String classId) {
        if (properties == null || !properties.enabled()) {
            throw new OwnerRulesUnavailableException("Owner Rules отключены в конфигурации.");
        }
        String schema = safeIdentifier(properties.schema());
        String sql = """
                select metamodel_class_id, name, inverse_role_name, ranges::text, data_type
                  from %s.metamodel_associations
                 where metamodel_class_id = ? and show is true
                 order by id
                """.formatted(schema);
        List<OwnerAssociation> result = new ArrayList<>();
        try {
            try (Connection connection = dataSource.connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                connection.setReadOnly(true);
                statement.setString(1, classId);
                statement.setQueryTimeout(Math.max(1, (int) properties.connectTimeout().toSeconds()));
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String rangesJson = rows.getString("ranges");
                        List<String> ranges = rangesJson == null
                                ? List.of()
                                : objectMapper.readValue(rangesJson, STRING_LIST);
                        result.add(new OwnerAssociation(
                                rows.getString("metamodel_class_id"),
                                rows.getString("name"),
                                rows.getString("inverse_role_name"),
                                List.copyOf(ranges),
                                rows.getString("data_type")));
                    }
                }
            }
            return List.copyOf(result);
        } catch (Exception exception) {
            throw new OwnerRulesUnavailableException("Owner Rules временно недоступны: не удалось прочитать metadata PostgreSQL.", exception);
        }
    }

    private static String safeIdentifier(String value) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Некорректная PostgreSQL schema для Owner Rules");
        }
        return value;
    }
}
