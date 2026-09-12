package com.sqlcli.parser;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.DescribeStatement;
import net.sf.jsqlparser.statement.ExplainStatement;
import net.sf.jsqlparser.statement.ShowColumnsStatement;
import net.sf.jsqlparser.statement.ShowStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.UnsupportedStatement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.grant.Grant;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.show.ShowIndexStatement;
import net.sf.jsqlparser.statement.show.ShowTablesStatement;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class SqlStatementAnalyzer {

    public Statement parseStatement(String sql) throws SqlParseException {
        if (sql == null || sql.isBlank()) {
            throw new SqlParseException("SQL is empty", sql);
        }
        try {
            return CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException e) {
            throw new SqlParseException("Failed to parse SQL: " + e.getMessage(), sql, e);
        }
    }

    public List<String> splitStatements(String sql) {
        if (sql == null || sql.isBlank()) {
            return List.of();
        }
        try {
            Statements statements = CCJSqlParserUtil.parseStatements(sql);
            List<String> result = new ArrayList<>();
            for (Statement statement : statements) {
                if (statement != null) {
                    String normalized = statement.toString().trim();
                    if (!normalized.isEmpty()) {
                        result.add(normalized);
                    }
                }
            }
            if (!result.isEmpty()) {
                return result;
            }
        } catch (JSQLParserException ignored) {
            // Fallback for unsupported dialect fragments.
        }
        return splitBySemicolonAware(sql.trim());
    }

    public List<StatementSlice> splitStatementsWithLines(String sql) {
        if (sql == null || sql.isBlank()) return List.of();
        List<String> statements = splitBySemicolonAware(sql);
        List<StatementSlice> result = new ArrayList<>();
        int cursor = 0;
        int line = 1;
        for (String statement : statements) {
            int start = sql.indexOf(statement, cursor);
            if (start < 0) start = cursor;
            for (int i = cursor; i < start; i++) {
                if (sql.charAt(i) == '\n') line++;
            }
            result.add(new StatementSlice(statement, line));
            cursor = start + statement.length();
        }
        return List.copyOf(result);
    }

    public record StatementSlice(String sql, int line) {
    }

    public String detectSqlType(String sql) {
        try {
            Statement statement = parseStatement(sql);
            // JSqlParser 建模不了的语句会返回 UnsupportedStatement，而这算「解析成功」，
            // 走不到下面的 catch，于是一路落到 OTHER。后果不只是标签难看：
            // isWriteOperation("OTHER") 是 false，方言写语句会绕过 readonly 保护和更新审批。
            // 认不出结构时和解析失败一样，退回按首个关键字判断。
            if (statement instanceof UnsupportedStatement) {
                return fallbackSqlType(sql);
            }
            return detectSqlType(statement);
        } catch (SqlParseException e) {
            return fallbackSqlType(sql);
        }
    }

    public String detectSqlType(Statement statement) {
        Objects.requireNonNull(statement, "statement");
        if (statement instanceof Select select) {
            return select.getWithItemsList() != null && !select.getWithItemsList().isEmpty()
                    ? "WITH" : "SELECT";
        }
        if (statement instanceof Insert) {
            return "INSERT";
        }
        if (statement instanceof Update) {
            return "UPDATE";
        }
        if (statement instanceof Delete) {
            return "DELETE";
        }
        if (statement instanceof Merge) {
            return "MERGE";
        }
        // JSqlParser 把 SHOW 拆成四个互不相干的类：ShowStatement 只管 `SHOW <变量>`，
        // SHOW TABLES / COLUMNS / INDEX 各有各的类。只判 ShowStatement 会让
        // `SHOW TABLES` 落到 OTHER —— 于是只读白名单把它当非只读语句拒掉。
        if (statement instanceof ShowStatement
                || statement instanceof ShowTablesStatement
                || statement instanceof ShowColumnsStatement
                || statement instanceof ShowIndexStatement) {
            return "SHOW";
        }
        if (statement instanceof DescribeStatement describeStatement) {
            return describeStatement.getDescribeType() == null || describeStatement.getDescribeType().isBlank()
                    ? "DESCRIBE"
                    : describeStatement.getDescribeType().toUpperCase(Locale.ROOT);
        }
        if (statement instanceof ExplainStatement) {
            return "EXPLAIN";
        }
        if (statement instanceof Truncate) {
            return "TRUNCATE";
        }
        if (statement instanceof Grant) {
            return "GRANT";
        }
        String simpleName = statement.getClass().getSimpleName();
        if (simpleName.startsWith("Create")) {
            return "CREATE";
        }
        if (simpleName.startsWith("Alter")) {
            return "ALTER";
        }
        if (simpleName.startsWith("Drop")) {
            return "DROP";
        }
        if (simpleName.startsWith("Revoke")) {
            return "REVOKE";
        }
        return "OTHER";
    }

    public boolean containsSubSelect(Expression expression) {
        if (expression == null) {
            return false;
        }
        SubSelectFinder finder = new SubSelectFinder();
        expression.accept(finder);
        return finder.found();
    }

    private String fallbackSqlType(String sql) {
        if (sql == null || sql.isBlank()) {
            return "OTHER";
        }
        String normalized = sql.stripLeading().toUpperCase(Locale.ROOT);
        int idx = normalized.indexOf(' ');
        return idx > 0 ? normalized.substring(0, idx) : normalized;
    }

    private static final class SubSelectFinder extends ExpressionVisitorAdapter {
        private boolean found;

        @Override
        public void visit(Select select) {
            found = true;
        }

        @Override
        public void visit(ParenthesedSelect parenthesedSelect) {
            found = true;
        }

        boolean found() {
            return found;
        }
    }

    /**
     * Split SQL by semicolons, respecting quoted strings and comments.
     * Semicolons inside single-quoted, double-quoted, or backtick-quoted strings,
     * as well as inside line comments (--) and block comments (* /* * /), are not treated as statement separators.
     */
    private List<String> splitBySemicolonAware(String sql) {
        if (sql == null || sql.isEmpty()) {
            return List.of();
        }
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int len = sql.length();
        int i = 0;

        while (i < len) {
            char c = sql.charAt(i);

            // Single-quoted string
            if (c == '\'') {
                current.append(c);
                i++;
                while (i < len) {
                    char ic = sql.charAt(i);
                    current.append(ic);
                    if (ic == '\'' && i + 1 < len && sql.charAt(i + 1) == '\'') {
                        // escaped single quote ''
                        current.append(sql.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    if (ic == '\'') {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }

            // Double-quoted string
            if (c == '"') {
                current.append(c);
                i++;
                while (i < len) {
                    char ic = sql.charAt(i);
                    current.append(ic);
                    if (ic == '"' && i + 1 < len && sql.charAt(i + 1) == '"') {
                        // escaped double quote ""
                        current.append(sql.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    if (ic == '"') {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }

            // Backtick-quoted identifier
            if (c == '`') {
                current.append(c);
                i++;
                while (i < len) {
                    char ic = sql.charAt(i);
                    current.append(ic);
                    if (ic == '`') {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }

            // Line comment --
            if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                current.append(c);
                i++;
                while (i < len) {
                    char ic = sql.charAt(i);
                    current.append(ic);
                    if (ic == '\n') {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }

            // Block comment /* */
            if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                current.append(c);
                i++;
                while (i < len) {
                    char ic = sql.charAt(i);
                    current.append(ic);
                    if (ic == '*' && i + 1 < len && sql.charAt(i + 1) == '/') {
                        current.append(sql.charAt(i + 1));
                        i += 2;
                        break;
                    }
                    i++;
                }
                continue;
            }

            // Semicolon — statement separator
            if (c == ';') {
                String stmt = current.toString().trim();
                if (!stmt.isEmpty()) {
                    statements.add(stmt);
                }
                current.setLength(0);
                i++;
                continue;
            }

            current.append(c);
            i++;
        }

        String last = current.toString().trim();
        if (!last.isEmpty()) {
            statements.add(last);
        }

        return statements.isEmpty() ? List.of() : statements;
    }
}
