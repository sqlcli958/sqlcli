package com.sqlcli.clickhouse;

import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.connection.SqlInterceptor;
import com.sqlcli.graph.workspace.TableExtractResult;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import com.sqlcli.graph.workspace.WorkspaceMetadataProvider;
import com.sqlcli.strategy.ClickHouseDatabaseStrategy;
import com.sqlcli.strategy.TableInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CH-P1-027 / CH-P1-028：ClickHouse Testcontainers 集成测试。
 *
 * <p>默认不参与构建：文件名以 {@code IT} 结尾，被 pom.xml 里 maven-compiler-plugin 的
 * {@code testExcludes} 默认排除出 testCompile，{@code mvn test} / {@code mvn package}
 * 完全碰不到这个文件——不会因为本机没有 Docker、也没有 testcontainers/clickhouse-jdbc
 * 依赖而失败。
 *
 * <p>启用方式（需要本机或 CI 有 Docker）：
 * <pre>mvn -Pit-clickhouse verify</pre>
 * 该 profile 会补上被排除的编译范围、补上 testcontainers + clickhouse-jdbc 依赖，
 * 并用 maven-failsafe-plugin 在 verify 阶段执行本类。
 *
 * <p>覆盖范围对应 CH-P1-028 要求的 test / SELECT / SHOW / DESCRIBE / tables / ddl /
 * schema import 七个场景，全部通过真实容器里的 ClickHouse server 验证，不是 mock。
 */
@Testcontainers
@Tag("clickhouse-it")
class ClickHouseIntegrationIT {

    @Container
    static final ClickHouseContainer CLICKHOUSE =
            new ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:23.8"));

    private static final ClickHouseDatabaseStrategy STRATEGY = new ClickHouseDatabaseStrategy();
    private static final String TEST_DB = "sqlcli_test";

    private final SqlInterceptor interceptor = new SqlInterceptor();

    private Connection connection;
    private DatabaseConfig config;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection(
                CLICKHOUSE.getJdbcUrl(), CLICKHOUSE.getUsername(), CLICKHOUSE.getPassword());

        config = new DatabaseConfig();
        config.setType("clickhouse");
        config.setAliasName("it-clickhouse");
        config.setDatabase(TEST_DB);
        config.setDefaultQueryLimit(1000);

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE DATABASE IF NOT EXISTS " + TEST_DB);
            stmt.execute("DROP TABLE IF EXISTS " + TEST_DB + ".events");
            stmt.execute("CREATE TABLE " + TEST_DB + ".events (" +
                    "id UInt64, " +
                    "user_id UInt64, " +
                    "event_name String COMMENT '事件名', " +
                    "event_time DateTime, " +
                    "amount Decimal(18,2) DEFAULT 0" +
                    ") ENGINE = MergeTree ORDER BY (event_time, id) COMMENT '事件明细表'");
            stmt.execute("INSERT INTO " + TEST_DB + ".events VALUES " +
                    "(1, 100, 'click', now(), 1.50), " +
                    "(2, 100, 'view', now(), 0)");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    @DisplayName("test：连接可用，能拿到 server 信息")
    void connectionIsValidAndReportsServerInfo() throws Exception {
        assertTrue(connection.isValid(5));

        Map<String, String> info = STRATEGY.collectConnectionInfo(connection, config);
        assertNotNull(info);
        assertFalse(info.isEmpty());
    }

    @Test
    @DisplayName("SELECT：查询真实写入的数据")
    void selectReturnsRealRows() throws Exception {
        String sql = interceptor.preprocess(config, "SELECT count() AS c FROM " + TEST_DB + ".events");
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            assertTrue(rs.next());
            assertEquals(2, rs.getLong("c"));
        }
    }

    @Test
    @DisplayName("SHOW：SHOW DATABASES 能看到测试库")
    void showDatabasesListsTestDb() throws Exception {
        String sql = interceptor.preprocess(config, "SHOW DATABASES");
        List<String> names = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        assertTrue(names.contains(TEST_DB));
    }

    @Test
    @DisplayName("DESCRIBE：字段列表跟建表语句一致")
    void describeTableListsCreatedColumns() throws Exception {
        String sql = interceptor.preprocess(config, "DESCRIBE TABLE " + TEST_DB + ".events");
        List<String> columns = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                columns.add(rs.getString("name"));
            }
        }
        assertTrue(columns.containsAll(List.of("id", "user_id", "event_name", "event_time", "amount")));
    }

    @Test
    @DisplayName("tables：listTables 返回刚建的表")
    void listTablesReturnsCreatedTable() throws Exception {
        List<TableInfo> tables = STRATEGY.listTables(connection, TEST_DB, null);
        assertTrue(tables.stream().anyMatch(t -> "events".equals(t.getName())));
    }

    @Test
    @DisplayName("ddl：getTableDdl 返回真实 CREATE TABLE 语句")
    void getTableDdlReturnsRealCreateStatement() throws Exception {
        String ddl = STRATEGY.getTableDdl(connection, "events", TEST_DB);
        assertNotNull(ddl);
        assertTrue(ddl.toUpperCase(Locale.ROOT).contains("CREATE TABLE"));
        assertTrue(ddl.contains("MergeTree"));
    }

    @Test
    @DisplayName("schema import：WorkspaceMetadataProvider 从真实系统表抽取表结构")
    void metadataProviderExtractsRealTableFromSystemTables() throws Exception {
        WorkspaceMetadataProvider provider = STRATEGY.createMetadataProvider(connection, config);
        try {
            List<String> schemas = provider.discoverSchemas();
            assertTrue(schemas.contains(TEST_DB));

            List<String> tables = provider.discoverTables(TEST_DB);
            assertTrue(tables.contains("events"));

            TableExtractResult result = provider.extractTable(TEST_DB, "events");
            assertNotNull(result);
            TableWorkspaceNode table = result.table();
            assertEquals("events", table.getName());
            assertEquals(5, table.getColumns().size());
            assertEquals("MergeTree", table.getAttributes().get("engine"));
        } finally {
            provider.close();
        }
    }
}
