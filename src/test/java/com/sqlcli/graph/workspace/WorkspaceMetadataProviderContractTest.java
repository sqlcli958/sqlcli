package com.sqlcli.graph.workspace;

import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.strategy.ClickHouseWorkspaceMetadataProvider;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * DB-P3-003：所有 WorkspaceMetadataProvider 必须通过同一组基础契约。
 * 只覆盖不需要真实数据库的部分：类型声明、系统 schema 识别、能力开关、close 幂等。
 * schema/table/column 的真实抽取契约由各自的单元测试和 Testcontainers 集成测试覆盖。
 */
class WorkspaceMetadataProviderContractTest {

    record ProviderCase(WorkspaceMetadataProvider provider, String expectedType,
                        List<String> systemSchemas, String normalSchema) {
        @Override
        public String toString() {
            return expectedType;
        }
    }

    static Stream<ProviderCase> providers() throws SQLException {
        return Stream.of(
                new ProviderCase(genericProvider("jdbc:mysql://localhost:3306/db"), "mysql",
                        List.of("information_schema", "mysql", "performance_schema", "sys"), "myapp"),
                new ProviderCase(genericProvider("jdbc:oracle:thin:@localhost:1521:orcl"), "oracle",
                        List.of("SYS", "SYSTEM", "XDB"), "APPUSER"),
                new ProviderCase(genericProvider("jdbc:postgresql://localhost:5432/db"), "postgresql",
                        List.of("pg_catalog", "pg_toast", "information_schema"), "public"),
                new ProviderCase(clickHouseProvider(), "clickhouse",
                        List.of("system", "information_schema", "INFORMATION_SCHEMA"), "analytics"),
                // SQLite 没有系统 schema 需要过滤——只有一个固定的 "main"，参见
                // SqliteDatabaseStrategy 类注释；systemSchemas 留空，下面
                // systemSchemasAreRecognized 对空列表的循环体天然不执行。
                new ProviderCase(genericProvider("jdbc:sqlite:D:/data/shop.db"), "sqlite",
                        List.of(), "main"));
    }

    private static WorkspaceMetadataProvider genericProvider(String url) throws SQLException {
        Connection conn = Mockito.mock(Connection.class);
        DatabaseMetaData metaData = Mockito.mock(DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(metaData);
        when(metaData.getURL()).thenReturn(url);
        return new WorkspaceMetadataExtractor(conn, "contract-test");
    }

    private static WorkspaceMetadataProvider clickHouseProvider() throws SQLException {
        Connection conn = Mockito.mock(Connection.class);
        DatabaseConfig config = new DatabaseConfig();
        config.setAliasName("contract-test");
        config.setType("clickhouse");
        return new ClickHouseWorkspaceMetadataProvider(conn, config);
    }

    @ParameterizedTest
    @MethodSource("providers")
    void databaseTypeIsDeclared(ProviderCase c) {
        assertEquals(c.expectedType(), c.provider().getDatabaseType());
    }

    @ParameterizedTest
    @MethodSource("providers")
    void systemSchemasAreRecognized(ProviderCase c) {
        for (String schema : c.systemSchemas()) {
            assertTrue(c.provider().isSystemSchema(schema),
                    c.expectedType() + " 应识别系统 schema: " + schema);
        }
    }

    @ParameterizedTest
    @MethodSource("providers")
    void normalSchemaIsNotSystem(ProviderCase c) {
        assertFalse(c.provider().isSystemSchema(c.normalSchema()),
                c.expectedType() + " 不应把业务 schema 当系统 schema: " + c.normalSchema());
    }

    @ParameterizedTest
    @MethodSource("providers")
    void isSystemSchemaIsNullSafe(ProviderCase c) {
        assertDoesNotThrow(() -> c.provider().isSystemSchema(null));
        assertFalse(c.provider().isSystemSchema(null));
    }

    @ParameterizedTest
    @MethodSource("providers")
    void capabilityFlagsDoNotThrowAndMatchKnownFacts(ProviderCase c) {
        boolean foreignKeys = assertDoesNotThrow(c.provider()::supportsForeignKeys);
        assertDoesNotThrow(c.provider()::supportsIndexes);
        if ("clickhouse".equals(c.expectedType())) {
            assertFalse(foreignKeys, "ClickHouse 无声明外键，provider 必须声明不支持");
        }
    }

    @ParameterizedTest
    @MethodSource("providers")
    void closeIsIdempotent(ProviderCase c) {
        assertDoesNotThrow(c.provider()::close);
        assertDoesNotThrow(c.provider()::close);
    }
}
