package com.sqlcli.graph.eval;

import com.sqlcli.parser.SqlStatementAnalyzer;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.*;

import java.util.*;

/** 任务评分的静态 SQL 证据；只认语法树中的对象与必要条件，不读取作答说明或注释。 */
final class TaskSqlEvidence {
    private final Map<String, String> aliases = new LinkedHashMap<>();
    private final Set<String> tables = new LinkedHashSet<>();
    private final Set<String> columns = new LinkedHashSet<>();
    private final List<Expression> predicates = new ArrayList<>();
    private final List<Expression> filters = new ArrayList<>();
    private final List<Expression> observedPredicates = new ArrayList<>();

    static TaskSqlEvidence parse(String sql) throws Exception {
        var statement = new SqlStatementAnalyzer().parseStatement(sql);
        // shortcut: 目前只检查单层 SELECT；加入作用域分析后再支持 CTE、子查询和集合运算。
        if (!(statement instanceof PlainSelect select)
                || select.getWithItemsList() != null && !select.getWithItemsList().isEmpty()) {
            throw new IllegalArgumentException("SQL 检查目前支持单层 SELECT；CTE、子查询和集合运算需外部验证");
        }
        TaskSqlEvidence evidence = new TaskSqlEvidence();
        evidence.addTable(select.getFromItem());
        if (select.getJoins() != null) {
            for (Join join : select.getJoins()) {
                if (join.isNatural() || join.getUsingColumns() != null && !join.getUsingColumns().isEmpty()) {
                    throw new IllegalArgumentException("NATURAL/USING JOIN 需要字段解析，当前检查未完成");
                }
                evidence.addTable(join.getRightItem());
            }
            for (Join join : select.getJoins()) {
                for (Expression on : join.getOnExpressions()) evidence.condition(on, false);
            }
        }
        for (SelectItem<?> item : select.getSelectItems()) evidence.references(item.getExpression());
        evidence.condition(select.getWhere(), true);
        evidence.condition(select.getHaving(), true);
        if (select.getGroupBy() != null && select.getGroupBy().getGroupByExpressionList() != null) {
            for (Object expr : select.getGroupBy().getGroupByExpressionList()) evidence.references((Expression) expr);
        }
        if (select.getOrderByElements() != null) {
            for (OrderByElement order : select.getOrderByElements()) evidence.references(order.getExpression());
        }
        return evidence;
    }

    private void addTable(FromItem from) {
        if (!(from instanceof Table table)) throw new IllegalArgumentException("SQL 检查需要明确的实体表，不支持派生表");
        String name = identifier(table.getFullyQualifiedName());
        if (!tables.add(name)) throw new IllegalArgumentException("自关联需要区分同表角色，当前检查未完成");
        aliases.put(name, name);
        if (table.getAlias() != null) aliases.put(identifier(table.getAlias().getName()), name);
    }

    private void references(Expression expression) {
        if (expression == null) return;
        expression.accept(new ExpressionVisitorAdapter() {
            @Override public void visit(Column column) { columns.add(column(column)); }
            @Override protected void visitBinaryExpression(BinaryExpression expression) {
                observedPredicates.add(expression);
                super.visitBinaryExpression(expression);
            }
            @Override public void visit(IsNullExpression expression) {
                observedPredicates.add(expression);
                super.visit(expression);
            }
            @Override public void visit(Select select) { throw new IllegalArgumentException("子查询检查未完成"); }
            @Override public void visit(ParenthesedSelect select) { throw new IllegalArgumentException("子查询检查未完成"); }
        });
    }

    private void condition(Expression expression, boolean filter) {
        if (expression == null) return;
        references(expression);
        expression = unwrap(expression);
        if (expression instanceof AndExpression and) {
            condition(and.getLeftExpression(), filter);
            condition(and.getRightExpression(), filter);
        } else {
            // OR/NOT 内的子条件不保证成立，不能拿其中一支冒充必要过滤或 JOIN。
            predicates.add(expression);
            if (filter) filters.add(expression);
        }
    }

    boolean hasTable(String expected) {
        return tables.stream().anyMatch(actual -> matchesIdentifier(actual, expected));
    }

    boolean hasJoin(String left, String right) {
        return predicates.stream().filter(EqualsTo.class::isInstance).map(EqualsTo.class::cast)
                .anyMatch(eq -> eq.getLeftExpression() instanceof Column a && eq.getRightExpression() instanceof Column b
                        && (endpoint(a, left) && endpoint(b, right) || endpoint(a, right) && endpoint(b, left)));
    }

