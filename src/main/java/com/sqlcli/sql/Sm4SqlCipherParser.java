package com.sqlcli.sql;

import com.sqlcli.connection.QueryExecutionOptions;
import com.sqlcli.crypto.Sm4Config;
import com.sqlcli.parser.SqlParseException;
import com.sqlcli.parser.SqlStatementAnalyzer;
import com.sqlcli.util.SM4Utils;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 基于 JSqlParser AST 的 SM4 SQL 改写器。
 */
public class Sm4SqlCipherParser implements SqlCipherParser {
    private final SqlStatementAnalyzer statementAnalyzer = new SqlStatementAnalyzer();

    @Override
    public String rewrite(String sql, QueryExecutionOptions options) {
        if (sql == null || sql.isBlank() || options == null || !options.hasCipherColumns()) {
            return sql;
        }
        try {
            Statement statement = statementAnalyzer.parseStatement(sql);
            rewriteStatement(statement, options);
            return statement.toString();
        } catch (SqlParseException e) {
            return sql;
        }
    }

    private void rewriteStatement(Statement statement, QueryExecutionOptions options) {
        if (statement instanceof Select select) {
            rewriteSelect(select, options);
        } else if (statement instanceof Update update) {
            rewriteUpdate(update, options);
        } else if (statement instanceof Delete delete) {
            rewriteExpression(delete.getWhere(), options);
        } else if (statement instanceof Insert insert) {
            rewriteInsert(insert, options);
        }
    }

    private void rewriteSelect(Select select, QueryExecutionOptions options) {
        if (select.getWithItemsList() != null) {
            for (WithItem withItem : select.getWithItemsList()) {
                rewriteSelect(withItem, options);
            }
        }
        if (select instanceof PlainSelect plainSelect) {
            rewritePlainSelect(plainSelect, options);
        } else if (select instanceof SetOperationList setOperationList) {
            if (setOperationList.getSelects() != null) {
                for (Select child : setOperationList.getSelects()) {
                    rewriteSelect(child, options);
                }
            }
        } else if (select instanceof ParenthesedSelect parenthesedSelect && parenthesedSelect.getSelect() != null) {
            rewriteSelect(parenthesedSelect.getSelect(), options);
        }
    }

