package com.sqlcli.recovery;

import com.sqlcli.parser.ParsedSql;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 恢复SQL构建器
 * 根据原SQL和查询到的原始数据，构建恢复SQL
 */
public class RecoveryBuilder {

    /**
     * 构建恢复SQL
     * @param parsedSql 解析后的SQL信息
     * @param rs 查询原始数据的ResultSet
     * @param aliasName 数据库别名名称
     * @return 恢复SQL结果
     * @throws SQLException 数据库异常
     */
    public RecoveryResult build(ParsedSql parsedSql, ResultSet rs, String aliasName,
                                List<String> primaryKeyColumns) throws SQLException {
        List<Map<String, Object>> originalData = extractData(rs);
        List<String> recoverySqls = buildRecoverySqls(parsedSql, originalData, primaryKeyColumns);
        return new RecoveryResult(
                parsedSql.getSqlType(),
                parsedSql.getTableName(),
                originalData.size(),
                originalData,
                recoverySqls,
                aliasName,
                parsedSql.getOriginalSql()
        );
    }

    /**
     * 从ResultSet提取数据
     */
    private List<Map<String, Object>> extractData(ResultSet rs) throws SQLException {
        List<Map<String, Object>> data = new ArrayList<>();
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();

        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                String columnName = metaData.getColumnLabel(i);
                Object value = rs.getObject(i);
                row.put(columnName, value);
            }
            data.add(row);
        }
        return data;
    }

    /**
     * 构建恢复SQL列表
     */
    private List<String> buildRecoverySqls(ParsedSql parsedSql, List<Map<String, Object>> originalData,
                                           List<String> primaryKeyColumns) throws SQLException {
        List<String> sqls = new ArrayList<>();
        String sqlType = parsedSql.getSqlType();

        for (Map<String, Object> row : originalData) {
            String recoverySql;
            if ("UPDATE".equals(sqlType)) {
                recoverySql = buildUpdateRecovery(parsedSql, row, primaryKeyColumns);
            } else if ("DELETE".equals(sqlType)) {
                recoverySql = buildDeleteRecovery(parsedSql, row);
            } else {
                continue;
            }
            if (recoverySql != null && !recoverySql.isBlank()) {
                sqls.add(recoverySql);
            }
        }
        return sqls;
    }

    /**
     * 构建UPDATE的恢复SQL（反向UPDATE）
     */
    private String buildUpdateRecovery(ParsedSql parsedSql, Map<String, Object> row,
                                       List<String> primaryKeyColumns) throws SQLException {
        if (primaryKeyColumns == null || primaryKeyColumns.isEmpty()) {
            throw new SQLException("UPDATE rejected: target table has no reliable primary key for recovery");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("UPDATE ").append(parsedSql.getTableName()).append(" SET ");

        List<String> setClauses = new ArrayList<>();
        List<String> columns = parsedSql.getColumns();
        for (String column : columns) {
            Object originalValue = valueForColumn(row, column);
            String valueStr = formatValue(originalValue);
            setClauses.add(column + "=" + valueStr);
        }
        sb.append(String.join(", ", setClauses));

        sb.append(" WHERE ").append(buildPrimaryKeyWhere(row, primaryKeyColumns));

        sb.append(";");
        return sb.toString();
    }

    /**
     * 构建DELETE的恢复SQL（INSERT）
     */
    private String buildDeleteRecovery(ParsedSql parsedSql, Map<String, Object> row) {
        StringBuilder sb = new StringBuilder();
        sb.append("INSERT INTO ").append(parsedSql.getTableName()).append(" (");

        List<String> columns = new ArrayList<>(row.keySet());
        sb.append(String.join(", ", columns));

        sb.append(") VALUES (");
        List<String> values = new ArrayList<>();
        for (Object value : row.values()) {
            values.add(formatValue(value));
        }
        sb.append(String.join(", ", values));
        sb.append(");");

        return sb.toString();
    }

    private String buildPrimaryKeyWhere(Map<String, Object> row,
                                        List<String> primaryKeyColumns) throws SQLException {
        List<String> clauses = new ArrayList<>();
        for (String column : primaryKeyColumns) {
            Object value = valueForColumn(row, column);
            if (value == null) {
                throw new SQLException("UPDATE rejected: primary key column '" + column
                        + "' is missing or NULL in recovery data");
            }
            clauses.add(column + "=" + formatValue(value));
        }
        return String.join(" AND ", clauses);
    }

    private Object valueForColumn(Map<String, Object> row, String column) throws SQLException {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(column)) {
                return entry.getValue();
            }
        }
        throw new SQLException("Recovery data is missing required column '" + column + "'");
    }

    /**
     * 格式化值
     */
    private String formatValue(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof String) {
            return "'" + escapeString((String) value) + "'";
        }
        if (value instanceof Number) {
            return String.valueOf(value);
        }
        if (value instanceof java.sql.Date) {
            return "'" + value.toString() + "'";
        }
        if (value instanceof java.sql.Timestamp) {
            return "'" + value.toString() + "'";
        }
        if (value instanceof java.sql.Time) {
            return "'" + value.toString() + "'";
        }
        // 其他类型默认作为字符串处理
        return "'" + escapeString(String.valueOf(value)) + "'";
    }

    /**
     * 转义字符串中的特殊字符
     */
    private String escapeString(String str) {
        if (str == null) return "";
        return str.replace("'", "''");
    }
}
