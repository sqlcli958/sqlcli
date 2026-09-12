package com.sqlcli.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DriverResolverTest {

    private final DriverResolver resolver = new DriverResolver();

    @Test
    void testApplyBuiltinDefaults_MySql() {
        DatabaseConfig config = new DatabaseConfig();
        config.setType("mysql");
        config.setDriverRef("");

        resolver.enrich(config);

        assertEquals("com.mysql.cj.jdbc.Driver", config.getDriverClass());
    }

    @Test
    void testApplyBuiltinDefaults_Oracle() {
        DatabaseConfig config = new DatabaseConfig();
        config.setType("oracle");
        config.setDriverRef("");

        resolver.enrich(config);

        assertEquals("oracle.jdbc.OracleDriver", config.getDriverClass());
    }

    @Test
    void testApplyBuiltinDefaults_PostgreSql() {
        DatabaseConfig config = new DatabaseConfig();
        config.setType("postgresql");
        config.setDriverRef("");

        resolver.enrich(config);

        assertEquals("org.postgresql.Driver", config.getDriverClass());
    }

    @Test
    void testApplyBuiltinDefaults_ClickHouse() {
        DatabaseConfig config = new DatabaseConfig();
        config.setType("clickhouse");
        config.setDriverRef("");

        resolver.enrich(config);

        assertEquals("com.clickhouse.jdbc.ClickHouseDriver", config.getDriverClass());
    }

    @Test
    void testApplyBuiltinDefaults_Sqlite() {
        DatabaseConfig config = new DatabaseConfig();
        config.setType("sqlite");
        config.setDriverRef("");

        resolver.enrich(config);

        assertEquals("org.sqlite.JDBC", config.getDriverClass());
    }

    @Test
    void sqliteDriverRefFromSettingsHasEmptyJars() {
        DatabaseConfig config = new DatabaseConfig();
        config.setType("sqlite");

        resolver.enrich(config);

        // config/settings.yaml 里 sqlite3 驱动的 jars 是空列表：驱动是主 jar 自带的编译依赖，
        // 不需要额外 jar,DriverLoader 遇到空列表会直接从当前 classpath 加载。
        assertEquals("org.sqlite.JDBC", config.getDriverClass());
        assertTrue(config.getDriverJars().isEmpty(),
                "sqlite 驱动不需要外部 jar，settings.yaml 里应配置为空列表");
    }
}
