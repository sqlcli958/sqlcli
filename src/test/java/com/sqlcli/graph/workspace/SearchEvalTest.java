package com.sqlcli.graph.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcli.cli.SchemaActionCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索评估集：Top1/Top5/MRR 指标计算、badcase 列表、CLI 退出码契约。
 */
class SearchEvalTest {

    private static final String ALIAS = "eval-test";

    @TempDir Path temp;

    // ---------------------------------------------------------------- 指标计算

    @Test
    void metricsCoverTop1Top5AndMrr() {
        Map<String, List<String>> canned = Map.of(
                "q1", List.of("a", "b"),          // a 在第 1 位
                "q2", List.of("x", "y", "b"),     // b 在第 3 位
                "q3", List.of("x"));               // 未命中
        Function<String, List<String>> search = canned::get;
        SearchEval.Report report = SearchEval.evaluate(List.of(
                new SearchEval.EvalCase("q1", List.of("a")),
                new SearchEval.EvalCase("q2", List.of("b")),
                new SearchEval.EvalCase("q3", List.of("missing"))), search);

        assertEquals(1.0 / 3, report.top1Rate(), 1e-9);
        assertEquals(2.0 / 3, report.top5Rate(), 1e-9);
        assertEquals((1.0 + 1.0 / 3 + 0) / 3, report.mrr(), 1e-9);
        assertFalse(report.allHit());
        assertEquals(1, report.badcases().size());
        assertEquals("q3", report.badcases().get(0).query());

        SearchEval.CaseResult second = report.results().get(1);
        assertEquals(3, second.bestRank());
        assertFalse(second.top1());
        assertTrue(second.top5());
        assertTrue(second.hit());
    }

    @Test
    void anyExpectedIdCountsAndRankBeyondFiveIsHitOnly() {
        Function<String, List<String>> search =
                query -> List.of("r1", "r2", "r3", "r4", "r5", "r6", "target");
        SearchEval.Report report = SearchEval.evaluate(List.of(
                new SearchEval.EvalCase("q", List.of("nope", "target"))), search);
        assertEquals(7, report.results().get(0).bestRank());
        assertFalse(report.results().get(0).top5());
        assertTrue(report.allHit(), "排名靠后仍算命中，不算 badcase");
        assertEquals(1.0 / 7, report.mrr(), 1e-9);
    }

    @Test
    void expectedIdMatchIsCaseInsensitive() {
        SearchEval.Report report = SearchEval.evaluate(List.of(
                new SearchEval.EvalCase("q", List.of("COLUMN:X:APP.T.C"))),
                query -> List.of("column:x:app.t.c"));
        assertEquals(1, report.results().get(0).bestRank());
    }

    // ---------------------------------------------------------------- CLI 契约

    @Test
    void cliExitsZeroWhenAllCasesHitAndOneOnMiss() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        store.save(seedWorkspace());

        Path good = temp.resolve("good.yaml");
        Files.writeString(good, """
                cases:
                  - query: "orders"
                    expect:
                      - table:%s:app.orders
                """.formatted(ALIAS), StandardCharsets.UTF_8);
        Run pass = run(store, command -> command.setCasesPath(good.toString()));
        assertEquals(0, pass.exitCode(), pass.stderr());

        Path bad = temp.resolve("bad.yaml");
        Files.writeString(bad, """
                cases:
                  - query: "orders"
                    expect:
                      - table:%s:app.orders
                  - query: "不存在的词"
                    expect:
                      - table:%s:app.orders
                """.formatted(ALIAS, ALIAS), StandardCharsets.UTF_8);
        Run fail = run(store, command -> {
            command.setCasesPath(bad.toString());
            command.setJsonOutput(true);
        });
        assertEquals(1, fail.exitCode(), "存在完全未命中的 case 时退出码 1");

        JsonNode data = new ObjectMapper().readTree(fail.stdout()).get("data");
        assertEquals(2, data.get("cases").asInt());
        assertTrue(data.get("top1Rate").isNumber());
        assertTrue(data.get("top5Rate").isNumber());
        assertTrue(data.get("mrr").isNumber());
        assertEquals(2, data.get("results").size());
        assertEquals(1, data.get("badcases").size());
        assertEquals("不存在的词", data.get("badcases").get(0).get("query").asText());
    }

    @Test
    void cliRejectsMissingCasesFileWithUsageError() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        store.save(seedWorkspace());
        Run run = run(store, command -> command.setCasesPath(temp.resolve("nope.yaml").toString()));
        assertEquals(2, run.exitCode());
    }

    // -------------------------------------------------------------------- 辅助

    private GraphWorkspace seedWorkspace() {
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create(ALIAS, "app", "orders", GraphActor.extractor);
        orders.getColumns().add(ColumnWorkspaceNode.create("status"));
        workspace.getTables().put(orders.getId(), orders);
        return workspace;
    }

    private record Run(int exitCode, String stdout, String stderr) {
    }

    private Run run(GraphWorkspaceStore store, java.util.function.Consumer<SchemaActionCommand> configure) {
        SchemaActionCommand command = new SchemaActionCommand(store);
        command.setAlias(ALIAS);
        command.setAction("search-eval");
        command.setCommandArgs(List.of("search-eval"));
        configure.accept(command);

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode;
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            System.setErr(err);
            exitCode = command.executeCommand();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new Run(exitCode, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }
}
