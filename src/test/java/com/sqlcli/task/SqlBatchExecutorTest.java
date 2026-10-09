package com.sqlcli.task;

import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.connection.ConnectionManager;
import com.sqlcli.graph.policy.PolicyRuleSetManager;
import com.sqlcli.graph.policy.RuleSetLoader;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sqlcli.runstate.ApprovalBatchRow;
import com.sqlcli.runstate.ApprovalRow;
import com.sqlcli.runstate.RunStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SQL 批次的事务边界：按 seq 执行，任一条失败整批回滚，**一条都不生效**。
 *
 * <p>用真 SQLite 验证。mock 出来的 rollback 断言不了数据真的回去了，
 * 而「多条语句改一半」正是这条路径存在的全部理由。
 */
class SqlBatchExecutorTest {

    @TempDir Path temp;

    private RunStateStore runState;
    private SqlBatchExecutor executor;

    private String dbUrl() {
        return "jdbc:sqlite:" + temp.resolve("target.db");
    }

    @BeforeEach
    void setUp() throws Exception {
        runState = new RunStateStore(temp.resolve("sqlcli.db"), temp.resolve("history"));
        ConnectionManager manager = mock(ConnectionManager.class);
        when(manager.getConnection(any())).thenAnswer(inv -> DriverManager.getConnection(dbUrl()));
        executor = new SqlBatchExecutor(manager, runState);
        try (Connection conn = DriverManager.getConnection(dbUrl());
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
            st.execute("INSERT INTO t VALUES (1, 'a'), (2, 'b')");
        }
    }

    private DatabaseConfig config() {
        DatabaseConfig config = new DatabaseConfig();
        config.setAliasName("demo");
        config.setType("sqlite");
        return config;
    }

    /** 条目按生产路径建：draft 进批次，submit 之后才是 pending。 */
    private List<ApprovalRow> stage(long batchId, String... statements) {
        for (int i = 0; i < statements.length; i++) {
            runState.createApproval("demo", "update", statements[i], statements[i], null, null, null,
                    ApprovalRow.STATUS_DRAFT, batchId, i + 1);
        }
        runState.submitBatch(batchId, true);
        return runState.listBatchItems(batchId);
    }

    private String nameOf(int id) throws Exception {
        try (Connection conn = DriverManager.getConnection(dbUrl());
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM t WHERE id = " + id)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Test
    void everyStatementLandsInSeqOrder() throws Exception {
        long batchId = runState.createBatch("demo", ApprovalBatchRow.KIND_SQL, "改两行");
        List<ApprovalRow> items = stage(batchId,
                "UPDATE t SET name = 'x' WHERE id = 1",
                "UPDATE t SET name = 'y' WHERE id = 2");

        SqlBatchExecutor.BatchResult result = executor.execute(config(), batchId, items);

        assertEquals("x", nameOf(1));
        assertEquals("y", nameOf(2));
        assertEquals(2, result.items().size());
        assertNotNull(result.items().get(0).executionId(), "每条都要落一行执行记录");
        assertNotNull(result.recoveryId(), "UPDATE 批次要有回滚脚本");
    }

    @Test
    void batchChecksTheWholeDdlCandidateBeforeAnyStatementExecutes() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp.resolve("graphs"));
        store.save(GraphWorkspace.create("demo", "sqlite"));
        Path rules = temp.resolve("pk.yaml");
        Files.writeString(rules, """
                kind: PolicyRuleSet
                id: structure
                title: 主键要求
                version: "1"
                rules:
                  - id: pk
                    title: 主键
                    category: primary_key_required
                    severity: error
                    enforcement: required
                """);
        PolicyRuleSetManager ruleSets = new PolicyRuleSetManager(store);
        ruleSets.create("demo", "structure.yaml", new RuleSetLoader().load(rules));
        ruleSets.setBound("demo", "structure.yaml", true);
        ConnectionManager disconnected = mock(ConnectionManager.class);
        long rejectedId = runState.createBatch("demo", ApprovalBatchRow.KIND_SQL, "不合规建表");
        List<ApprovalRow> rejected = stage(rejectedId,
                "UPDATE t SET name = 'changed' WHERE id = 1", "CREATE TABLE main.invoice (amount NUMERIC)");
        SqlTaskRejected error = assertThrows(SqlTaskRejected.class,
                () -> new SqlBatchExecutor(disconnected, runState, store).execute(config(), rejectedId, rejected));
        assertTrue(error.getMessage().contains("primary key"), error.getMessage());
        verify(disconnected, never()).getConnection(any());
        assertEquals("a", nameOf(1));

        ConnectionManager connected = mock(ConnectionManager.class);
        when(connected.getConnection(any())).thenAnswer(inv -> DriverManager.getConnection(dbUrl()));
        long validId = runState.createBatch("demo", ApprovalBatchRow.KIND_SQL, "合规建表与加列");
        List<ApprovalRow> valid = stage(validId, "CREATE TABLE main.invoice (id INTEGER PRIMARY KEY)",
                "ALTER TABLE main.invoice ADD COLUMN amount NUMERIC");
        assertEquals(2, new SqlBatchExecutor(connected, runState, store)
                .execute(config(), validId, valid).items().size());
        try (Connection conn = DriverManager.getConnection(dbUrl()); Statement st = conn.createStatement()) {
            st.executeQuery("SELECT id, amount FROM invoice").close();
        }
        assertTrue(store.load("demo").getTables().isEmpty(), "检查候选结构不应写入正式图谱");
    }

