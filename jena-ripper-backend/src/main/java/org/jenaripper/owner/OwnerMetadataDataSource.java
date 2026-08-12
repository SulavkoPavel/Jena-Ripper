package org.jenaripper.owner;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.jenaripper.config.JenaRipperProperties;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;

@Component
public class OwnerMetadataDataSource {
    private final HikariDataSource dataSource;

    public OwnerMetadataDataSource(JenaRipperProperties properties) {
        JenaRipperProperties.OwnerRules owner = properties.ownerRules();
        if (owner == null || !owner.enabled()) {
            dataSource = null;
            return;
        }
        HikariConfig config = new HikariConfig();
        config.setPoolName("jena-ripper-owner-metadata");
        config.setJdbcUrl(owner.jdbcUrl());
        config.setUsername(owner.username());
        config.setPassword(owner.password());
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(0);
        config.setReadOnly(true);
        config.setConnectionTimeout(Math.max(1_000, owner.connectTimeout().toMillis()));
        config.setValidationTimeout(Math.max(1_000, owner.connectTimeout().toMillis()));
        config.setInitializationFailTimeout(-1);
        dataSource = new HikariDataSource(config);
    }

    public Connection connection() throws SQLException {
        if (dataSource == null) throw new SQLException("Owner Rules PostgreSQL is disabled");
        return dataSource.getConnection();
    }

    @PreDestroy
    public void close() {
        if (dataSource != null) dataSource.close();
    }
}
