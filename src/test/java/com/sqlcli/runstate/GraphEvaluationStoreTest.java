package com.sqlcli.runstate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** `graph_evaluation` / `graph_finding` 的读写：落库、按时间倒序、分页。 */
class GraphEvaluationStoreTest {

    @TempDir Path temp;

    private RunStateStore store;

    @BeforeEach
    void setUp() {
        store = new RunStateStore(temp.resolve("sqlcli.db"), temp.resolve("execution-history"));
    }

    @Test
    void savesEvaluationWithMetricsAndFindings() throws Exception {
        store.saveGraphEvaluation(evaluation("eval-1", "demo", "fail", 7, 2, 1,
                "{\"tableDescription\":0.5}"), List.of(
                new RunStateStore.GraphFindingRow(0, "term.orphan", "error", "term:demo:工单",
                        "既没有描述也没有映射", "sql-cli demo schema add-term 工单 --description '…'"),
                new RunStateStore.GraphFindingRow(1, "value.unused", "warning", "column:demo:app.orders.flag",
                        "全为 NULL", "sql-cli demo schema edit --column app.orders.flag --description '未启用'")));

        List<RunStateStore.GraphEvaluationRow> evaluations = store.listGraphEvaluations("demo", 10);
        assertEquals(1, evaluations.size());
        RunStateStore.GraphEvaluationRow row = evaluations.get(0);
        assertEquals("fail", row.status());
        assertEquals(7, row.revision());
        assertEquals(1, row.errorCount());
        assertEquals("{\"tableDescription\":0.5}", row.metricsJson());

        assertEquals(2, store.countGraphFindings("eval-1"));
        List<RunStateStore.GraphFindingRow> findings = store.listGraphFindings("eval-1", 0, 50);
        assertEquals("term.orphan", findings.get(0).probe());
        assertTrue(findings.get(0).remediation().startsWith("sql-cli "),
                "修复命令是 finding 的一部分，掉了这列报告就退回成一份抱怨");
    }

    @Test
    void listsMostRecentFirstAndFiltersByAlias() throws Exception {
        store.saveGraphEvaluation(evaluation("eval-old", "demo", "pass", 1, 1000, 0, "{}"), List.of());
        store.saveGraphEvaluation(evaluation("eval-new", "demo", "pass", 2, 2000, 0, "{}"), List.of());
        store.saveGraphEvaluation(evaluation("eval-other", "other", "pass", 1, 3000, 0, "{}"), List.of());

        List<RunStateStore.GraphEvaluationRow> demo = store.listGraphEvaluations("demo", 10);
        assertEquals(List.of("eval-new", "eval-old"),
                demo.stream().map(RunStateStore.GraphEvaluationRow::id).toList());
        assertEquals(3, store.listGraphEvaluations(null, 10).size(), "alias=null 时不筛别名");
        assertEquals(1, store.listGraphEvaluations("demo", 1).size());
    }

    @Test
    void findingsPaginateBySequence() throws Exception {
        List<RunStateStore.GraphFindingRow> rows = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            rows.add(new RunStateStore.GraphFindingRow(i, "desc.dead-ref", "error", "table:demo:app.t" + i,
                    "描述提到不存在的对象", "sql-cli demo schema edit --table app.t" + i + " --description '…'"));
        }
        store.saveGraphEvaluation(evaluation("eval-paged", "demo", "fail", 3, 1, 120, "{}"), rows);

        assertEquals(120, store.countGraphFindings("eval-paged"));
        List<RunStateStore.GraphFindingRow> second = store.listGraphFindings("eval-paged", 50, 50);
        assertEquals(50, second.size());
        assertEquals(50, second.get(0).seq());
        assertEquals(20, store.listGraphFindings("eval-paged", 100, 50).size());
    }

    @Test
    void evaluationWithoutMetricsIsAllowed() throws Exception {
        // value-domain 产的那种：只有 finding，没有指标——趋势图不该拿它连线
        store.saveGraphEvaluation(new RunStateStore.GraphEvaluationRow("eval-vd", "demo",
                "value-domain", "pass", 4, 500L, null, 0, 1, null), List.of(
                new RunStateStore.GraphFindingRow(0, "value.unused", "warning", "column:demo:app.orders.flag",
                        "全为 NULL", "sql-cli demo schema edit --column app.orders.flag --description '未启用'")));

        RunStateStore.GraphEvaluationRow row = store.listGraphEvaluations("demo", 10).get(0);
        assertEquals("value-domain", row.source());
        assertNull(row.metricsJson());
        assertNull(row.elapsedMs());
    }

    private RunStateStore.GraphEvaluationRow evaluation(String id, String alias, String status,
            long revision, long startedAt, int errors, String metrics) {
        return new RunStateStore.GraphEvaluationRow(id, alias, "eval", status, revision, startedAt,
                12L, errors, 1, metrics);
    }
}
