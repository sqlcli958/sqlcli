package com.sqlcli.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JdbcUrlParserTest {

    // --- inferDbType ---

    @Test
    void testInferDbType_MySql() {
        assertEquals("mysql", JdbcUrlParser.inferDbType("jdbc:mysql://host/db"));
    }

    @Test
    void testInferDbType_Oracle() {
        assertEquals("oracle", JdbcUrlParser.inferDbType("jdbc:oracle:thin:@host:1521:orcl"));
    }

    @Test
    void testInferDbType_PostgreSql() {
        assertEquals("postgresql", JdbcUrlParser.inferDbType("jdbc:postgresql://host/db"));
    }

    @Test
    void testInferDbType_ClickHouse_Short() {
        assertEquals("clickhouse", JdbcUrlParser.inferDbType("jdbc:ch:http://host:8123/db"));
    }

    @Test
    void testInferDbType_ClickHouse_Full() {
        assertEquals("clickhouse", JdbcUrlParser.inferDbType("jdbc:clickhouse:http://host:8123/db"));
    }

    @Test
    void testInferDbType_ClickHouse_Https() {
        assertEquals("clickhouse", JdbcUrlParser.inferDbType("jdbc:ch:https://host:8443/db"));
    }

    @Test
    void testInferDbType_Sqlite() {
        assertEquals("sqlite", JdbcUrlParser.inferDbType("jdbc:sqlite:D:/data/shop.db"));
    }

    @Test
    void normalizesLegacyClickHouseHttpUrl() {
        assertEquals("jdbc:clickhouse://host:8123/db",
                JdbcUrlParser.normalize("jdbc:ch:http://host:8123/db"));
    }

    @Test
    void normalizesLegacyClickHouseHttpsUrlAndPreservesSecurity() {
        assertEquals("jdbc:clickhouse://host:8443/db?socket_timeout=10&ssl=true",
                JdbcUrlParser.normalize("jdbc:ch:https://host:8443/db?socket_timeout=10"));
    }

    @Test
    void extractsDatabaseFromCanonicalClickHouseUrl() {
        assertEquals("analytics", JdbcUrlParser.extractPathDatabase(
                "jdbc:clickhouse://db.example.com:8123/analytics?ssl=true"));
    }

    @Test
    void redactsSecretsInJdbcUrlsAndMessages() {
        assertEquals(
                "jdbc:mysql://db.example.com/app?user=agent&password=***&access_token=***",
                JdbcUrlParser.redactSecrets(
                        "jdbc:mysql://db.example.com/app?user=agent&password=p%40ss&access_token=abc"));
        assertEquals(
                "Failed for jdbc:postgresql://agent:***@db.example.com/app?ssl=true",
                JdbcUrlParser.redactSecrets(
                        "Failed for jdbc:postgresql://agent:plain-secret@db.example.com/app?ssl=true"));
        assertEquals(
                "properties {user=agent, password=***, auth-token=***}; clientSecret=***",
                JdbcUrlParser.redactSecrets(
                        "properties {user=agent, password=plain-secret, auth-token=abc123}; "
                                + "clientSecret=client-value"));
        assertEquals(
                "jdbc:example://db.example.com/app?private_key=***&secret-key=***",
                JdbcUrlParser.redactSecrets(
                        "jdbc:example://db.example.com/app?private_key=key-value&secret-key=secret-value"));
        assertEquals("check username and password",
                JdbcUrlParser.redactSecrets("check username and password"));
        assertEquals("normal message", JdbcUrlParser.redactSecrets("normal message"));
    }

    @Test
    void testInferDbType_Null() {
        assertNull(JdbcUrlParser.inferDbType(null));
    }

    @Test
    void testInferDbType_Unknown() {
        assertNull(JdbcUrlParser.inferDbType("jdbc:unknown://host/db"));
    }

    // --- inferOracleConnectMode ---

    @Test
    void testInferOracleConnectMode_ServiceName() {
        assertEquals("service_name", JdbcUrlParser.inferOracleConnectMode("jdbc:oracle:thin:@//host:1521/service"));
    }

    @Test
    void testInferOracleConnectMode_Sid() {
        assertEquals("sid", JdbcUrlParser.inferOracleConnectMode("jdbc:oracle:thin:@host:1521:sid"));
    }

    // --- extractOracleDatabaseToken ---

    @Test
    void testExtractOracleDatabaseToken_ServiceName() {
        assertEquals("myservice", JdbcUrlParser.extractOracleDatabaseToken("jdbc:oracle:thin:@//host:1521/myservice"));
    }

    @Test
    void testExtractOracleDatabaseToken_Sid() {
        assertEquals("orcl", JdbcUrlParser.extractOracleDatabaseToken("jdbc:oracle:thin:@host:1521:orcl"));
    }
}
