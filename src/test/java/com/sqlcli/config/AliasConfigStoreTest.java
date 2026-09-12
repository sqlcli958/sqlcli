package com.sqlcli.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AliasConfigStoreTest {

    @Test
    void serializesJdbcAliasWithOneCanonicalJdbcUrl() {
        DatabaseConfig config = new DatabaseConfig();
        config.setType("clickhouse");
        config.setDriverRef("clickhouse09");
        config.setHost("db.example.com");
        config.setPort(8123);
        config.setDatabase("analytics");
        config.setUsername("app_user");
        config.setSecretRef("env:CLICKHOUSE_PASSWORD");
        config.setParams(Map.of("socket_timeout", "300000"));

        Map<String, Object> yaml = new AliasConfigStore().toMap(config);

        assertEquals("jdbc:clickhouse://db.example.com:8123/analytics", yaml.get("url"));
        assertFalse(yaml.containsKey("host"));
        assertFalse(yaml.containsKey("port"));
        assertFalse(yaml.containsKey("database"));
        assertEquals(Map.of("socket_timeout", "300000"), yaml.get("params"));
    }

    @Test
    void normalizesLegacyClickHouseUrlWhenWriting() {
        DatabaseConfig config = new DatabaseConfig();
        config.setType("clickhouse");
        config.setDriverRef("clickhouse09");
        config.setJdbcUrl("jdbc:ch:http://localhost:8123/default");

        Map<String, Object> yaml = new AliasConfigStore().toMap(config);

        assertEquals("jdbc:clickhouse://localhost:8123/default", yaml.get("url"));
    }
}
