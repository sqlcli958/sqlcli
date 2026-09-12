package com.sqlcli.runstate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunStateStoreTest {

    private RunStateStore store(Path dir) {
        return new RunStateStore(dir.resolve("sqlcli.db"), dir.resolve("execution-history"));
    }

    @Test
    void createsSchemaAndSurvivesReopen(@TempDir Path dir) {
        store(dir).recordExecution("demo", "SELECT", "SELECT 1", "success", null, null, 5, 1000);

        List<SqlExecutionRow> rows = store(dir).listExecutions(null, null, null, null, 10, 0);
        assertEquals(1, rows.size());
        assertEquals("demo", rows.get(0).alias());
        assertTrue(Files.exists(dir.resolve("sqlcli.db")));
    }

    /**
     * 列表里显示脱敏文本，详情里能看到原文——写语句同样如此。写语句的原始行和回滚脚本
     * 就存在隔壁 recovery_artifact 里，单独把它的 SQL 打码只会让人看不出改了哪一行。
     */
    @Test
    void keepsBothMaskedAndRawSqlForEveryStatement(@TempDir Path dir) {
        RunStateStore store = store(dir);
        store.recordExecution("demo", "SELECT", "SELECT * FROM t WHERE phone = '13800138000'",
                "success", null, null, 1, 1000);
        store.recordExecution("demo", "UPDATE", "UPDATE t SET phone = '13800138000'",
                "success", null, 1L, 1, 2000);

        List<SqlExecutionRow> rows = store.listExecutions("demo", null, null, null, 10, 0);
        assertEquals("UPDATE", rows.get(0).sqlType());
        assertEquals("UPDATE t SET phone = '13800138000'", rows.get(0).rawSql());
        assertEquals("UPDATE t SET phone = '<redacted>'", rows.get(0).sql());

        assertEquals("SELECT * FROM t WHERE phone = '13800138000'", rows.get(1).rawSql());
        assertEquals("SELECT * FROM t WHERE phone = '<redacted>'", rows.get(1).sql());
    }

    /** schema 筛选：SQL 里写了 schema.table 就用它，没写就退回别名的默认 schema。 */
    @Test
    void tagsExecutionsWithTargetSchemaForFiltering(@TempDir Path dir) {
        RunStateStore store = store(dir);
        store.recordExecution("demo", "SELECT", "SELECT * FROM qm_pct.orders", "success", null, null,
                1, 1000, "normal", null, "fallback_db");
        store.recordExecution("demo", "UPDATE", "UPDATE sys_menu SET visible=1 WHERE id='9'", "success",
                null, 1L, 1, 2000, "normal", null, "Fallback_DB");

        assertEquals(1, store.listExecutions("demo", null, null, null, null, "qm_pct", 10, 0).size());
        assertEquals(1, store.listExecutions("demo", null, null, null, null, "FALLBACK_DB", 10, 0).size());
        assertEquals(List.of("fallback_db", "qm_pct"), store.listExecutionSchemas("demo"));
    }

    /** 时间范围是左闭右开：右端点那一条不算进来。 */
    @Test
    void filtersByTimeRange(@TempDir Path dir) {
        RunStateStore store = store(dir);
        store.recordExecution("demo", "SELECT", "SELECT 1", "success", null, null, 1, 1000);
        store.recordExecution("demo", "SELECT", "SELECT 2", "success", null, null, 1, 2000);
        store.recordExecution("demo", "SELECT", "SELECT 3", "success", null, null, 1, 3000);

        assertEquals(1, store.listExecutions("demo", null, null, 2000L, 3000L, null, 10, 0).size());
    }

    /** 回滚脚本存库：先存（还没有执行记录），执行完再挂上去。 */
    @Test
    void storesRecoveryScriptBeforeItHasAnExecutionToAttachTo(@TempDir Path dir) {
        RunStateStore store = store(dir);
        long artifactId = store.saveRecoveryScript("UPDATE t SET x=0 WHERE id=1;", "id=1, x=0");
        assertTrue(artifactId > 0);

        long executionId = store.recordExecution("demo", "UPDATE", "UPDATE t SET x=1 WHERE id=1",
                "success", null, 1L, 1, 1000);
        store.attachRecoveryArtifact(artifactId, executionId);

        RecoveryArtifactRow artifact = store.findRecovery(executionId);
        assertEquals("UPDATE t SET x=0 WHERE id=1;", artifact.rollbackSql());
        assertEquals("id=1, x=0", artifact.backupText());
        assertTrue(artifact.hasScript());
    }

    @Test
    void listsRecentSqlForTableDedupedWithWordBoundary(@TempDir Path dir) {
        RunStateStore store = store(dir);
        // 同形 SQL（mask 后一致）跑三次只算一条范例；orders_archive 不该被 orders 命中
        store.recordExecution("demo", "SELECT", "SELECT * FROM orders WHERE no = 'A1'", "success", null, null, 1, 1000);
        store.recordExecution("demo", "SELECT", "SELECT * FROM orders WHERE no = 'A2'", "success", null, null, 1, 2000);
        store.recordExecution("demo", "SELECT", "SELECT * FROM orders WHERE no = 'A3'", "success", null, null, 1, 3000);
        store.recordExecution("demo", "SELECT", "SELECT count(*) FROM orders", "success", null, null, 1, 4000);
        store.recordExecution("demo", "SELECT", "SELECT * FROM orders_archive", "success", null, null, 1, 5000);
        store.recordExecution("demo", "SELECT", "SELECT max(id) FROM orders", "failed", "boom", null, 1, 6000);
        store.recordExecution("other", "SELECT", "SELECT min(id) FROM orders", "success", null, null, 1, 7000);

        List<String> recent = store.listRecentSqlForTable("demo", "orders", 5);
        assertEquals(2, recent.size(), "去重后只剩两种写法，失败的和别的别名的不算");
        assertEquals("SELECT count(*) FROM orders", recent.get(0));
        assertEquals("SELECT * FROM orders WHERE no = '<redacted>'", recent.get(1));
    }

    @Test
    void filtersAndPagesNewestFirst(@TempDir Path dir) {
        RunStateStore store = store(dir);
        for (int i = 0; i < 5; i++) {
            store.recordExecution("a", "SELECT", "SELECT " + i, "success", null, null, 1, 1000 + i);
        }
        store.recordExecution("a", "UPDATE", "UPDATE t SET x=1", "failed", "boom", null, 1, 9000);
        store.recordExecution("b", "SELECT", "SELECT 9", "success", null, null, 1, 9999);

        assertEquals(5, store.listExecutions("a", "SELECT", null, null, 50, 0).size());
        assertEquals(1, store.listExecutions("a", null, "failed", null, 50, 0).size());
        assertEquals(2, store.listExecutions(null, null, null, 9000L, 50, 0).size());

        List<SqlExecutionRow> page1 = store.listExecutions("a", "select", null, null, 2, 0);
        List<SqlExecutionRow> page2 = store.listExecutions("a", "SELECT", null, null, 2, 2);
        assertEquals("SELECT 4", page1.get(0).sql());
        assertEquals("SELECT 3", page1.get(1).sql());
        assertEquals("SELECT 2", page2.get(0).sql());
    }

    @Test
    void importsLegacyJsonlExactlyOnce(@TempDir Path dir) throws Exception {
        Path history = dir.resolve("execution-history");
        Files.createDirectories(history);
        Files.writeString(history.resolve("demo.jsonl"), """
                {"alias":"demo","sql":"SELECT 1","startedAt":100,"elapsedMs":7}
                {"alias":"demo","sql":"UPDATE t SET x='<redacted>'","startedAt":200,"elapsedMs":9}
                not-json
                """, StandardCharsets.UTF_8);

        store(dir).listExecutions(null, null, null, null, 50, 0);
        List<SqlExecutionRow> rows = store(dir).listExecutions(null, null, null, null, 50, 0);

        assertEquals(2, rows.size(), "re-opening must not re-import");
        assertEquals("migrated", rows.get(0).source());
        assertEquals("UPDATE", rows.get(0).sqlType());
        assertNull(rows.get(0).rawSql(), "migrated rows have no original text");
        assertTrue(Files.exists(history.resolve("demo.jsonl")), "old files are kept");
    }

    @Test
    void concurrentWritersLoseNothing(@TempDir Path dir) throws Exception {
        int perThread = 40;
        java.util.concurrent.atomic.AtomicInteger lost = new java.util.concurrent.atomic.AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        Runnable writer = () -> {
            RunStateStore store = store(dir);
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            for (int i = 0; i < perThread; i++) {
                // 返回 0 = 这一行没写进去。审计写入对外是「失败也不抛」，所以只断言总数的话
                // 挂了只能看到 79 != 80，看不出是谁丢的；这里当场记下来。
                if (store.recordExecution("demo", "SELECT", "SELECT " + i, "success", null, null,
                        1, 1000 + i) <= 0) {
                    lost.incrementAndGet();
                }
            }
        };
        Thread t1 = new Thread(writer);
        Thread t2 = new Thread(writer);
        t1.start();
        t2.start();
        start.countDown();
        t1.join();
        t2.join();

        assertEquals(0, lost.get(), "有写入返回 0，说明并发下真的丢了行");
        assertEquals(perThread * 2, store(dir).listExecutions("demo", null, null, null, 500, 0).size());
    }

    /**
     * 老库（v3，approval_request 还没有 task_run_id）升到 v4：现有行必须一条不少，
     * 新列要能立刻用起来。ALTER 走错就是审批历史整段消失，只能真造一个老库来验。
     */
    @Test
    void migratesVersion3DatabaseWithoutLosingRows(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("sqlcli.db");
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
             java.sql.Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE approval_request (
                      id         INTEGER PRIMARY KEY AUTOINCREMENT,
                      alias      TEXT    NOT NULL,
                      kind       TEXT    NOT NULL,
                      summary    TEXT    NOT NULL,
                      detail     TEXT,
                      status     TEXT    NOT NULL,
                      reason     TEXT,
                      created_at INTEGER NOT NULL,
                      decided_at INTEGER
                    )""");
            st.execute("INSERT INTO approval_request (alias, kind, summary, status, created_at)"
                    + " VALUES ('demo', 'update', 'UPDATE t SET x=1', 'approved', 42)");
            st.execute("PRAGMA user_version=3");
        }

        RunStateStore store = store(dir);
        List<ApprovalRow> rows = store.listApprovals(null, 50);
        assertEquals(1, rows.size(), "老库里的审批记录不能在迁移中丢掉");
        assertEquals("demo", rows.get(0).alias());
        assertNull(rows.get(0).taskRunId(), "老行没有关联任务");

        long runId = store.createTaskRun("demo", "sql_write", "cli", "hash");
        long approvalId = store.createApproval("demo", "update", "DELETE FROM t", "DELETE FROM t", runId);
        assertEquals(runId, store.findApproval(approvalId).taskRunId());
    }

    /**
     * 分页要的是「符合条件的第 N 页」，不是「当前页里符合条件的那几条」——
     * 所以类型/状态筛选必须在 SQL 里做，而且 count 和 list 要用同一份 WHERE。
     */
    @Test
    void filtersAndPagesApprovalsServerSide(@TempDir Path dir) {
        RunStateStore store = store(dir);
        store.createApproval("demo", "update", "#1", null);
        store.createApproval("demo", "graph", "#2", null);
        long third = store.createApproval("demo", "update", "#3", null);
        store.decideApproval(third, "approved", null);
        store.createApproval("other", "update", "#4", null);

        assertEquals(4, store.countApprovals(null, null, null, null));
        assertEquals(3, store.countApprovals(null, null, "update", null));
        assertEquals(2, store.countApprovals(null, "pending", "update", null));
        assertEquals(0, store.countApprovals(null, null, null, System.currentTimeMillis() + 1000));

        // 别名过滤：审批记录跟着顶栏选中的数据源走，别的库的记录不该混进来
        assertEquals(3, store.countApprovals("demo", null, null, null));
        assertEquals(1, store.countApprovals("other", null, null, null));
        assertEquals(1, store.listApprovals("other", null, null, null, 50, 0).size());
        assertEquals("#4", store.listApprovals("other", null, null, null, 50, 0).get(0).summary());

        // 最新在前：#3(update) → #2(graph) → #1(update)；demo 的 update 第二页是 #1
        assertEquals("#3", store.listApprovals("demo", null, "update", null, 1, 0).get(0).summary());
        assertEquals("#1", store.listApprovals("demo", null, "update", null, 1, 1).get(0).summary());
        assertEquals(2, store.listApprovals(null, null, null, null, 2, 0).size());
    }

    /** 总条数和翻出来的内容必须来自同一组条件，否则总页数是假的。 */
    @Test
    void countExecutionsUsesTheSameFilterAsTheList(@TempDir Path dir) {
        RunStateStore store = store(dir);
        for (int i = 0; i < 7; i++) {
            store.recordExecution("demo", "SELECT", "SELECT * FROM qm_pct.t" + i, "success", null, null,
                    1, 1000 + i, "normal", null, null);
        }
        store.recordExecution("demo", "UPDATE", "UPDATE qm_pct.t SET x=1 WHERE id=1", "failed", "boom",
                null, 1, 9000, "normal", null, null);
        store.recordExecution("other", "SELECT", "SELECT 1", "success", null, null, 1, 9999);

        assertEquals(8, store.countExecutions("demo", null, null, null, null, null));
        assertEquals(7, store.countExecutions("demo", "SELECT", null, null, null, null));
        assertEquals(1, store.countExecutions("demo", null, "failed", null, null, null));
        assertEquals(8, store.countExecutions("demo", null, null, null, null, "qm_pct"));
        assertEquals(2, store.listExecutions("demo", "SELECT", null, null, null, null, 2, 5).size());
    }

    @Test
    void linksApprovalToTaskRunAndReadsEventsInOrder(@TempDir Path dir) {
        RunStateStore store = store(dir);
        long runId = store.createTaskRun("demo", "sql_write", "cli", "hash");
        store.recordTaskEvent(runId, "submitted", null);
        store.recordTaskEvent(runId, "precheck", "{\"table\":\"t\",\"estimatedRows\":3}");
        store.recordTaskEvent(runId, "executed", "{\"affectedRows\":3}");
        store.recordTaskEvent(0, "ignored", null);
        store.finishTaskRun(runId, "success");

        TaskRunRow run = store.findTaskRun(runId);
        assertEquals("success", run.status());
        assertEquals("demo", run.alias());
        assertTrue(run.updatedAt() >= run.createdAt());

        List<TaskEventRow> events = store.listTaskEvents(runId);
        assertEquals(List.of("submitted", "precheck", "executed"),
                events.stream().map(TaskEventRow::eventType).toList());
        assertEquals("{\"affectedRows\":3}", events.get(2).payload());
        assertNull(events.get(0).payload());

        assertNull(store.findTaskRun(runId + 999));
        assertTrue(store.listTaskEvents(0).isEmpty());
    }

    /**
     * 批准之后执行被拒的那条 SQL：审批状态停在 approved，真相只在 task_run 里。
     * 列表不带上它，评审页就只有一个绿色的「已批准」。
     */
    @Test
    void approvalListCarriesTheOutcomeOfTheRunItApproved(@TempDir Path dir) {
        RunStateStore store = store(dir);
        long runId = store.createTaskRun("demo", "sql_write", "cli", "hash");
        long approvalId = store.createApproval("demo", "update", "UPDATE t SET a=1", null, runId);
        store.decideApproval(approvalId, "approved", null);
        store.finishTaskRun(runId, "rejected");

        ApprovalRow row = store.listApprovals(null, 50).get(0);
        assertEquals("approved", row.status());
        assertEquals("rejected", row.taskStatus());

        // 没有关联写任务的审批（读操作、图谱变更）不该因为这一列报错
        store.createApproval("demo", "graph", "no task run", null);
        assertNull(store.listApprovals(null, 50).get(0).taskStatus());
    }

    @Test
    void recordsGraphChanges(@TempDir Path dir) throws Exception {
        store(dir).recordGraphChange("demo", "upsert_table", "t:1", "human", 4, 5);

        try (java.sql.Connection conn =
                     java.sql.DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("sqlcli.db"));
             java.sql.Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery(
                     "SELECT alias, operation, target_id, actor, revision_before, revision_after"
                             + " FROM graph_change_log")) {
            assertTrue(rs.next());
            assertEquals("demo", rs.getString(1));
            assertEquals("upsert_table", rs.getString(2));
            assertEquals("t:1", rs.getString(3));
            assertEquals("human", rs.getString(4));
            assertEquals(4, rs.getLong(5));
            assertEquals(5, rs.getLong(6));
        }
    }

    // --------------------------------------------------------------- graph_read

    /**
     * query 存原文，不做归一化——这是读侧遥测要解决的问题本身（业务词搜不到对的表），
     * 把词磨平就把线索磨掉了。
     */
    @Test
    void recordsGraphReadWithVerbatimQueryAndTargetIds(@TempDir Path dir) {
        RunStateStore store = store(dir);
        store.recordInteraction("demo", InteractionDetail.schemaAction("search"), "ok", 0, null, null,
                InteractionDetail.of("订单 Order-金额", 2, 0.87, List.of("table:demo:app.orders", "column:demo:app.orders.amount")));

        assertEquals(1, store.countTargetHits("demo", InteractionDetail.schemaAction("search"), "table:demo:app.orders"));
        assertEquals(1, store.countTargetHits("demo", null, "column:demo:app.orders.amount"));
        assertEquals(0, store.countTargetHits("demo", InteractionDetail.schemaAction("describe"), "table:demo:app.orders"));
        assertEquals(0, store.countTargetHits("demo", InteractionDetail.schemaAction("search"), "table:demo:app.order"));
    }

    @Test
    void writeFailureNeverThrowsBecauseTelemetryIsASideEffect(@TempDir Path dir) {
        RunStateStore store = store(dir);
        // 没有 alias 也不该抛异常——遥测挂了不该影响 agent 拿上下文
        store.recordInteraction(null, InteractionDetail.schemaAction("search"), "ok", 0, null, null,
                InteractionDetail.of("x", null, null, null));
        assertEquals(0, store.countTargetHits("demo", InteractionDetail.schemaAction("search"), "anything"));
    }

    @Test
    void aggregatesTargetHitCountsAcrossReads(@TempDir Path dir) {
        RunStateStore store = store(dir);
        store.recordInteraction("demo", InteractionDetail.schemaAction("describe"), "ok", 0, null, null,
                InteractionDetail.of("orders", 1, null, List.of("table:demo:app.orders")));
        store.recordInteraction("demo", InteractionDetail.schemaAction("describe"), "ok", 0, null, null,
                InteractionDetail.of("orders", 1, null, List.of("table:demo:app.orders")));
        store.recordInteraction("demo", "path", "ok", 0, null, null,
                InteractionDetail.of("orders -> users", 1, null, List.of("table:demo:app.orders", "table:demo:app.users")));

        Map<String, Integer> counts = store.targetHitCounts("demo", null);
        assertEquals(3, counts.get("table:demo:app.orders"));
        assertEquals(1, counts.get("table:demo:app.users"));

        // action 筛选：只看 path 的话 orders 只出现过一次
        assertEquals(1, store.targetHitCounts("demo", "path").get("table:demo:app.orders"));
    }

    /** 搜空的查询词是最准的补图谱优先级信号——hit_count = 0 的 search，按出现次数降序。 */
    @Test
    void listsMissedSearchQueriesOrderedByFrequency(@TempDir Path dir) {
        RunStateStore store = store(dir);
        store.recordInteraction("demo", InteractionDetail.schemaAction("search"), "ok", 0, null, null,
                InteractionDetail.of("退款单", 0, null, List.of()));
        store.recordInteraction("demo", InteractionDetail.schemaAction("search"), "ok", 0, null, null,
                InteractionDetail.of("退款单", 0, null, List.of()));
        store.recordInteraction("demo", InteractionDetail.schemaAction("search"), "ok", 0, null, null,
                InteractionDetail.of("发货单", 0, null, List.of()));
        // 命中过的词不算搜空
        store.recordInteraction("demo", InteractionDetail.schemaAction("search"), "ok", 0, null, null,
                InteractionDetail.of("订单", 3, 0.9, List.of("table:demo:app.orders")));

        List<RunStateStore.MissedQuery> missed = store.missedSearchQueries("demo", 10);
        assertEquals(2, missed.size());
        assertEquals("退款单", missed.get(0).query());
        assertEquals(2, missed.get(0).count());
        assertEquals("发货单", missed.get(1).query());
        assertEquals(1, missed.get(1).count());
    }
}
