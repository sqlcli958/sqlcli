package com.sqlcli.recovery;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 恢复SQL结果
 */
public class RecoveryResult {

    private final String sqlType;
    private final String tableName;
    private final int affectedRows;
    private final List<Map<String, Object>> originalData;
    private final List<String> recoverySqls;
    private final String aliasName;
    private final String originalSql;

    public RecoveryResult(String sqlType, String tableName, int affectedRows,
                          List<Map<String, Object>> originalData, List<String> recoverySqls,
                          String aliasName, String originalSql) {
        this.sqlType = sqlType;
        this.tableName = tableName;
        this.affectedRows = affectedRows;
        this.originalData = originalData != null ? new ArrayList<>(originalData) : new ArrayList<>();
        this.recoverySqls = recoverySqls != null ? new ArrayList<>(recoverySqls) : new ArrayList<>();
        this.aliasName = aliasName;
        this.originalSql = originalSql;
    }

    public String getSqlType() {
        return sqlType;
    }

    public String getTableName() {
        return tableName;
    }

    public List<String> getRecoverySqls() {
        return recoverySqls;
    }

    private String formatRow(Map<String, Object> row) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            Object value = entry.getValue();
            String valueStr = value == null ? "NULL" :
                    (value instanceof String ? "'" + value + "'" : String.valueOf(value));
            parts.add(entry.getKey() + "=" + valueStr);
        }
        return String.join(", ", parts);
    }

    /**
     * 回滚段：逐条恢复语句，一行一条。没有原始行时是空串（调用方据此判断"这条语句无可回滚"）。
     *
     * <p>这两段以前是写进 {@code ~/.sql-cli/recovery/*.sql} 的，现在直接存 SQLite——
     * 一份数据两个存储位置、还要靠注释标记切段落，比存两列贵得多。
     */
    public String rollbackText() {
        return String.join("\n", recoverySqls);
    }

    /** 执行前的原始行，一行一条 {@code 列=值}。 */
    public String backupText() {
        return originalData.stream().map(this::formatRow).collect(Collectors.joining("\n"));
    }
}
