package com.sqlcli.strategy;

import com.sqlcli.config.DatabaseConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DatabaseStrategyDefaultSchemaTest {

    @Test
    void postgresqlDoesNotTreatUsernameAsSchema() {
        DatabaseConfig config = new DatabaseConfig();
        config.setUsername("app_user");
        config.setJdbcUrl("jdbc:postgresql://localhost:5432/app");

        assertNull(new PostgreSqlDatabaseStrategy().defaultSchema(config));
    }

    @Test
    void oracleUsesUsernameAsDefaultSchema() {
        DatabaseConfig config = new DatabaseConfig();
        config.setUsername("APP_USER");

        assertEquals("APP_USER", new OracleDatabaseStrategy().defaultSchema(config));
    }
}
