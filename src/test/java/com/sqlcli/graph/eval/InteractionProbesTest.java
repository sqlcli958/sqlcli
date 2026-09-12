package com.sqlcli.graph.eval;

import com.sqlcli.runstate.InteractionDetail;
import com.sqlcli.runstate.RunStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 失败的交互 → 可执行的发现。
 *
 * <p>这套探针要回答的不是「出过几次错」，是**「该修图谱还是该改技能」**——
 * 两类混在一起报，就是 task-eval 那份文档说的「通过率是一个无法归因的数字」。
 */
class InteractionProbesTest {

    private static final String ALIAS = "probe-demo";

    private RunStateStore store(Path dir) {
        return new RunStateStore(dir.resolve("sqlcli.db"), dir.resolve("history"));
    }

    private void fail(RunStateStore store, String action, String error) {
        store.recordInteraction(ALIAS, action, "failed", 1, error, 10L,
                InteractionDetail.of(null, null, null, null));
    }

    private GraphFinding findByProbe(List<GraphFinding> findings, String probe) {
        return findings.stream().filter(f -> f.probe().equals(probe)).findFirst().orElse(null);
    }

    @Test
    void classifiesTableMissColumnMissAndGateRejectSeparately(@TempDir Path dir) {
        RunStateStore store = store(dir);
        fail(store, "query", "Table 'probe_demo.sys_logininfor' doesn't exist");
        fail(store, "schema describe", "Table not found: probe_demo.no_such_table");
        fail(store, "query", "Unknown column 'login_name' in 'field list'");
        // 中文那条是主力：sql-cli 拿图谱预检，还没打到库就拦下了。
        // 第一版正则只写了英文，真库上最常见的这条反而漏掉了
        fail(store, "query", "提示: 列 'no_such_col_xyz' 在图谱中未找到，相近的列: app.a.no");
        fail(store, "schema add-term", "拒绝写入空壳术语：带上 --map 或 --aliases 重试。");

        List<GraphFinding> findings = InteractionProbes.run(store, ALIAS, 0);

        // 三类各自成条，而不是混成一堆「出错了 4 次」
        assertTrue(findings.size() >= 3, findings.toString());
        GraphFinding tableMiss = findByProbe(findings, "agent.table-miss");
        GraphFinding columnMiss = findByProbe(findings, "agent.column-miss");
        GraphFinding gateReject = findByProbe(findings, "agent.gate-reject");
        assertTrue(tableMiss != null && columnMiss != null && gateReject != null, findings.toString());

        // 修图谱的两类，修复命令必须可执行
        assertTrue(tableMiss.remediation().startsWith("sql-cli " + ALIAS), tableMiss.toString());
        assertTrue(columnMiss.remediation().startsWith("sql-cli " + ALIAS), columnMiss.toString());
        // 用法问题那一类指向技能文档，**不该**给一条改图谱的命令——改错了地方比不改还糟
        assertFalse(gateReject.remediation().startsWith("sql-cli "), gateReject.toString());
        assertTrue(gateReject.remediation().contains("graph-write.md"), gateReject.toString());
    }

    @Test
    void recognisesTheToolsOwnChineseHintNotJustDatabaseErrors(@TempDir Path dir) {
        RunStateStore store = store(dir);
        fail(store, "query", "提示: 列 'no_such_col_xyz' 在图谱中未找到，相近的列: app.a.no");
        fail(store, "schema describe", "表 'ghost_table' 在图谱中未找到");

        List<GraphFinding> findings = InteractionProbes.run(store, ALIAS, 0);

        assertEquals("no_such_col_xyz", findByProbe(findings, "agent.column-miss").targetId());
        assertEquals("ghost_table", findByProbe(findings, "agent.table-miss").targetId());
    }

    @Test
    void repeatedSameErrorIsOneFindingWithACount(@TempDir Path dir) {
        RunStateStore store = store(dir);
        for (int i = 0; i < 5; i++) {
            fail(store, "query", "Table 'probe_demo.ghost' doesn't exist");
        }

        List<GraphFinding> findings = InteractionProbes.run(store, ALIAS, 0);

        // 撞 5 次是一条带次数的发现，不是 5 条——5 条会把工作队列淹掉，
        // 而「撞了 5 次」本身才是优先级信号
        assertEquals(1, findings.size(), findings.toString());
        assertTrue(findings.get(0).message().contains("5 次"), findings.get(0).message());
    }

    @Test
    void allFindingsAreWarningsSoOneTypoDoesNotFailTheEvaluation(@TempDir Path dir) {
        RunStateStore store = store(dir);
        fail(store, "query", "Table 'probe_demo.ghost' doesn't exist");

        List<GraphFinding> findings = InteractionProbes.run(store, ALIAS, 0);

        // 硬错误会让 schema eval 退出码非 0。agent 猜错一个表名不是图谱的错，
        // 算成硬错误会让评估因为别人手滑而失败
        assertTrue(findings.stream().allMatch(f -> f.severity() == GraphFinding.Severity.warning),
                findings.toString());
    }

    @Test
    void onlyLooksInsideTheWindow(@TempDir Path dir) {
        RunStateStore store = store(dir);
        fail(store, "query", "Table 'probe_demo.ghost' doesn't exist");

        // 窗口起点设在未来 = 窗口内没有失败。全历史累计的话，修好的问题会一直
        // 重复出现在报告里，最后变成第二个 policy（1487 条违规、十天没人再跑）
        assertTrue(InteractionProbes.run(store, ALIAS, System.currentTimeMillis() + 60_000).isEmpty());
    }

    @Test
    void successfulInteractionsProduceNothing(@TempDir Path dir) {
        RunStateStore store = store(dir);
        store.recordInteraction(ALIAS, "schema search", "ok", 0, null, 5L,
                InteractionDetail.of("巡检", 3, 0.9, List.of("table:probe-demo:app.plan")));

        assertTrue(InteractionProbes.run(store, ALIAS, 0).isEmpty());
    }
}
