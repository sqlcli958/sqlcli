package com.sqlcli.graph.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcli.SqlCli;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务级评估的评分器：三类归因、pitfall 机判、缺组的退化行为、CLI 契约。
 */
class TaskEvalTest {

    @TempDir Path temp;

    // ---------------------------------------------------------------- 归因

    @Test
    void threeVerdictsSplitGraphFromSkillFailures() {
        TaskEval.TaskCase pass = simpleCase("pass-case");
        TaskEval.TaskCase graph = simpleCase("graph-case");
        TaskEval.TaskCase skill = simpleCase("skill-case");
        TaskEval.Answer right = answer(List.of("app.orders"));
        TaskEval.Answer wrong = answer(List.of("app.order_items"));

        TaskEval.Report report = TaskEval.evaluate(List.of(pass, graph, skill), Map.of(
                "pass-case", Map.of(TaskEval.CONTROL, right, TaskEval.CHEAT, right),
                "graph-case", Map.of(TaskEval.CONTROL, wrong, TaskEval.CHEAT, right),
                "skill-case", Map.of(TaskEval.CONTROL, wrong, TaskEval.CHEAT, wrong)));

        assertEquals(TaskEval.Verdict.passed, report.results().get(0).verdict());
        assertEquals(TaskEval.Verdict.graph, report.results().get(1).verdict(),
                "对照组失败、作弊组通过 = 图谱信息不足");
        assertEquals(TaskEval.Verdict.skill, report.results().get(2).verdict(),
                "给了正确表还做不对 = skill 问题");
        assertEquals(1.0 / 3, report.passRate(), 1e-9);
        assertFalse(report.allPassed());
        assertEquals(List.of("orders"), report.results().get(1).control().missingTables());
    }

    @Test
    void missingCheatGroupDegradesToUnattributable() {
        TaskEval.TaskCase failing = simpleCase("only-control");
        TaskEval.TaskCase passing = simpleCase("only-control-pass");

        TaskEval.Report report = TaskEval.evaluate(List.of(failing, passing), Map.of(
                "only-control", Map.of(TaskEval.CONTROL, answer(List.of("app.other"))),
                "only-control-pass", Map.of(TaskEval.CONTROL, answer(List.of("app.orders")))));

        TaskEval.CaseResult unattributable = report.results().get(0);
        assertEquals(TaskEval.Verdict.unattributable, unattributable.verdict());
        assertTrue(unattributable.note().contains("作弊组缺失"), unattributable.note());
        assertNull(unattributable.cheat());
        assertEquals(TaskEval.Verdict.passed, report.results().get(1).verdict(),
                "对照组通过就够了——归因只在失败时才需要作弊组");
    }

    @Test
    void noAnswerAtAllIsUnattributableNotSilentPass() {
        TaskEval.Report report = TaskEval.evaluate(List.of(simpleCase("nobody-answered")), Map.of());
        assertEquals(TaskEval.Verdict.unattributable, report.results().get(0).verdict());
        assertTrue(report.results().get(0).note().contains("没有作答"));
    }

    // ---------------------------------------------------------------- pitfall

    @Test
    void forbiddenTableIsHitEvenWhenTablesAndJoinsAreRight() {
        TaskEval.TaskCase withPitfall = new TaskEval.TaskCase("p", "任务", List.of("orders"),
                List.of("orders → users"),
                List.of(new TaskEval.Pitfall("用了配置表", List.of("order_snapshot"), List.of())),
                null);
        TaskEval.Answer answer = new TaskEval.Answer(List.of("app.orders", "app.users", "app.order_snapshot"),
                List.of("orders.user_id = users.id"), null, Map.of());

        TaskEval.GroupResult result = TaskEval.evaluate(List.of(withPitfall),
                Map.of("p", Map.of(TaskEval.CONTROL, answer))).results().get(0).control();

        assertTrue(result.missingTables().isEmpty());
        assertTrue(result.missingJoins().isEmpty(), "表和 JOIN 都对");
        assertEquals(List.of("用了配置表"), result.pitfalls(), "只有 pitfall 抓得到选错相近表");
        assertFalse(result.pass());
    }

