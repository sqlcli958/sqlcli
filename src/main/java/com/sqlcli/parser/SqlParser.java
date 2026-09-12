package com.sqlcli.parser;

import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL解析器
 * 使用JSqlParser解析UPDATE和DELETE语句
 */
public class SqlParser {
    private final SqlStatementAnalyzer statementAnalyzer = new SqlStatementAnalyzer();

    /**
     * 解析SQL语句
     * @param sql SQL语句
     * @return 解析结果
     * @throws SqlParseException 解析失败
     */
    public ParsedSql parse(String sql) throws SqlParseException {
        if (sql == null || sql.isBlank()) {
            throw new SqlParseException("SQL is empty", sql);
        }
        Statement statement = statementAnalyzer.parseStatement(sql);
        return parseStatement(statement, sql);
    }

    /**
     * Reject standard UPDATE/DELETE statements that cannot be safely scoped.
     */
    public void requireWhereClause(String sql) throws SqlParseException {
        ParsedSql parsedSql = parse(sql);
        if (!parsedSql.hasWhereClause()) {
            throw new SqlParseException(
                    parsedSql.getSqlType() + " rejected: WHERE clause is required",
                    null);
        }
    }

    private ParsedSql parseStatement(Statement statement, String originalSql) throws SqlParseException {
        if (statement instanceof Update) {
            return parseUpdate((Update) statement, originalSql);
        } else if (statement instanceof Delete) {
            return parseDelete((Delete) statement, originalSql);
        } else {
            throw new SqlParseException("Unsupported SQL type for recovery: " + statement.getClass().getSimpleName(),
                    originalSql);
        }
    }

    private ParsedSql parseUpdate(Update update, String originalSql) throws SqlParseException {
        Table table = update.getTable();
        if (table == null) {
            throw new SqlParseException("UPDATE statement has no table", originalSql);
        }

        String tableName = table.getFullyQualifiedName();
        // JSqlParser 4.9 使用 getWhere() 而不是 getWhereExpression()
        Expression whereExpression = update.getWhere();
        String whereClause = whereExpression != null ? whereExpression.toString() : null;

        // 检查复杂性
        ComplexityCheckResult complexity = checkComplexity(update, whereExpression);

        // 提取列名和新值
        List<String> columns = new ArrayList<>();
        List<String> values = new ArrayList<>();
        List<Column> columnList = update.getColumns();
        List<Expression> valueList = update.getExpressions();

        if (columnList != null) {
            for (Column col : columnList) {
                columns.add(col.getColumnName());
            }
        }
        if (valueList != null) {
            for (Expression val : valueList) {
                values.add(val.toString());
            }
        }

        return new ParsedSql(
                "UPDATE",
                tableName,
                whereClause,
                columns,
                values,
                complexity.isComplex,
                complexity.reason,
                originalSql
        );
    }

    private ParsedSql parseDelete(Delete delete, String originalSql) throws SqlParseException {
        Table table = delete.getTable();
        if (table == null) {
            throw new SqlParseException("DELETE statement has no table", originalSql);
        }

        String tableName = table.getFullyQualifiedName();
        // JSqlParser 4.9 使用 getWhere() 而不是 getWhereExpression()
        Expression whereExpression = delete.getWhere();
        String whereClause = whereExpression != null ? whereExpression.toString() : null;

        // 检查复杂性
        ComplexityCheckResult complexity = checkComplexity(delete, whereExpression);

        return new ParsedSql(
                "DELETE",
                tableName,
                whereClause,
                null,
                null,
                complexity.isComplex,
                complexity.reason,
                originalSql
        );
    }

    /**
     * 检查SQL是否复杂
     */
    private ComplexityCheckResult checkComplexity(Update update, Expression where) {
        // 检查多表UPDATE
        if (update.getJoins() != null && !update.getJoins().isEmpty()) {
            return new ComplexityCheckResult(true, "UPDATE with JOIN is not supported");
        }

        // 检查FROM子句（多表）
        if (update.getFromItem() != null) {
            return new ComplexityCheckResult(true, "UPDATE with FROM clause is not supported");
        }

        // 检查ORDER BY
        if (update.getOrderByElements() != null && !update.getOrderByElements().isEmpty()) {
            return new ComplexityCheckResult(true, "UPDATE with ORDER BY is not supported");
        }

        // 检查LIMIT
        if (update.getLimit() != null) {
            return new ComplexityCheckResult(true, "UPDATE with LIMIT is not supported");
        }

        // 检查WHERE中的子查询（简化检测）
        if (statementAnalyzer.containsSubSelect(where)) {
            return new ComplexityCheckResult(true, "UPDATE with subquery in WHERE is not supported");
        }

        return new ComplexityCheckResult(false, null);
    }

    private ComplexityCheckResult checkComplexity(Delete delete, Expression where) {
        // 检查多表DELETE
        if (delete.getJoins() != null && !delete.getJoins().isEmpty()) {
            return new ComplexityCheckResult(true, "DELETE with JOIN is not supported");
        }

        // 检查ORDER BY
        if (delete.getOrderByElements() != null && !delete.getOrderByElements().isEmpty()) {
            return new ComplexityCheckResult(true, "DELETE with ORDER BY is not supported");
        }

        // 检查LIMIT
        if (delete.getLimit() != null) {
            return new ComplexityCheckResult(true, "DELETE with LIMIT is not supported");
        }

        // 检查WHERE中的子查询（简化检测）
        if (statementAnalyzer.containsSubSelect(where)) {
            return new ComplexityCheckResult(true, "DELETE with subquery in WHERE is not supported");
        }

        return new ComplexityCheckResult(false, null);
    }

    private static class ComplexityCheckResult {
        final boolean isComplex;
        final String reason;

        ComplexityCheckResult(boolean isComplex, String reason) {
            this.isComplex = isComplex;
            this.reason = reason;
        }
    }
}
