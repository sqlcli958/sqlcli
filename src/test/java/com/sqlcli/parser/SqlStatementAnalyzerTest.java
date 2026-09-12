package com.sqlcli.parser;

import net.sf.jsqlparser.statement.delete.Delete;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlStatementAnalyzerTest {
    private final SqlStatementAnalyzer analyzer = new SqlStatementAnalyzer();

    @Test
    @DisplayName("按 AST 拆分多条 SQL，忽略字符串中的分号")
    void splitStatements_handlesSemicolonInLiteral() {
        List<String> statements = analyzer.splitStatements(
                "SELECT 'a;b' AS v; INSERT INTO t(id, name) VALUES (1, 'x;y');");

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("'a;b'"));
        assertTrue(statements.get(1).contains("'x;y'"));
    }

    @Test
    void splitStatementsKeepsCommentsAndProcedureBodySemicolonsInsideOneStatement() {
        assertEquals(1, analyzer.splitStatements(
                "-- setup; remains a comment\nSELECT 'value;still-one'").size());
        assertEquals(1, analyzer.splitStatements(
                "CREATE PROCEDURE p() BEGIN SELECT 1; SELECT 2; END;").size());
        assertEquals(1, analyzer.splitStatements(
                "CREATE FUNCTION f() RETURNS void AS $$ BEGIN PERFORM 1; END; $$ LANGUAGE plpgsql;")
                .size());
    }

    @Test
    @DisplayName("识别带 CTE 的 WITH 语句")
    void detectSqlType_withStatement() {
        assertEquals("WITH", analyzer.detectSqlType("WITH t AS (SELECT 1) SELECT * FROM t"));
    }

    @Test
    @DisplayName("SHOW 的各种写法都识别成 SHOW")
    void detectSqlType_showVariants() {
        // JSqlParser 给前三种各建了一个类，漏判会让语句落到 OTHER、被只读白名单拒掉
        assertEquals("SHOW", analyzer.detectSqlType("SHOW TABLES"));
        assertEquals("SHOW", analyzer.detectSqlType("SHOW COLUMNS FROM users"));
        assertEquals("SHOW", analyzer.detectSqlType("SHOW INDEX FROM users"));
        // 这条 JSqlParser 建模不了，返回的是 UnsupportedStatement。
        // 那算「解析成功」、走不到解析失败的兜底，所以要单独退回按首关键字判断——
        // 落成 OTHER 时 isWriteOperation 返回 false，方言写语句就绕过了
        // readonly 保护和更新审批，不只是标签难看的问题。
        assertEquals("SHOW", analyzer.detectSqlType("SHOW @@version"));
    }

    @Test
    @DisplayName("AST 识别 WHERE 子查询")
    void containsSubSelect_detectsNestedSelect() throws SqlParseException {
        Delete delete = (Delete) analyzer.parseStatement("DELETE FROM t WHERE id IN (SELECT id FROM x)");
        assertTrue(analyzer.containsSubSelect(delete.getWhere()));
    }

    @Test
    @DisplayName("普通条件不应识别为子查询")
    void containsSubSelect_falseForSimpleWhere() throws SqlParseException {
        Delete delete = (Delete) analyzer.parseStatement("DELETE FROM t WHERE id IN (1, 2)");
        assertFalse(analyzer.containsSubSelect(delete.getWhere()));
    }
}
