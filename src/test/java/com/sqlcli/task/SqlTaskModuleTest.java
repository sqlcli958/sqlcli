package com.sqlcli.task;

import com.sqlcli.approval.ApprovalGate;
import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.connection.ConnectionManager;
import com.sqlcli.connection.QueryExecutionOptions;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sqlcli.recovery.RecoveryResult;
import com.sqlcli.runstate.RunStateStore;
import com.sqlcli.strategy.DatabaseCapabilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqlTaskModuleTest {

    @TempDir
    Path temp;

    private SqlTaskModule module(ConnectionManager connectionManager) {
        RunStateStore runState = new RunStateStore(temp.resolve("sqlcli.db"), temp.resolve("history"));
        return new SqlTaskModule(connectionManager, runState, new ApprovalGate(runState, 200));
    }

    private SqlTaskRequest request(DatabaseConfig config, String sql) {
        return SqlTaskRequest.of(config, sql, new QueryExecutionOptions("json", Set.of(), null),
                SqlTaskRequest.Origin.cli);
    }

    @Test
    void updateWithoutWhereIsRejectedWithoutEchoingValues() {
        SqlTaskResult result = module(mock(ConnectionManager.class))
                .execute(request(mysqlConfig(), "UPDATE customers SET phone = 'sensitive-value'"));

        assertEquals(SqlTaskResult.Status.REJECTED, result.status());
        assertTrue(result.errorSummary().contains("UPDATE"));
        assertTrue(result.errorSummary().contains("WHERE"));
        assertFalse(result.errorSummary().contains("sensitive-value"));
    }

    @Test
    void deleteWithoutWhereIsRejected() {
        SqlTaskResult result = module(mock(ConnectionManager.class))
                .execute(request(mysqlConfig(), "DELETE FROM customers"));

        assertEquals(SqlTaskResult.Status.REJECTED, result.status());
        assertTrue(result.errorSummary().contains("DELETE"));
        assertTrue(result.errorSummary().contains("WHERE"));
    }

    @Test
    void multipleStatementsAreRejectedBeforeConnecting() throws Exception {
        ConnectionManager manager = mock(ConnectionManager.class);
        SqlTaskResult result = module(manager)
                .execute(request(mysqlConfig(), "SELECT 1; SELECT 2"));

        assertEquals(SqlTaskResult.Status.REJECTED, result.status());
        assertTrue(result.errorSummary().contains("Multiple statements"));
        verify(manager, never()).getConnection(any());
    }

    @Test
    void readonlyAliasRejectsWriteBeforeConnecting() throws Exception {
        ConnectionManager manager = mock(ConnectionManager.class);
        DatabaseConfig config = mysqlConfig();
        config.setReadonly(true);

        SqlTaskResult result = module(manager)
                .execute(request(config, "UPDATE customers SET status = 'x' WHERE id = 1"));

        assertEquals(SqlTaskResult.Status.REJECTED, result.status());
        assertTrue(result.errorSummary().contains("readonly"));
        verify(manager, never()).getConnection(any());
    }

    @Test
    void updateWithoutPrimaryKeyIsRejectedBeforeReadingOrWritingRows() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet primaryKeys = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getCatalog()).thenReturn("app");
        when(metadata.getPrimaryKeys("app", null, "customers")).thenReturn(primaryKeys);
        when(metadata.getPrimaryKeys("app", null, "CUSTOMERS")).thenReturn(primaryKeys);
        when(primaryKeys.next()).thenReturn(false);

        ConnectionManager manager = mock(ConnectionManager.class);
        when(manager.getConnection(any())).thenReturn(connection);

        SqlTaskResult result = module(manager)
                .execute(request(mysqlConfig(), "UPDATE customers SET status = 'active' WHERE id = 7"));

        assertEquals(SqlTaskResult.Status.REJECTED, result.status());
        assertTrue(result.errorSummary().contains("primary key"));
        verify(statement, never()).executeQuery(anyString());
        verify(statement, never()).executeUpdate(anyString());
    }

    @Test
    void recoveryFileMustBeSavedBeforeTheOriginalWrite() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet primaryKeys = mock(ResultSet.class);
        ResultSet rows = mock(ResultSet.class);
        java.sql.ResultSetMetaData rowMetadata = mock(java.sql.ResultSetMetaData.class);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getCatalog()).thenReturn("app");
        when(metadata.getPrimaryKeys("app", null, "customers")).thenReturn(primaryKeys);
        when(primaryKeys.next()).thenReturn(true, false);
        when(primaryKeys.getString("COLUMN_NAME")).thenReturn("id");
        when(primaryKeys.getShort("KEY_SEQ")).thenReturn((short) 1);
        when(statement.executeQuery(anyString())).thenReturn(rows);
        when(rows.getMetaData()).thenReturn(rowMetadata);
        when(rowMetadata.getColumnCount()).thenReturn(2);
        when(rowMetadata.getColumnLabel(1)).thenReturn("id");
        when(rowMetadata.getColumnLabel(2)).thenReturn("status");
        when(rows.next()).thenReturn(true, false);
        when(rows.getObject(1)).thenReturn(7);
        when(rows.getObject(2)).thenReturn("pending");

        ConnectionManager manager = mock(ConnectionManager.class);
        when(manager.getConnection(any())).thenReturn(connection);

        // 运行库存不下回滚脚本（mock 的 saveRecoveryScript 返回 0）= 这次写没有后悔药，
        // 必须在执行之前就中止。
        RunStateStore brokenStore = mock(RunStateStore.class);
        SqlTaskResult result = new SqlTaskModule(manager, brokenStore, new ApprovalGate(brokenStore, 200))
                .execute(request(mysqlConfig(), "UPDATE customers SET status = 'active' WHERE id = 7"));

        assertEquals(SqlTaskResult.Status.FAILED, result.status());
        verify(statement, never()).executeUpdate(anyString());
        // 写没执行，回滚在 catch 里统一发生，事务不应该留在手动提交模式。
        verify(connection).setAutoCommit(false);
        verify(connection).rollback();
        verify(connection).setAutoCommit(true);
    }

    @Test
    void dryRunCollectsPrecheckWithoutExecutingOrBlockingOnApproval() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet primaryKeys = mock(ResultSet.class);
        java.sql.PreparedStatement countStatement = mock(java.sql.PreparedStatement.class);
        ResultSet countResult = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getCatalog()).thenReturn("app");
        when(metadata.getPrimaryKeys("app", null, "customers")).thenReturn(primaryKeys);
        when(primaryKeys.next()).thenReturn(true, false);
        when(primaryKeys.getString("COLUMN_NAME")).thenReturn("id");
        when(primaryKeys.getShort("KEY_SEQ")).thenReturn((short) 1);
        when(connection.prepareStatement(org.mockito.ArgumentMatchers.contains("COUNT(*)")))
                .thenReturn(countStatement);
        when(countStatement.executeQuery()).thenReturn(countResult);
        when(countResult.next()).thenReturn(true);
        when(countResult.getLong(1)).thenReturn(42L);

        ConnectionManager manager = mock(ConnectionManager.class);
        when(manager.getConnection(any())).thenReturn(connection);

        SqlTaskRequest request = new SqlTaskRequest(mysqlConfig(),
                "UPDATE customers SET status = 'active' WHERE id = 7",
                new QueryExecutionOptions("json", Set.of(), null),
                SqlTaskRequest.Origin.ui_workbench, null, null, null, true);
        SqlTaskResult result = module(manager).execute(request);

        assertEquals(SqlTaskResult.Status.SUCCEEDED, result.status());
        assertTrue(result.precheck().recoverySupported());
        assertEquals(42L, result.precheck().estimatedRows());
        // dry-run 不执行、不阻塞审批：写 Statement 从没被建过。
        verify(connection, never()).createStatement();
        verify(statement, never()).executeUpdate(anyString());
    }

    @Test
    void yearningBackendRejectsWritesWithoutTouchingTheBackend() throws Exception {
        DatabaseConfig config = mysqlConfig();
        config.setAccessMode("yearning");

        ConnectionManager manager = mock(ConnectionManager.class);
        SqlTaskResult result = module(manager)
                .execute(request(config, "UPDATE customers SET status = 'x' WHERE id = 1"));

        // GuardStage 问的是 backend.supportsWrites()，不是硬编码"是不是 Yearning"——
        // 加第三个只读后端时这条断言不用改。
        assertEquals(SqlTaskResult.Status.REJECTED, result.status());
        assertTrue(result.errorSummary().contains("read-only"));
        verify(manager, never()).getConnection(any());
    }

    /**
     * 写成功之后时间线要有「执行完成」这一格，带上真实影响行数和恢复文件；
     * 没有它，审批人分不出「批准之后真跑了」和「批准之后挂了」。
     */
    @Test
    void successfulWriteAppendsExecutedEventWithAffectedRows() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet primaryKeys = mock(ResultSet.class);
        ResultSet rows = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getCatalog()).thenReturn("app");
        when(metadata.getPrimaryKeys("app", null, "customers")).thenReturn(primaryKeys);
        when(primaryKeys.next()).thenReturn(true, false);
        when(primaryKeys.getString("COLUMN_NAME")).thenReturn("id");
        when(primaryKeys.getShort("KEY_SEQ")).thenReturn((short) 1);
        java.sql.ResultSetMetaData rowMetadata = mock(java.sql.ResultSetMetaData.class);
        when(statement.executeQuery(anyString())).thenReturn(rows);
        when(rows.getMetaData()).thenReturn(rowMetadata);
        when(rowMetadata.getColumnCount()).thenReturn(2);
        when(rowMetadata.getColumnLabel(1)).thenReturn("id");
        when(rowMetadata.getColumnLabel(2)).thenReturn("status");
        when(rows.next()).thenReturn(true, false);
        when(rows.getObject(1)).thenReturn(7);
        when(rows.getObject(2)).thenReturn("pending");
        when(statement.executeUpdate(anyString())).thenReturn(3);

        ConnectionManager manager = mock(ConnectionManager.class);
        when(manager.getConnection(any())).thenReturn(connection);
        RunStateStore runState = new RunStateStore(temp.resolve("sqlcli.db"), temp.resolve("history"));

        SqlTaskResult result = new SqlTaskModule(manager, runState, new ApprovalGate(runState, 200))
                .execute(request(mysqlConfig(), "UPDATE customers SET status = 'active' WHERE id = 7"));

        assertEquals(SqlTaskResult.Status.SUCCEEDED, result.status());
        var executed = runState.listTaskEvents(Long.parseLong(result.taskId())).stream()
                .filter(e -> "executed".equals(e.eventType())).findFirst().orElseThrow();
        assertTrue(executed.payload().contains("\"affectedRows\":3"), executed.payload());
        assertTrue(executed.payload().contains("\"recoveryId\":" + result.recoveryId()), executed.payload());

        // 回滚脚本进了运行库，而且是执行前就存好的：从执行记录点得回去。
        var artifact = runState.findRecovery(runState.listExecutions("safety-test", null, null, null, 10, 0)
                .get(0).id());
        assertTrue(artifact.hasScript());
        assertTrue(artifact.rollbackSql().contains("UPDATE customers SET status="), artifact.rollbackSql());
        assertTrue(artifact.backupText().contains("id=7"), artifact.backupText());
    }

    /**
     * 审批记录必须挂上 task_run：评审页的预检块和时间线是顺着这个 id 找过去的，
     * 断了链就只剩一段 SQL 摘要。这里让审批超时（gate 超时 200ms），
     * 只关心落库时有没有把 id 带上。
     */
    @Test
    void approvalRequestCarriesTaskRunIdSoTheReviewPageCanFindThePrecheck() throws Exception {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet primaryKeys = mock(ResultSet.class);
        java.sql.PreparedStatement countStatement = mock(java.sql.PreparedStatement.class);
        ResultSet countResult = mock(ResultSet.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getCatalog()).thenReturn("app");
        when(metadata.getPrimaryKeys("app", null, "customers")).thenReturn(primaryKeys);
        when(primaryKeys.next()).thenReturn(true, false);
        when(primaryKeys.getString("COLUMN_NAME")).thenReturn("id");
        when(primaryKeys.getShort("KEY_SEQ")).thenReturn((short) 1);
        when(connection.prepareStatement(org.mockito.ArgumentMatchers.contains("COUNT(*)")))
                .thenReturn(countStatement);
        when(countStatement.executeQuery()).thenReturn(countResult);
        when(countResult.next()).thenReturn(true);
        when(countResult.getLong(1)).thenReturn(42L);

        ConnectionManager manager = mock(ConnectionManager.class);
        when(manager.getConnection(any())).thenReturn(connection);
        DatabaseConfig config = mysqlConfig();
        config.setApproveUpdate(true);

        RunStateStore runState = new RunStateStore(temp.resolve("sqlcli.db"), temp.resolve("history"));
        SqlTaskResult result = new SqlTaskModule(manager, runState, new ApprovalGate(runState, 200))
                .execute(request(config, "UPDATE customers SET status = 'active' WHERE id = 7"));

        assertEquals(SqlTaskResult.Status.REJECTED, result.status());
        com.sqlcli.runstate.ApprovalRow approval = runState.listApprovals(null, 10).get(0);
        org.junit.jupiter.api.Assertions.assertNotNull(approval.taskRunId(), "审批必须挂上写任务");
        assertEquals(result.taskId(), String.valueOf(approval.taskRunId()));

        // 时间线上的 precheck 事件带着预估行数，评审页就是从这里读的
        var events = runState.listTaskEvents(approval.taskRunId());
        assertEquals("submitted", events.get(0).eventType());
        assertTrue(events.stream().anyMatch(
                e -> "precheck".equals(e.eventType()) && e.payload().contains("42")));
    }

    @Test
    void reliableAffectedRowsReturnsNullWhenCapabilityUntrusted() {
        assertNull(SqlTaskUtil.reliableAffectedRows(DatabaseCapabilities.CLICKHOUSE_DEFAULTS(), 0));
        assertEquals(3, SqlTaskUtil.reliableAffectedRows(DatabaseCapabilities.MySQL_DEFAULTS(), 3));
    }

    private DatabaseConfig mysqlConfig() {
        DatabaseConfig config = new DatabaseConfig();
        config.setAliasName("safety-test");
        config.setType("mysql");
        config.setDatabase("app");
        return config;
    }

    // ---- DDL 门：绑定的结构规则在 CREATE / ALTER / DROP 执行前生效 ----

    /** 别名工作区 + 一条 required_business_columns 规则（enforcement 由参数给），并绑定。 */
    private GraphWorkspaceStore workspaceWithRequiredColumns(String enforcement) throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp.resolve("graphs"));
        store.save(GraphWorkspace.create("safety-test", "mysql"));
        Path policy = temp.resolve("graphs/safety-test/policy");
        Files.createDirectories(policy.resolve("rules"));
        Files.writeString(policy.resolve("rules/structure.yaml"), """
                kind: PolicyRuleSet
                id: structure-policy
                title: 结构规范
                version: "1"
                rules:
                  - id: required_business_columns
                    title: 必备字段
                    category: required_business_columns
                    severity: error
                    enforcement: %s
                    remediation: 补齐 created_at / updated_at
                    statement:
                      columns:
                        - name: created_at
                          type: datetime
                          nullable: false
                """.formatted(enforcement));
        Files.writeString(policy.resolve("bindings.yaml"), "ruleSets:\n  - rules/structure.yaml\n");
        return store;
    }

    private SqlTaskModule module(ConnectionManager connectionManager, GraphWorkspaceStore store) {
        RunStateStore runState = new RunStateStore(temp.resolve("sqlcli.db"), temp.resolve("history"));
        return new SqlTaskModule(connectionManager, runState, new ApprovalGate(runState, 200), store);
    }

    private SqlTaskRequest dryRun(String sql) {
        return new SqlTaskRequest(mysqlConfig(), sql, new QueryExecutionOptions("json", Set.of(), null),
                SqlTaskRequest.Origin.cli, null, null, null, true);
    }

    @Test
    void createTableMissingRequiredColumnsIsRejectedBeforeConnecting() throws Exception {
        ConnectionManager manager = mock(ConnectionManager.class);
        SqlTaskResult result = module(manager, workspaceWithRequiredColumns("required"))
                .execute(request(mysqlConfig(), "CREATE TABLE orders (id BIGINT PRIMARY KEY, amount DECIMAL(10,2))"));

        assertEquals(SqlTaskResult.Status.REJECTED, result.status());
        assertTrue(result.errorSummary().contains("created_at"), result.errorSummary());
        assertTrue(result.errorSummary().contains("补齐 created_at"), "修法必须跟着违规一起回去");
        verify(manager, never()).getConnection(any());
    }

    /** 名字必须完全一致：create_time 不算 created_at，规则要的是新表统一叫这个名字。 */
    @Test
    void differentlyNamedColumnDoesNotSatisfyTheRule() throws Exception {
        SqlTaskResult result = module(mock(ConnectionManager.class), workspaceWithRequiredColumns("required"))
                .execute(dryRun("CREATE TABLE orders (id BIGINT PRIMARY KEY, create_time DATETIME NOT NULL)"));
        assertEquals(SqlTaskResult.Status.REJECTED, result.status());
    }

    @Test
    void createTableWithRequiredColumnsPassesTheGate() throws Exception {
        SqlTaskResult result = module(mock(ConnectionManager.class), workspaceWithRequiredColumns("required"))
                .execute(dryRun("CREATE TABLE orders (id BIGINT PRIMARY KEY, created_at DATETIME NOT NULL)"));

        assertEquals(SqlTaskResult.Status.SUCCEEDED, result.status(), result.errorSummary());
        assertTrue(result.notices().isEmpty(), () -> String.join("; ", result.notices()));
    }

    @Test
    void advisoryViolationBecomesANoticeAndTheDdlProceeds() throws Exception {
        SqlTaskResult result = module(mock(ConnectionManager.class), workspaceWithRequiredColumns("advisory"))
                .execute(dryRun("CREATE TABLE orders (id BIGINT PRIMARY KEY)"));

        assertEquals(SqlTaskResult.Status.SUCCEEDED, result.status(), result.errorSummary());
        assertEquals(1, result.notices().size(), () -> String.join("; ", result.notices()));
        assertTrue(result.notices().get(0).contains("created_at"), result.notices().get(0));
    }

    @Test
    void aliasWithoutBoundRulesIsNotTouchedByTheGate() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp.resolve("graphs"));
        store.save(GraphWorkspace.create("safety-test", "mysql"));
        SqlTaskResult result = module(mock(ConnectionManager.class), store)
                .execute(dryRun("CREATE TABLE orders (id BIGINT PRIMARY KEY)"));
        assertEquals(SqlTaskResult.Status.SUCCEEDED, result.status(), result.errorSummary());
        assertTrue(result.notices().isEmpty());
    }
}
