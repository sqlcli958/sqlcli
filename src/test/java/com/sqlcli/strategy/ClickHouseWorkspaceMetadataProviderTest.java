package com.sqlcli.strategy;

import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.graph.workspace.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ClickHouseWorkspaceMetadataProvider}.
 * Uses Mockito mocks since we cannot connect to a real ClickHouse instance.
 *
 * Note: Requires mockito-core (or mockito-junit-jupiter) on the test classpath.
 * If not already in pom.xml, add:
 * <pre>
 * &lt;dependency&gt;
 *     &lt;groupId&gt;org.mockito&lt;/groupId&gt;
 *     &lt;artifactId&gt;mockito-junit-jupiter&lt;/artifactId&gt;
 *     &lt;version&gt;5.8.0&lt;/version&gt;
 *     &lt;scope&gt;test&lt;/scope&gt;
 * &lt;/dependency&gt;
 * </pre>
 */
@ExtendWith(MockitoExtension.class)
class ClickHouseWorkspaceMetadataProviderTest {

    @Mock
    private Connection connection;

    @Mock
    private PreparedStatement pstmt;

    @Mock
    private ResultSet rs;

    private DatabaseConfig config;
    private ClickHouseWorkspaceMetadataProvider provider;

    @BeforeEach
    void setUp() {
        config = new DatabaseConfig();
        config.setType("clickhouse");
        config.setAliasName("test-ch");
        provider = new ClickHouseWorkspaceMetadataProvider(connection, config);
    }

    @Test
    @DisplayName("getDatabaseType returns clickhouse")
    void testGetDatabaseType() {
        assertEquals("clickhouse", provider.getDatabaseType());
    }

    @Test
    void reportsThatForeignKeysAreUnsupported() {
        assertFalse(provider.supportsForeignKeys());
    }

    @Test
    @DisplayName("discoverSchemas returns user databases from system.databases")
    void testDiscoverSchemas() throws SQLException {
        when(connection.prepareStatement(contains("system.databases"))).thenReturn(pstmt);
        when(pstmt.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getString("name")).thenReturn("analytics", "test_db");

        List<String> schemas = provider.discoverSchemas();

        assertEquals(2, schemas.size());
        assertEquals("analytics", schemas.get(0));
        assertEquals("test_db", schemas.get(1));
    }

    @Test
    @DisplayName("discoverSchemas filters system databases")
    void testDiscoverSchemas_FiltersSystemDatabases() throws SQLException {
        // The SQL already has a NOT IN clause for system databases,
        // but we test that even if they somehow appear, they are included
        // in the result since the filtering is done at the SQL level.
        // Here we verify the SQL-level filter by checking the query string.
        when(connection.prepareStatement(contains("system.databases"))).thenReturn(pstmt);
        when(pstmt.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString("name")).thenReturn("analytics");

        List<String> schemas = provider.discoverSchemas();

        assertEquals(1, schemas.size());
        assertEquals("analytics", schemas.get(0));
        // Verify the SQL includes the NOT IN filter for system databases
        verify(connection).prepareStatement(contains("NOT IN"));
    }

    @Test
    @DisplayName("discoverTables returns tables from system.tables for a schema")
    void testDiscoverTables() throws SQLException {
        when(connection.prepareStatement(contains("system.tables"))).thenReturn(pstmt);
        when(pstmt.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString("name")).thenReturn("events");

        List<String> tables = provider.discoverTables("analytics");

        assertEquals(1, tables.size());
        assertEquals("events", tables.get(0));
        // Verify the schema parameter was set
        verify(pstmt).setString(1, "analytics");
    }

    @Test
    @DisplayName("extractTable returns correct table metadata with columns and attributes")
    void testExtractTable() throws SQLException {
        // We need two separate PreparedStatements: one for columns, one for tables
        PreparedStatement columnsStmt = mock(PreparedStatement.class);
        PreparedStatement tablesStmt = mock(PreparedStatement.class);
        ResultSet columnsRs = mock(ResultSet.class);
        ResultSet tablesRs = mock(ResultSet.class);

        // Mock columns query
        when(connection.prepareStatement(contains("system.columns"))).thenReturn(columnsStmt);
        when(columnsStmt.executeQuery()).thenReturn(columnsRs);
        when(columnsRs.next()).thenReturn(true, false);
        when(columnsRs.getString("name")).thenReturn("id");
        when(columnsRs.getString("type")).thenReturn("UInt64");
        when(columnsRs.getString("default_kind")).thenReturn("");
        when(columnsRs.getString("default_expression")).thenReturn("");
        when(columnsRs.getString("comment")).thenReturn("");
        when(columnsRs.getBoolean("is_in_primary_key")).thenReturn(true);

        // Mock tables query
        when(connection.prepareStatement(contains("system.tables"))).thenReturn(tablesStmt);
        when(tablesStmt.executeQuery()).thenReturn(tablesRs);
        when(tablesRs.next()).thenReturn(true, false);
        when(tablesRs.getString("engine")).thenReturn("MergeTree");
        when(tablesRs.getString("comment")).thenReturn("test table");
        when(tablesRs.getString("primary_key")).thenReturn("id");
        when(tablesRs.getString("sorting_key")).thenReturn("id");
        when(tablesRs.getString("partition_key")).thenReturn("");

        TableExtractResult result = provider.extractTable("analytics", "events");

        assertNotNull(result);
        TableWorkspaceNode table = result.table();
        assertNotNull(table);

        // Verify table-level attributes
        assertEquals("events", table.getName());
        assertEquals("analytics", table.getSchema());
        assertEquals("analytics.events", table.getQualifiedName());
        assertEquals(TableType.base_table, table.getTableType());
        assertEquals("test table", table.getComment());

        // Verify attributes from system.tables
        Map<String, Object> attrs = table.getAttributes();
        assertEquals("MergeTree", attrs.get("engine"));
        assertEquals("id", attrs.get("primary_key"));
        assertEquals("id", attrs.get("sorting_key"));
        assertNull(attrs.get("partition_key")); // blank partition_key should not be stored

        // Verify columns
        assertEquals(1, table.getColumns().size());
        ColumnWorkspaceNode col = table.getColumns().get(0);
        assertEquals("id", col.getName());
        assertEquals(1, col.getOrdinal());
        assertFalse(col.isNullable()); // UInt64 is not Nullable
        assertTrue(col.isPrimaryKey());
        assertEquals("UInt64", col.getDataType().getRaw());
        assertEquals("uint64", col.getDataType().getNormalized());

        // Verify primary key list
        assertEquals(1, table.getPrimaryKey().size());
    }

