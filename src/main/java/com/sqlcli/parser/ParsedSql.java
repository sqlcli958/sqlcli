package com.sqlcli.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL解析结果
 */
public class ParsedSql {
    private final String sqlType;           // UPDATE / DELETE
    private final String tableName;         // 表名
    private final String whereClause;       // WHERE条件（可能为null）
    private final List<String> columns;     // UPDATE的列名列表
    private final List<String> values;      // UPDATE的新值表达式列表
    private final boolean isComplex;        // 是否复杂SQL
    private final String complexityReason;  // 复杂SQL的原因
    private final String originalSql;       // 原始SQL

    public ParsedSql(String sqlType, String tableName, String whereClause,
                      List<String> columns, List<String> values,
                      boolean isComplex, String complexityReason, String originalSql) {
        this.sqlType = sqlType;
        this.tableName = tableName;
        this.whereClause = whereClause;
        this.columns = columns != null ? new ArrayList<>(columns) : new ArrayList<>();
        this.values = values != null ? new ArrayList<>(values) : new ArrayList<>();
        this.isComplex = isComplex;
        this.complexityReason = complexityReason;
        this.originalSql = originalSql;
    }

    public String getSqlType() {
        return sqlType;
    }

    public String getTableName() {
        return tableName;
    }

    public String getWhereClause() {
        return whereClause;
    }

    public boolean hasWhereClause() {
        return whereClause != null && !whereClause.isBlank();
    }

    public List<String> getColumns() {
        return columns;
    }

    public List<String> getValues() {
        return values;
    }

    public boolean isComplex() {
        return isComplex;
    }

    public String getComplexityReason() {
        return complexityReason;
    }

    public String getOriginalSql() {
        return originalSql;
    }

    /**
     * 构建查询原始数据的SQL
     */
    public String buildQuerySql() {
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT * FROM ").append(tableName);
        if (hasWhereClause()) {
            sb.append(" WHERE ").append(whereClause);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return String.format("ParsedSql{type=%s, table=%s, where=%s, columns=%s, complex=%s}",
                sqlType, tableName, whereClause, columns, isComplex);
    }
}