    @Test
    void requireCatchesOmissionAndWhitespaceIsNormalized() {
        TaskEval.Pitfall pitfall = new TaskEval.Pitfall("漏了免扫码过滤", List.of(), List.of("is_scan = 1"));
        TaskEval.TaskCase taskCase = new TaskEval.TaskCase("r", "任务", List.of(), List.of(),
                List.of(pitfall), null);

        TaskEval.Answer omitted = new TaskEval.Answer(List.of(), List.of(),
                "SELECT * FROM plans", Map.of());
        TaskEval.Answer spaced = new TaskEval.Answer(List.of(), List.of(),
                "SELECT * FROM plans WHERE is_scan=1", Map.of());

        assertEquals(1, groupOf(taskCase, omitted).pitfalls().size(), "缺失即踩中");
        assertTrue(groupOf(taskCase, spaced).pass(), "去空白后 is_scan=1 与 is_scan = 1 是同一件事");
    }

    @Test
    void joinDirectionMatters() {
        TaskEval.TaskCase taskCase = new TaskEval.TaskCase("j", "任务", List.of(),
                List.of("erp_report.option_id → erp_point.id"), List.of(), null);

        TaskEval.Answer forward = new TaskEval.Answer(List.of(),
                List.of("erp_report.option_id = erp_point.id"), null, Map.of());
        TaskEval.Answer reversed = new TaskEval.Answer(List.of(),
                List.of("erp_point.id = erp_report.other_col"), null, Map.of());

        assertTrue(groupOf(taskCase, forward).missingJoins().isEmpty());
        assertEquals(1, groupOf(taskCase, reversed).missingJoins().size());
    }

    // ---------------------------------------------------------------- 解析