    @Test
    @DisplayName("extractForeignKeysForTable is a no-op and does not throw")
    void testExtractForeignKeys_NoOp() throws SQLException {
        TableWorkspaceNode table = TableWorkspaceNode.create("test-ch", "analytics", "events", GraphActor.extractor);
        GraphWorkspace workspace = GraphWorkspace.create("test-ch", "clickhouse");

        // Should not throw and should not interact with the connection
        assertDoesNotThrow(() -> provider.extractForeignKeysForTable(table, workspace));
        verifyNoInteractions(connection);
    }

    @Test
    @DisplayName("extractTable maps MaterializedView engine to view type with materialized attribute")
    void testExtractTable_MaterializedView() throws SQLException {
        PreparedStatement columnsStmt = mock(PreparedStatement.class);
        PreparedStatement tablesStmt = mock(PreparedStatement.class);
        ResultSet columnsRs = mock(ResultSet.class);
        ResultSet tablesRs = mock(ResultSet.class);

        // No columns for simplicity
        when(connection.prepareStatement(contains("system.columns"))).thenReturn(columnsStmt);
        when(columnsStmt.executeQuery()).thenReturn(columnsRs);
        when(columnsRs.next()).thenReturn(false);

        when(connection.prepareStatement(contains("system.tables"))).thenReturn(tablesStmt);
        when(tablesStmt.executeQuery()).thenReturn(tablesRs);
        when(tablesRs.next()).thenReturn(true, false);
        when(tablesRs.getString("engine")).thenReturn("MaterializedView");
        when(tablesRs.getString("comment")).thenReturn("");
        when(tablesRs.getString("primary_key")).thenReturn("");
        when(tablesRs.getString("sorting_key")).thenReturn("");
        when(tablesRs.getString("partition_key")).thenReturn("");

        TableExtractResult result = provider.extractTable("analytics", "mv_events");

        assertNotNull(result);
        assertEquals(TableType.view, result.table().getTableType());
        assertEquals("true", result.table().getAttributes().get("materialized"));
    }

    @Test
    @DisplayName("extractTable handles Nullable type correctly")
    void testExtractTable_NullableType() throws SQLException {
        PreparedStatement columnsStmt = mock(PreparedStatement.class);
        PreparedStatement tablesStmt = mock(PreparedStatement.class);
        ResultSet columnsRs = mock(ResultSet.class);
        ResultSet tablesRs = mock(ResultSet.class);

        when(connection.prepareStatement(contains("system.columns"))).thenReturn(columnsStmt);
        when(columnsStmt.executeQuery()).thenReturn(columnsRs);
        when(columnsRs.next()).thenReturn(true, false);
        when(columnsRs.getString("name")).thenReturn("description");
        when(columnsRs.getString("type")).thenReturn("Nullable(String)");
        when(columnsRs.getString("default_kind")).thenReturn(null);
        when(columnsRs.getString("default_expression")).thenReturn(null);
        when(columnsRs.getString("comment")).thenReturn(null);
        when(columnsRs.getBoolean("is_in_primary_key")).thenReturn(false);

        when(connection.prepareStatement(contains("system.tables"))).thenReturn(tablesStmt);
        when(tablesStmt.executeQuery()).thenReturn(tablesRs);
        when(tablesRs.next()).thenReturn(false);

        TableExtractResult result = provider.extractTable("analytics", "events");

        ColumnWorkspaceNode col = result.table().getColumns().get(0);
        assertTrue(col.isNullable());
        assertEquals("Nullable(String)", col.getDataType().getRaw());
        assertEquals("string", col.getDataType().getNormalized()); // Nullable wrapper stripped
    }

    @Test
    @DisplayName("close is a no-op and does not close the external connection")
    void testClose() throws Exception {
        assertDoesNotThrow(() -> provider.close());
        verifyNoInteractions(connection);
    }
}
