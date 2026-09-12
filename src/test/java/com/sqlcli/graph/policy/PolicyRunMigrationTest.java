package com.sqlcli.graph.policy;

import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sqlcli.runstate.RunStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 老的 {@code policy/runs/} 评估目录一次性搬进运行库。
 *
 * <p>为什么值得一条测试：这批数据是真正在涨的那一块——实测 3 轮评估就 2MB、5 万行违规，
 * 而原来的 {@code listEvaluations} 每次都全目录扫。搬完之后列表走索引，
 * 「这条规则历史上违规多少次」这类查询才有可能。
 */
class PolicyRunMigrationTest {

    @TempDir Path temp;

    private PolicyStore store() {
        GraphWorkspaceStore workspaces = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = GraphWorkspace.create("demo", "mysql");
        try {
            workspaces.save(workspace);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new PolicyStore(workspaces,
                new RunStateStore(temp.resolve("sqlcli.db"), temp.resolve("history")));
    }

    /** 造一轮升级前形态的评估：三个文件一个目录。 */
    private Path writeLegacyRun(String evaluationId, String startedAt) throws Exception {
        Path run = temp.resolve("demo/policy/runs").resolve(evaluationId);
        Files.createDirectories(run);
        Files.writeString(run.resolve("evaluation.json"), """
                {
                  "id": "%s",
                  "sourceAlias": "demo",
                  "ruleSetId": "structure",
                  "ruleSetVersion": "1",
                  "status": "violations",
                  "startedAt": "%s",
                  "evaluatedRules": 1,
                  "violationCount": 1
                }
                """.formatted(evaluationId, startedAt));
        Files.writeString(run.resolve("ruleset.yaml"), """
                id: structure
                version: "1"
                """);
        Files.writeString(run.resolve("violations.jsonl"), """
                {"id":"v-%s","evaluationId":"%s","ruleId":"pk","targetId":"table:demo:app.orders",\
"severity":"error","message":"primary key is required","status":"open"}
                """.formatted(evaluationId, evaluationId));
        return run;
    }

    @Test
    void legacyRunDirectoriesAreImportedOnFirstRead() throws Exception {
        writeLegacyRun("evaluation-old-1", "2026-08-21T00:19:00");
        writeLegacyRun("evaluation-old-2", "2026-08-21T00:30:00");
        PolicyStore store = store();

        List<RuleEvaluation> listed = store.listEvaluations("demo");

        assertEquals(2, listed.size(), "两轮老评估都要搬进来");
        assertEquals("evaluation-old-2", listed.get(0).getId(), "最近的在前");
        assertEquals("structure", listed.get(0).getRuleSetId());

        PolicyRun loaded = store.loadRun("demo", "evaluation-old-1");
        assertEquals(1, loaded.violations().size(), "违规明细跟着一起搬");
        assertEquals("pk", loaded.violations().get(0).getRuleId());
        assertEquals("structure", loaded.ruleSet().getId(), "当时用的规则集原文也要留住");

        assertTrue(Files.exists(temp.resolve("demo/policy/.runs-imported")), "写标记，免得每次都扫目录");
        assertTrue(Files.isDirectory(temp.resolve("demo/policy/runs/evaluation-old-1")),
                "老目录保留——和当初 JSONL 历史一样，确认没问题再由人删");
    }

    /** 重复调用不能把同一轮评估搬两遍。 */
    @Test
    void importIsIdempotent() throws Exception {
        writeLegacyRun("evaluation-old-1", "2026-08-21T00:19:00");
        PolicyStore store = store();

        assertEquals(1, store.listEvaluations("demo").size());
        Files.deleteIfExists(temp.resolve("demo/policy/.runs-imported"));
        assertEquals(1, store.listEvaluations("demo").size(), "标记没了也不该搬出第二份");
    }

    /** 新评估直接进库，不再往 policy/runs/ 下写目录。 */
    @Test
    void newRunsGoStraightToTheDatabase() throws Exception {
        PolicyStore store = store();
        RuleEvaluation evaluation = new RuleEvaluation();
        evaluation.setId("evaluation-new");
        evaluation.setSourceAlias("demo");
        evaluation.setRuleSetId("structure");
        evaluation.setStartedAt(java.time.LocalDateTime.now());
        RuleSet ruleSet = new RuleSet();
        ruleSet.setId("structure");

        store.saveRun("demo", evaluation, ruleSet, List.of());

        assertEquals(1, store.listEvaluations("demo").size());
        assertTrue(Files.notExists(temp.resolve("demo/policy/runs/evaluation-new")),
                "不该再往文件系统写评估目录");
    }
}