    @Test
    void pitfallWithoutMachineRuleIsRejected() throws Exception {
        Path cases = write("prose.yaml", """
                tasks:
                  - id: a
                    task: "t"
                    expect:
                      pitfalls:
                        - desc: "选错了名字相近的那张表"
                """);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TaskEval.parseCases(cases));
        assertTrue(error.getMessage().contains("forbid/require"), error.getMessage());
    }

    @Test
    void shippedEvalSetParsesAndEveryPitfallIsMachineCheckable() throws Exception {
        List<TaskEval.TaskCase> cases = TaskEval.parseCases(Path.of("docs/task-eval-erp-inspection.yaml"));
        assertEquals(10, cases.size());
        for (TaskEval.TaskCase taskCase : cases) {
            assertFalse(taskCase.pitfalls().isEmpty(), taskCase.id() + " 没有 pitfall，测不出安静出错");
            assertFalse(taskCase.tables().isEmpty(), taskCase.id() + " 没有期望表");
        }
    }

    /** 模板作答配模板评估集必须判成「1 通过 + 1 图谱侧」——文档里写的三种归因得是真的。 */
    @Test
    void shippedAnswerTemplateDemonstratesPassAndGraphFailure() throws Exception {
        TaskEval.Report report = TaskEval.evaluate(
                TaskEval.parseCases(Path.of("docs/task-eval-erp-inspection.yaml")),
                TaskEval.parseAnswers(Path.of("docs/task-eval-answers.example.yaml")));

        assertEquals(TaskEval.Verdict.passed,
                verdictOf(report, "scan-required-plans"));
        assertEquals(TaskEval.Verdict.graph,
                verdictOf(report, "inspect-completion-rate"));
        assertEquals(8, report.byVerdict(TaskEval.Verdict.unattributable).size(),
                "模板只答了 2 条，其余 8 条没人作答");
    }

    // ---------------------------------------------------------------- CLI 契约

    @Test
    void cliScoresAnswersAndExitsNonZeroOnFailure() throws Exception {
        Path cases = write("cases.yaml", """
                tasks:
                  - id: t1
                    task: "统计完成率"
                    skillRef: skills/sql-cli/references/query.md
                    expect:
                      tables: [plan_point]
                      joins: []
                      pitfalls:
                        - desc: "用了配置表"
                          forbid: [task_point]
                  - id: t2
                    task: "列出强制扫码的计划"
                    skillRef: skills/sql-cli/references/query.md
                    expect:
                      tables: [task_plan]
                      joins: []
                      pitfalls:
                        - desc: "值域取反"
                          require: ["is_scan = 1"]
                """);
        Path answers = write("answers.yaml", """
                answers:
                  t1:
                    control:
                      tables: [task_point]
                      joins: []
                    cheat:
                      tables: [plan_point]
                      joins: []
                  t2:
                    control:
                      tables: [task_plan]
                      sql: "SELECT * FROM task_plan WHERE is_scan = 0"
                    cheat:
                      tables: [task_plan]
                      sql: "SELECT * FROM task_plan WHERE is_scan = 0"
                  typo-id:
                    control:
                      tables: []
                """);

        CliResult text = runCli("demo", "schema", "task-eval",
                "--cases", cases.toString(), "--answers", answers.toString());
        assertEquals(1, text.exitCode(), text.stderr());
        assertTrue(text.stdout().contains("图谱信息不足"), text.stdout());
        assertTrue(text.stdout().contains("用了配置表"), text.stdout());
        assertTrue(text.stdout().contains("skill 问题"), text.stdout());
        assertTrue(text.stdout().contains("改 skills/sql-cli/references/query.md"),
                "skill 侧失败必须指向该改的那份文档");
        assertTrue(text.stdout().contains("typo-id"), "作答里多出来的 id 要说出来");

        CliResult json = runCli("demo", "schema", "task-eval",
                "--cases", cases.toString(), "--answers", answers.toString(), "--json");
        assertEquals(1, json.exitCode());
        JsonNode data = new ObjectMapper().readTree(json.stdout()).get("data");
        assertEquals(2, data.get("cases").asInt());
        assertEquals(1, data.get("graphFailures").asInt());
        assertEquals(1, data.get("skillFailures").asInt());
        assertEquals(0.0, data.get("passRate").asDouble(), 1e-9);
        assertEquals("graph", data.get("results").get(0).get("verdict").asText());
        assertEquals("skill", data.get("results").get(1).get("verdict").asText());
        assertEquals("typo-id", data.get("unknownAnswerIds").get(0).asText());
    }

    @Test
    void cliRequiresBothFilesAndReportsMissingOnesAsUsageError() throws Exception {
        Path cases = write("only-cases.yaml", """
                tasks:
                  - id: t1
                    expect:
                      tables: [t]
                      pitfalls:
                        - desc: d
                          forbid: [x]
                """);
        assertEquals(2, runCli("demo", "schema", "task-eval", "--cases", cases.toString()).exitCode());
        assertEquals(2, runCli("demo", "schema", "task-eval",
                "--cases", cases.toString(),
                "--answers", temp.resolve("nope.yaml").toString()).exitCode());
    }

    // -------------------------------------------------------------------- 辅助

    private static TaskEval.Verdict verdictOf(TaskEval.Report report, String id) {
        return report.results().stream().filter(result -> result.id().equals(id)).findFirst()
                .orElseThrow().verdict();
    }

    private TaskEval.GroupResult groupOf(TaskEval.TaskCase taskCase, TaskEval.Answer answer) {
        return TaskEval.evaluate(List.of(taskCase), Map.of(taskCase.id(), Map.of(TaskEval.CONTROL, answer)))
                .results().get(0).control();
    }

    private static TaskEval.TaskCase simpleCase(String id) {
        return new TaskEval.TaskCase(id, "任务 " + id, List.of("orders"), List.of(), List.of(), null);
    }

    private static TaskEval.Answer answer(List<String> tables) {
        return new TaskEval.Answer(tables, List.of(), null, Map.of());
    }

    private Path write(String name, String content) throws Exception {
        Path file = temp.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private record CliResult(int exitCode, String stdout, String stderr) {
    }

    private CliResult runCli(String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            System.setErr(err);
            int exitCode = SqlCli.run(args);
            return new CliResult(exitCode, stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }
}
