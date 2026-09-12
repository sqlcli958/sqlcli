package com.sqlcli.config;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AliasResolverTest {

    @Test
    void parsesCanonicalUrlWrittenByInteractiveAliasWizard() {
        Map<String, Object> yaml = new LinkedHashMap<>();
        yaml.put("dbType", "clickhouse");
        yaml.put("driverRef", "clickhouse09");
        yaml.put("url", "jdbc:clickhouse://db.example.com:8123/analytics");
        yaml.put("username", "agent");
        yaml.put("secretRef", "env:TEST_CLICKHOUSE_PASSWORD");
        yaml.put("description", "测试数据库");
        yaml.put("defaultSchema", "reporting");
        yaml.put("readonly", false);

        DatabaseConfig config = new AliasResolver().parseConfig(yaml);

        assertEquals("clickhouse", config.getType());
        assertEquals("reporting", config.getDefaultSchema());
        assertEquals("jdbc:clickhouse://db.example.com:8123/analytics", config.getJdbcUrl());
        assertEquals("agent", config.getUsername());
        assertFalse(config.getReadonly());
        assertEquals("jdbc:clickhouse://db.example.com:8123/analytics", config.buildJdbcUrl());
    }

    @Test
    void rejectsNonCanonicalJdbcUrlFieldName() {
        Map<String, Object> yaml = new LinkedHashMap<>();
        yaml.put("dbType", "clickhouse");
        yaml.put("driverRef", "clickhouse09");
        yaml.put("jdbcUrl", "jdbc:clickhouse://localhost:8123/default");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new AliasResolver().parseConfig(yaml));

        assertEquals("url is required for JDBC alias", error.getMessage());
    }
}