    @Test
    void aliasedUpdateWithExistsKeepsItsRecoveryScript() throws Exception {
        try (Connection conn = DriverManager.getConnection(dbUrl());
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE gate (id INTEGER PRIMARY KEY, enabled INTEGER)");
            st.execute("INSERT INTO gate VALUES (1, 1)");
        }
        long batchId = runState.createBatch("demo", ApprovalBatchRow.KIND_SQL, "EXISTS UPDATE");
        List<ApprovalRow> items = stage(batchId, """
                UPDATE t AS target SET name = 'x'
                WHERE target.id = 1
                  AND EXISTS (SELECT 1 FROM gate g WHERE g.id = target.id AND g.enabled = 1)
                  AND EXISTS (SELECT 1 FROM gate g WHERE g.id = 1)
                """);

        SqlBatchExecutor.BatchResult result = executor.execute(config(), batchId, items);

        assertEquals("x", nameOf(1));
        assertTrue(runState.findRecovery(result.items().get(0).executionId())
                .rollbackSql().contains("UPDATE t SET name='a' WHERE id=1;"));
    }

    @Test
    void aLaterFailureRollsBackTheEarlierStatements() throws Exception {
        long batchId = runState.createBatch("demo", ApprovalBatchRow.KIND_SQL, "第二条会炸");
        List<ApprovalRow> items = stage(batchId,
                "UPDATE t SET name = 'x' WHERE id = 1",
                "UPDATE no_such_table SET name = 'y'");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> executor.execute(config(), batchId, items));

        assertTrue(error.getMessage().contains("第 2 条"), error.getMessage());
        assertEquals("a", nameOf(1), "第一条必须跟着回滚——半批生效是这条路径要消掉的那件事");
    }

    @Test
    void decidingTheBatchInTheApprovalCentreRunsIt() throws Exception {
        long batchId = runState.createBatch("demo", ApprovalBatchRow.KIND_SQL, "审批中心批准即执行");
        stage(batchId, "UPDATE t SET name = 'x' WHERE id = 1");
        var effects = new com.sqlcli.graph.ui.service.ApprovalEffects(
                null, java.util.Map.of("demo", config()), null, runState, executor);

        effects.decideBatch(batchId, true, "看过了", "tester");

        assertEquals("x", nameOf(1), "SQL 批次没有等待方，就是在批准这一刻才跑的");
        assertEquals(ApprovalBatchRow.STATUS_APPLIED, runState.findBatch(batchId).status());
        assertNotNull(runState.listBatchItems(batchId).get(0).executionId(),
                "条目要指回执行记录——结果不进批次表");
    }

    @Test
    void theRollbackScriptRunsBackwards() throws Exception {
        long batchId = runState.createBatch("demo", ApprovalBatchRow.KIND_SQL, "两条 UPDATE");
        List<ApprovalRow> items = stage(batchId,
                "UPDATE t SET name = 'x' WHERE id = 1",
                "UPDATE t SET name = 'y' WHERE id = 1");

        SqlBatchExecutor.BatchResult result = executor.execute(config(), batchId, items);

        // 回滚脚本挂在第一条的执行记录上（整批一份，执行记录页从那里进得去）
        String script = runState.findRecovery(result.items().get(0).executionId()).rollbackSql();
        // 倒序：先撤第二条（把 x 写回来），再撤第一条（把 a 写回来）
        assertTrue(script.indexOf("'x'") < script.indexOf("'a'"),
                "回滚段要逆序，顺着来会把中间态当成起点：\n" + script);
    }
}
