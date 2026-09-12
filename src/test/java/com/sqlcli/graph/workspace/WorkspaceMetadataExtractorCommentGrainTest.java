package com.sqlcli.graph.workspace;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * F2：导入侧从 REMARKS 里拆出 grain，跟 design review
 * （{@link com.sqlcli.graph.review.CandidateDdlProjector}）共用 {@link CommentGrainParser}。
 */
class WorkspaceMetadataExtractorCommentGrainTest {
    @Test
    void grainMarkerIsExtractedFromTableRemarksAndColumnCommentIsNeverSplitWithoutTheMarker() throws Exception {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);
        // 不含任何已知数据库关键字的 URL：databaseType 落到 "unknown"，跳过
        // enrichMySqlColumnExtras / enrichOracleComments 分支，测试只关心 grain 解析本身。
        when(metadata.getURL()).thenReturn("jdbc:h2:mem:test");

        ResultSet tables = mock(ResultSet.class);
        when(tables.next()).thenReturn(true, false);
        when(tables.getString("TABLE_TYPE")).thenReturn("TABLE");
        when(tables.getString("REMARKS")).thenReturn("巡检计划 | grain=一行一个巡检计划");
        when(metadata.getTables(isNull(), eq("app"), eq("plan"), any(String[].class))).thenReturn(tables);

        ResultSet primaryKeys = mock(ResultSet.class);
        when(primaryKeys.next()).thenReturn(false);
        when(metadata.getPrimaryKeys(isNull(), eq("app"), eq("plan"))).thenReturn(primaryKeys);

        ResultSet columns = mock(ResultSet.class);
        when(columns.next()).thenReturn(true, false);
        when(columns.getString("COLUMN_NAME")).thenReturn("status");
        when(columns.getString("TYPE_NAME")).thenReturn("VARCHAR");
        when(columns.getInt("NULLABLE")).thenReturn(DatabaseMetaData.columnNullable);
        // 没有 grain= 前缀：认不出来就整条原样当 comment，不做拆分
        when(columns.getString("REMARKS")).thenReturn("状态 | 已废弃");
        when(metadata.getColumns(isNull(), eq("app"), eq("plan"), eq("%"))).thenReturn(columns);

        ResultSet indexes = mock(ResultSet.class);
        when(indexes.next()).thenReturn(false);
        when(metadata.getIndexInfo(isNull(), eq("app"), eq("plan"), eq(false), eq(false))).thenReturn(indexes);

        WorkspaceMetadataExtractor extractor = new WorkspaceMetadataExtractor(connection, "db");
        TableWorkspaceNode table = extractor.extractTable("app", "plan").table();

        assertEquals("巡检计划", table.getComment());
        assertEquals("一行一个巡检计划", table.getGrain());
        assertEquals("状态 | 已废弃", table.findColumn("status").getComment());
    }
}
