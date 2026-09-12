package com.sqlcli.task;

import com.sqlcli.config.DatabaseConfig;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecoverySupportTest {

    /**
     * 用 url 配的 MySQL 别名 database 为空、或和 URL 里的库不一致时，主键必须在连接当前库里找，
     * 否则 Connector/J 对「别的库没这张表」静默返回空，UPDATE 会被当成无主键拒掉。
     */
    @Test
    void mysqlLooksUpPrimaryKeyInTheConnectionsCurrentDatabase() throws Exception {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet found = mock(ResultSet.class);
        ResultSet empty = mock(ResultSet.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getCatalog()).thenReturn("pct_prd");
        when(metadata.getPrimaryKeys("pct_prd", null, "system_menu")).thenReturn(found);
        when(metadata.getPrimaryKeys("stale", null, "system_menu")).thenReturn(empty);
        when(found.next()).thenReturn(true, false);
        when(found.getString("COLUMN_NAME")).thenReturn("id");
        when(found.getShort("KEY_SEQ")).thenReturn((short) 1);
        when(empty.next()).thenReturn(false);

        DatabaseConfig config = new DatabaseConfig();
        config.setType("mysql");
        config.setDatabase("stale");

        assertEquals(List.of("id"), RecoverySupport.primaryKeyColumns(connection, "system_menu", config));

        config.setDatabase(null);
        when(found.next()).thenReturn(true, false);
        assertEquals(List.of("id"), RecoverySupport.primaryKeyColumns(connection, "system_menu", config));
    }
}
