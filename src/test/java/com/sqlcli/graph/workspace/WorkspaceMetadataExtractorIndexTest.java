package com.sqlcli.graph.workspace;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkspaceMetadataExtractorIndexTest {
    @Test
    void extractsProductAndOrderedIndexesWithoutTreatingCompositeUniqueAsSingleColumnUnique() throws Exception {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getCatalog()).thenReturn("app");
        when(metadata.getURL()).thenReturn("jdbc:mysql://localhost/app");
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        when(metadata.getDatabaseProductVersion()).thenReturn("8.4.0");

        ResultSet tables = mock(ResultSet.class);
        when(tables.next()).thenReturn(true, false);
        when(tables.getString("TABLE_TYPE")).thenReturn("TABLE");
        when(metadata.getTables(eq("app"), isNull(), eq("orders"), any(String[].class))).thenReturn(tables);
        ResultSet primaryKeys = mock(ResultSet.class);
        when(primaryKeys.next()).thenReturn(false);
        when(metadata.getPrimaryKeys("app", null, "orders")).thenReturn(primaryKeys);

        ResultSet columns = mock(ResultSet.class);
        when(columns.next()).thenReturn(true, true, true, false);
        when(columns.getString("COLUMN_NAME")).thenReturn("id", "amount", "currency");
        when(columns.getString("TYPE_NAME")).thenReturn("BIGINT", "DECIMAL", "VARCHAR");
        when(columns.getInt("NULLABLE")).thenReturn(DatabaseMetaData.columnNoNulls);
        when(columns.getInt("COLUMN_SIZE")).thenReturn(20);
        when(metadata.getColumns("app", null, "orders", "%")).thenReturn(columns);

        PreparedStatement extras = mock(PreparedStatement.class);
        ResultSet extraRows = mock(ResultSet.class);
        when(connection.prepareStatement(contains("information_schema.COLUMNS"))).thenReturn(extras);
        when(extras.executeQuery()).thenReturn(extraRows);
        when(extraRows.next()).thenReturn(true, false);
        when(extraRows.getString("COLUMN_NAME")).thenReturn("id");
        when(extraRows.getString("EXTRA")).thenReturn(
                "DEFAULT_GENERATED on update CURRENT_TIMESTAMP");

        ResultSet indexes = mock(ResultSet.class);
        when(indexes.next()).thenReturn(true, true, true, false);
        when(indexes.getShort("TYPE")).thenReturn(DatabaseMetaData.tableIndexOther);
        when(indexes.getString("INDEX_NAME")).thenReturn("uq_amount_currency", "uq_amount_currency", "uq_id");
        when(indexes.getString("COLUMN_NAME")).thenReturn("amount", "currency", "id");
        when(indexes.getBoolean("NON_UNIQUE")).thenReturn(false);
        when(indexes.getInt("ORDINAL_POSITION")).thenReturn(1, 2, 1);
        when(metadata.getIndexInfo("app", null, "orders", false, false)).thenReturn(indexes);

        WorkspaceMetadataExtractor extractor = new WorkspaceMetadataExtractor(connection, "db");
        TableWorkspaceNode table = extractor.extractTable("app", "orders").table();
        assertEquals("MySQL", extractor.getProductName());
        assertEquals("8.4.0", extractor.getProductVersion());
        assertEquals(2, table.getIndexes().size());
        assertEquals(java.util.List.of("amount", "currency"), table.getIndexes().get(0).getColumns());
        assertTrue(table.findColumn("amount").isIndexed());
        assertFalse(table.findColumn("amount").isUnique());
        assertFalse(table.findColumn("currency").isUnique());
        assertTrue(table.findColumn("id").isUnique());
        assertEquals("CURRENT_TIMESTAMP", table.findColumn("id").getAttributes().get("onUpdate"));
        verify(extras).setString(1, "app");
        verify(extras).setString(2, "orders");
    }
}