    private void rewritePlainSelect(PlainSelect plainSelect, QueryExecutionOptions options) {
        rewriteExpression(plainSelect.getWhere(), options);
        rewriteExpression(plainSelect.getHaving(), options);
        if (plainSelect.getFromItem() instanceof ParenthesedSelect parenthesedSelect && parenthesedSelect.getSelect() != null) {
            rewriteSelect(parenthesedSelect.getSelect(), options);
        }
        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                if (join.getRightItem() instanceof ParenthesedSelect parenthesedSelect && parenthesedSelect.getSelect() != null) {
                    rewriteSelect(parenthesedSelect.getSelect(), options);
                }
                if (join.getOnExpressions() != null) {
                    for (Expression onExpression : join.getOnExpressions()) {
                        rewriteExpression(onExpression, options);
                    }
                } else {
                    rewriteExpression(join.getOnExpression(), options);
                }
            }
        }
    }

    private void rewriteUpdate(Update update, QueryExecutionOptions options) {
        if (update.getUpdateSets() != null) {
            for (UpdateSet updateSet : update.getUpdateSets()) {
                rewriteUpdateSet(updateSet, options);
            }
        }
        rewriteExpression(update.getWhere(), options);
    }

    private void rewriteUpdateSet(UpdateSet updateSet, QueryExecutionOptions options) {
        if (updateSet.getColumns() == null || updateSet.getValues() == null) {
            return;
        }
        List<Column> columns = updateSet.getColumns();
        @SuppressWarnings("unchecked")
        List<Expression> values = (List<Expression>) updateSet.getValues();
        int size = Math.min(columns.size(), values.size());
        for (int i = 0; i < size; i++) {
            Expression rewritten = rewriteAssignmentValue(columns.get(i), values.get(i), options);
            values.set(i, rewritten);
        }
    }

    private void rewriteInsert(Insert insert, QueryExecutionOptions options) {
        if (insert.getColumns() != null && insert.isUseValues()) {
            rewriteInsertValues(insert.getColumns(), insert.getSelect(), options);
        }
        if (insert.getDuplicateUpdateSets() != null) {
            for (UpdateSet updateSet : insert.getDuplicateUpdateSets()) {
                rewriteUpdateSet(updateSet, options);
            }
        }
        if (insert.getSetUpdateSets() != null) {
            for (UpdateSet updateSet : insert.getSetUpdateSets()) {
                rewriteUpdateSet(updateSet, options);
            }
        }
        Select select = insert.getSelect();
        if (select != null && !(select instanceof Values)) {
            rewriteSelect(select, options);
        }
    }

    private void rewriteInsertValues(ExpressionList<Column> columns, Select select, QueryExecutionOptions options) {
        if (!(select instanceof Values values) || values.getExpressions() == null) {
            return;
        }
        ExpressionList<?> expressions = values.getExpressions();
        if (expressions.isEmpty()) {
            return;
        }

        Object first = expressions.get(0);
        if (first instanceof ParenthesedExpressionList) {
            for (Object item : expressions) {
                if (item instanceof ParenthesedExpressionList<?> tuple) {
                    rewriteTuple(columns, tuple, options);
                }
            }
            return;
        }

        @SuppressWarnings("unchecked")
        ExpressionList<Expression> tuple = (ExpressionList<Expression>) expressions;
        rewriteTuple(columns, tuple, options);
    }

    private void rewriteTuple(ExpressionList<Column> columns, ExpressionList<?> tuple, QueryExecutionOptions options) {
        int size = Math.min(columns.size(), tuple.size());
        for (int i = 0; i < size; i++) {
            Object value = tuple.get(i);
            if (value instanceof Expression expression) {
                Expression rewritten = rewriteAssignmentValue(columns.get(i), expression, options);
                @SuppressWarnings("unchecked")
                ExpressionList<Expression> rawTuple = (ExpressionList<Expression>) tuple;
                rawTuple.set(i, rewritten);
            }
        }
    }

    private Expression rewriteAssignmentValue(Column column, Expression value, QueryExecutionOptions options) {
        rewriteExpression(value, options);
        if (!isCipherColumn(column, options.getCipherColumns()) || !(value instanceof StringValue stringValue)) {
            return value;
        }
        stringValue.setValue(encryptIfNeeded(stringValue.getNotExcapedValue(), options.getSm4Config()));
        return stringValue;
    }

    private void rewriteExpression(Expression expression, QueryExecutionOptions options) {
        if (expression == null) {
            return;
        }
        if (expression instanceof Parenthesis parenthesis) {
            rewriteExpression(parenthesis.getExpression(), options);
            return;
        }
        if (expression instanceof NotExpression notExpression) {
            rewriteExpression(notExpression.getExpression(), options);
            return;
        }
        if (expression instanceof Between between) {
            rewriteExpression(between.getLeftExpression(), options);
            rewriteExpression(between.getBetweenExpressionStart(), options);
            rewriteExpression(between.getBetweenExpressionEnd(), options);
            return;
        }
        if (expression instanceof InExpression inExpression) {
            rewriteInExpression(inExpression, options);
            return;
        }
        if (expression instanceof BinaryExpression binaryExpression) {
            rewriteBinaryExpression(binaryExpression, options);
            return;
        }
        if (expression instanceof Function function && function.getParameters() != null) {
            rewriteExpression(function.getParameters(), options);
            return;
        }
        if (expression instanceof ExpressionList<?> expressionList) {
            for (Object item : expressionList) {
                if (item instanceof Expression child) {
                    rewriteExpression(child, options);
                }
            }
        }
    }

    private void rewriteInExpression(InExpression inExpression, QueryExecutionOptions options) {
        rewriteExpression(inExpression.getLeftExpression(), options);
        Expression right = inExpression.getRightExpression();
        if (isCipherColumnExpression(inExpression.getLeftExpression(), options.getCipherColumns())
                && right instanceof ExpressionList<?> expressionList) {
            rewriteLiteralList(expressionList, options);
            return;
        }
        rewriteExpression(right, options);
    }

    private void rewriteBinaryExpression(BinaryExpression expression, QueryExecutionOptions options) {
        rewriteExpression(expression.getLeftExpression(), options);
        rewriteExpression(expression.getRightExpression(), options);

        if (!(expression instanceof EqualsTo
                || expression instanceof NotEqualsTo
                || expression instanceof LikeExpression
                || expression instanceof AndExpression
                || expression instanceof OrExpression)) {
            return;
        }
        if (expression instanceof AndExpression || expression instanceof OrExpression) {
            return;
        }

        Expression left = expression.getLeftExpression();
        Expression right = expression.getRightExpression();
        if (isCipherColumnExpression(left, options.getCipherColumns()) && right instanceof StringValue stringValue) {
            stringValue.setValue(encryptIfNeeded(stringValue.getNotExcapedValue(), options.getSm4Config()));
            return;
        }
        if (isCipherColumnExpression(right, options.getCipherColumns()) && left instanceof StringValue stringValue) {
            stringValue.setValue(encryptIfNeeded(stringValue.getNotExcapedValue(), options.getSm4Config()));
        }
    }

    private void rewriteLiteralList(ExpressionList<?> expressionList, QueryExecutionOptions options) {
        @SuppressWarnings("unchecked")
        ExpressionList<Expression> rawList = (ExpressionList<Expression>) expressionList;
        for (int i = 0; i < rawList.size(); i++) {
            Expression value = rawList.get(i);
            rewriteExpression(value, options);
            if (value instanceof StringValue stringValue) {
                stringValue.setValue(encryptIfNeeded(stringValue.getNotExcapedValue(), options.getSm4Config()));
            }
        }
    }

    private boolean isCipherColumnExpression(Expression expression, Set<String> cipherColumns) {
        return expression instanceof Column column && isCipherColumn(column, cipherColumns);
    }

    private boolean isCipherColumn(Column column, Set<String> cipherColumns) {
        if (column == null || cipherColumns == null || cipherColumns.isEmpty()) {
            return false;
        }
        String name = column.getColumnName();
        if (name == null || name.isBlank()) {
            return false;
        }
        return cipherColumns.contains(normalizeColumn(name));
    }

    private String normalizeColumn(String column) {
        return column.replace("`", "")
                .replace("\"", "")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private String encryptIfNeeded(String value, Sm4Config sm4Config) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return SM4Utils.encrypt(sm4Config.getKey(), sm4Config.getPrivateTag(), sm4Config.getVersion(), value);
    }
}
