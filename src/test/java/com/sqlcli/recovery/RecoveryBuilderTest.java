package com.sqlcli.recovery;

import com.sqlcli.parser.ParsedSql;
import com.sqlcli.parser.SqlParser;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecoveryBuilderTest {

    @Test
    void multiRowUpdateRecoveryUsesEveryCompositePrimaryKeyValue() throws Exception {
        ParsedSql sql = new SqlParser().parse(
                "UPDATE orders SET status = 'paid' WHERE batch_id = 9");
        ResultSet rows = rows(
                new String[]{"tenant_id", "id", "status"},
                new Object[][]{
                        {7, 11, "pending"},
                        {7, 12, "failed"}
                });

        RecoveryResult result = new RecoveryBuilder().build(
                sql, rows, "test", List.of("tenant_id", "id"));

        assertEquals(List.of(
                "UPDATE orders SET status='pending' WHERE tenant_id=7 AND id=11;",
                "UPDATE orders SET status='failed' WHERE tenant_id=7 AND id=12;"
        ), result.getRecoverySqls());
    }

    @Test
    void updateRecoveryRejectsMissingOrNullPrimaryKeys() throws Exception {
        ParsedSql sql = new SqlParser().parse(
                "UPDATE orders SET status = 'paid' WHERE batch_id = 9");

        assertThrows(SQLException.class, () -> new RecoveryBuilder().build(
                sql,
                rows(new String[]{"id", "status"}, new Object[][]{{1, "pending"}}),
                "test",
                List.of()));
        assertThrows(SQLException.class, () -> new RecoveryBuilder().build(
                sql,
                rows(new String[]{"id", "status"}, new Object[][]{{null, "pending"}}),
                "test",
                List.of("id")));
    }

    @Test
    void singleRowUpdateAndDeleteKeepNullAndJdbcTemporalValuesCompatible() throws Exception {
        RecoveryBuilder builder = new RecoveryBuilder();
        RecoveryResult update = builder.build(
                new SqlParser().parse(
                        "UPDATE audit.orders SET status = NULL WHERE id = 3"),
                rows(new String[]{"id", "status"}, new Object[][]{{3, null}}),
                "test",
                List.of("id"));
        assertEquals("SELECT * FROM audit.orders WHERE id = 3",
                new SqlParser().parse(
                        "UPDATE audit.orders SET status = NULL WHERE id = 3").buildQuerySql());
        assertEquals(List.of(
                "UPDATE audit.orders SET status=NULL WHERE id=3;"
        ), update.getRecoverySqls());

        RecoveryResult delete = builder.build(
                new SqlParser().parse(
                        "DELETE FROM audit.orders WHERE id = 3"),
                rows(
                        new String[]{"id", "name", "amount", "created_on", "created_at", "run_at", "note"},
                        new Object[][]{{
                                3,
                                "O'Reilly",
                                12.5,
                                java.sql.Date.valueOf(LocalDate.of(2026, 7, 26)),
                                Timestamp.valueOf("2026-07-26 12:34:56"),
                                Time.valueOf("12:34:56"),
                                null
                        }}),
                "test",
                List.of());
        assertEquals(List.of(
                "INSERT INTO audit.orders (id, name, amount, created_on, created_at, run_at, note) "
                        + "VALUES (3, 'O''Reilly', 12.5, '2026-07-26', "
                        + "'2026-07-26 12:34:56.0', '12:34:56', NULL);"
        ), delete.getRecoverySqls());
    }

    private ResultSet rows(String[] labels, Object[][] values) throws SQLException {
        ResultSet resultSet = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(resultSet.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(labels.length);
        for (int column = 0; column < labels.length; column++) {
            when(metadata.getColumnLabel(column + 1)).thenReturn(labels[column]);
            Object[] columnValues = new Object[values.length];
            for (int row = 0; row < values.length; row++) {
                columnValues[row] = values[row][column];
            }
            when(resultSet.getObject(column + 1)).thenReturn(
                    columnValues[0],
                    java.util.Arrays.copyOfRange(columnValues, 1, columnValues.length));
        }
        Boolean[] remainingRows = new Boolean[values.length];
        java.util.Arrays.fill(remainingRows, Boolean.TRUE);
        when(resultSet.next()).thenReturn(
                remainingRows[0],
                java.util.Arrays.copyOfRange(remainingRows, 1, remainingRows.length))
                .thenReturn(false);
        return resultSet;
    }
}
