package com.sqlcli.recovery;

import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.connection.ConnectionManager;
import com.sqlcli.runstate.RunStateStore;
import com.sqlcli.runstate.SqlExecutionRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 恢复执行器的事务边界（设计文档 §17）：全部成功才提交、任一失败整体回滚。
 * 用真 SQLite 验证，不 mock 事务——mock 出来的 rollback 断言不了数据真的回去了。
 */
class RecoveryExecutorTest {

    @TempDir Path temp;

    private RunStateStore runState;

    private String dbUrl() {
        return "jdbc:sqlite:" + temp.resolve("target.db");
    }

    private RecoveryExecutor executor(ConnectionManager manager) {
        runState = new RunStateStore(temp.resolve("sqlcli.db"), temp.resolve("history"));
        return new RecoveryExecutor(manager, runState);
    }

    private ConnectionManager sqliteManager() throws Exception {
        ConnectionManager manager = mock(ConnectionManager.class);
        when(manager.getConnection(any())).thenAnswer(inv -> DriverManager.getConnection(dbUrl()));
        return manager;
    }

    private DatabaseConfig config() {
        DatabaseConfig config = new DatabaseConfig();
        config.setAliasName("demo");
        config.setType("mysql");
        return config;
    }

    private void createTable() throws Exception {
        try (Connection conn = DriverManager.getConnection(dbUrl());
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE t (id INTEGER, name TEXT)");
        }
    }

    private long countRows() throws Exception {
        try (Connection conn = DriverManager.getConnection(dbUrl());
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM t")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** 脚本入库并挂到执行记录上——生产路径就是这两步，测试不该再造第二种形态。 */
    private void attach(long executionId, String rollbackSql) {
        long artifactId = runState.saveRecoveryScript(rollbackSql, "id=1, name='a'");
        runState.attachRecoveryArtifact(artifactId, executionId);
    }

    @Test
    void allStatementsCommitInOneTransactionAndEverythingIsAudited() throws Exception {
        createTable();
        RecoveryExecutor executor = executor(sqliteManager());
        long executionId = runState.recordExecution("demo", "DELETE", "DELETE FROM t WHERE id < 3",
                "success", null, 2L, 5, 1000);
        attach(executionId, """
                INSERT INTO t (id, name) VALUES (1, 'a;b');
                INSERT INTO t (id, name) VALUES (2, 'x''y
                z');
                """);

        RecoveryExecutor.RecoveryScript script = executor.loadScript(executionId);
        assertEquals(2, script.statements().size(), "值里的分号和换行不能切碎语句");
        assertEquals(2, executor.execute(config(), executionId, script));

        assertEquals(2, countRows(), "两条 INSERT 都已提交");
        List<SqlExecutionRow> rows = runState.listExecutions("demo", null, null, null, 50, 0).stream()
                .filter(r -> "recovery".equals(r.source())).toList();
        assertEquals(3, rows.size(), "每条语句一行 + 整体一行");
        assertTrue(rows.stream().allMatch(r -> "success".equals(r.status())));
        assertTrue(rows.stream().allMatch(r -> r.rerunOf() != null && r.rerunOf() == executionId),
                "全部审计行都要挂回原执行记录");
        assertTrue(rows.stream().anyMatch(r -> "ROLLBACK".equals(r.sqlType())), "整体结果行存在");
    }

    @Test
    void anyFailureRollsBackTheWholeTransaction() throws Exception {
        createTable();
        RecoveryExecutor executor = executor(sqliteManager());
        long executionId = runState.recordExecution("demo", "DELETE", "DELETE FROM t",
                "success", null, 1L, 5, 1000);
        attach(executionId, """
                INSERT INTO t (id, name) VALUES (1, 'ok');
                INSERT INTO missing_table (id) VALUES (2);
                """);

        RecoveryExecutor.RecoveryScript script = executor.loadScript(executionId);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> executor.execute(config(), executionId, script));
        assertTrue(e.getMessage().contains("第 2 条"), e.getMessage());
        assertTrue(e.getMessage().contains("已整体回滚"), e.getMessage());

        assertEquals(0, countRows(), "第一条已执行的 INSERT 必须随事务回滚");
        List<SqlExecutionRow> rows = runState.listExecutions("demo", null, null, null, 50, 0).stream()
                .filter(r -> "recovery".equals(r.source())).toList();
        assertEquals(2, rows.size(), "失败语句一行 + 整体一行；被回滚的语句不落 success");
        assertTrue(rows.stream().allMatch(r -> "failed".equals(r.status())));
    }

    @Test
    void readonlyAliasIsRejectedBeforeTouchingTheDatabase() throws Exception {
        ConnectionManager manager = mock(ConnectionManager.class);
        RecoveryExecutor executor = executor(manager);
        DatabaseConfig config = config();
        config.setReadonly(true);
        long executionId = 1;
        RecoveryExecutor.RecoveryScript script = new RecoveryExecutor.RecoveryScript(
                "x.sql", List.of("INSERT INTO t (id) VALUES (1)"), "INSERT INTO t (id) VALUES (1);");

        assertThrows(IllegalArgumentException.class,
                () -> executor.execute(config, executionId, script));
        verify(manager, never()).getConnection(any());
    }

    @Test
    void loadScriptRejectsMissingArtifactAndEmptyScript() throws Exception {
        RecoveryExecutor executor = executor(mock(ConnectionManager.class));

        IllegalArgumentException noArtifact = assertThrows(IllegalArgumentException.class,
                () -> executor.loadScript(42));
        assertTrue(noArtifact.getMessage().contains("没有登记回滚脚本"));

        long empty = runState.recordExecution("demo", "DELETE", "DELETE FROM t WHERE id=2",
                "success", null, 1L, 5, 1000);
        attach(empty, "   ");
        IllegalArgumentException noStatements = assertThrows(IllegalArgumentException.class,
                () -> executor.loadScript(empty));
        assertTrue(noStatements.getMessage().contains("回滚脚本内容为空"));
    }

    /** 脚本存在库里，不落文件——现在这是唯一的形态。 */
    @Test
    void loadScriptReadsInlineScriptFromTheRunStateDatabase() throws Exception {
        RecoveryExecutor executor = executor(mock(ConnectionManager.class));
        long artifactId = runState.saveRecoveryScript("""
                INSERT INTO t (id) VALUES (1);
                INSERT INTO t (id) VALUES (2);""", "id=1\nid=2");
        long executionId = runState.recordExecution("demo", "DELETE", "DELETE FROM t", "success",
                null, 2L, 5, 1000);
        runState.attachRecoveryArtifact(artifactId, executionId);

        RecoveryExecutor.RecoveryScript script = executor.loadScript(executionId);
        assertEquals(2, script.statements().size());
        assertTrue(script.source().contains("运行库"), script.source());
    }

    @Test
    void splitStatementsRespectsQuotes() {
        List<String> statements = RecoveryExecutor.splitStatements(
                "UPDATE t SET name='a;b' WHERE id=1;\nUPDATE t SET name='it''s;fine' WHERE id=2;");
        assertEquals(2, statements.size());
        assertEquals("UPDATE t SET name='a;b' WHERE id=1", statements.get(0));
        assertEquals("UPDATE t SET name='it''s;fine' WHERE id=2", statements.get(1));
        assertTrue(RecoveryExecutor.splitStatements("   \n").isEmpty());
    }
}