    boolean hasJoinExpression(String expected) throws Exception {
        Expression wanted = unwrap(CCJSqlParserUtil.parseCondExpression(expected));
        if (!(wanted instanceof EqualsTo equals) || !(equals.getLeftExpression() instanceof Column)
                || !(equals.getRightExpression() instanceof Column)) {
            throw new IllegalArgumentException("JOIN 判据需要列间等式或箭头: " + expected);
        }
        return predicates.stream().anyMatch(actual -> samePredicate(actual, wanted));
    }

    private boolean endpoint(Column actual, String expected) {
        String ref = column(actual);
        String table = ref.substring(0, ref.lastIndexOf('.'));
        return matchesIdentifier(ref, expected) || matchesIdentifier(table, expected);
    }

    boolean contains(String expected, boolean required) throws Exception {
        String trimmed = expected.trim();
        if (trimmed.matches("[\\p{L}_][\\p{L}\\p{N}_.$]*")) {
            if (!required && trimmed.endsWith("_")) {
                String prefix = identifier(trimmed);
                return tables.stream().anyMatch(value -> value.substring(value.lastIndexOf('.') + 1).startsWith(prefix));
            }
            return tables.stream().anyMatch(value -> matchesIdentifier(value, trimmed))
                    || columns.stream().anyMatch(value -> matchesIdentifier(value, trimmed));
        }
        Expression wanted = unwrap(CCJSqlParserUtil.parseCondExpression(trimmed));
        if (!(wanted instanceof BinaryExpression) && !(wanted instanceof IsNullExpression)) {
            throw new IllegalArgumentException("当前无法检查条件: " + expected);
        }
        for (Expression actual : required ? filters : observedPredicates) if (samePredicate(actual, wanted)) return true;
        return false;
    }

    private boolean samePredicate(Expression actual, Expression wanted) {
        if (actual instanceof BinaryExpression a && wanted instanceof BinaryExpression b
                && a.getClass().equals(b.getClass())) {
            boolean forward = sameOperand(a.getLeftExpression(), b.getLeftExpression())
                    && sameOperand(a.getRightExpression(), b.getRightExpression());
            return forward || a instanceof EqualsTo
                    && sameOperand(a.getLeftExpression(), b.getRightExpression())
                    && sameOperand(a.getRightExpression(), b.getLeftExpression());
        }
        return actual instanceof IsNullExpression a && wanted instanceof IsNullExpression b
                && a.isNot() == b.isNot() && sameOperand(a.getLeftExpression(), b.getLeftExpression());
    }

    private boolean sameOperand(Expression actual, Expression expected) {
        actual = unwrap(actual);
        expected = unwrap(expected);
        if (actual instanceof Column a && expected instanceof Column b) {
            return matchesIdentifier(column(a), b.getFullyQualifiedName());
        }
        if (actual instanceof StringValue a && expected instanceof StringValue b) return a.getValue().equals(b.getValue());
        return actual.getClass().equals(expected.getClass()) && actual.toString().equals(expected.toString());
    }

    private String column(Column column) {
        String name = identifier(column.getColumnName());
        String qualifier = column.getTable() == null ? "" : identifier(column.getTable().getFullyQualifiedName());
        if (qualifier.isBlank()) {
            if (tables.size() != 1) throw new IllegalArgumentException("多表 SQL 的字段缺少表限定，无法准确判定: " + name);
            return tables.iterator().next() + "." + name;
        }
        String resolved = aliases.get(qualifier);
        if (resolved == null) {
            List<String> matches = tables.stream().filter(table -> matchesIdentifier(table, qualifier)).toList();
            if (matches.size() != 1) throw new IllegalArgumentException("无法解析 SQL 表限定: " + qualifier);
            resolved = matches.get(0);
        }
        return resolved + "." + name;
    }

    static boolean matchesIdentifier(String actual, String expected) {
        String a = identifier(actual), e = identifier(expected);
        return a.equals(e) || a.endsWith("." + e);
    }

    private static String identifier(String text) {
        return text == null ? "" : text.replace("`", "").replace("\"", "")
                .replace("[", "").replace("]", "").trim().toLowerCase(Locale.ROOT);
    }

    private static Expression unwrap(Expression expression) {
        while (expression instanceof Parenthesis parenthesis) expression = parenthesis.getExpression();
        return expression;
    }
}
