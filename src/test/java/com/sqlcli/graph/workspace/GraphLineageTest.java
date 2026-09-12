package com.sqlcli.graph.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcli.cli.SchemaActionCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 字段级血缘：存取（save/load 往返）、按 target/source 的深度遍历、CLI 写入与查询。
 */
class GraphLineageTest {

    private static final String ALIAS = "lineage-test";

    @TempDir Path temp;

    // ---------------------------------------------------------------- 模型与存取

    @Test
    void lineageIdIsDeterministicSoRewriteIsIdempotent() {
        LineageRecord first = LineageRecord.create(ALIAS, "column:a:s.t.c",
                List.of("column:a:s.t.a", "column:a:s.t.b"), "SUM(a)+SUM(b)", "view_x", GraphActor.agent);
        LineageRecord second = LineageRecord.create(ALIAS, "column:a:s.t.c",
                List.of("column:a:s.t.a", "column:a:s.t.b"), "SUM(a)+SUM(b)", "view_x", GraphActor.agent);
        assertEquals(first.getId(), second.getId());

        LineageRecord otherThrough = LineageRecord.create(ALIAS, "column:a:s.t.c",
                List.of("column:a:s.t.a", "column:a:s.t.b"), "SUM(a)+SUM(b)", "job_y", GraphActor.agent);
        assertNotEquals(first.getId(), otherThrough.getId(), "through 不同是另一条血缘");

        LineageRecord otherExpression = LineageRecord.create(ALIAS, "column:a:s.t.c",
                List.of("column:a:s.t.a"), "SUM(a)", "view_x", GraphActor.agent);
        assertEquals(first.getId(), otherExpression.getId(),
                "同一段代码写同一列只有一条记录：改 sources / expression 是更新，不是新增");
    }

    @Test
    void kindIsInferredOnlyForTheTwoUnambiguousShapes() {
        assertEquals(LineageKind.identity, LineageKind.infer(List.of("a"), null));
        assertEquals(LineageKind.transformation, LineageKind.infer(List.of("a", "b"), "a+b"));
        assertEquals(null, LineageKind.infer(List.of("a", "b"), null), "多源无表达式认不出来，必须显式给");
        assertEquals(LineageKind.identity,
                LineageRecord.create(ALIAS, "column:a:s.t.c", List.of("column:a:s.t.a"), null, "x", GraphActor.agent)
                        .getLineageKind());
    }

    @Test
    void shapeValidationSharedByGateAndProbe() {
        assertEquals(null, LineageKind.validate(LineageKind.identity, "t", List.of("a"), null));
        assertTrue(LineageKind.validate(LineageKind.identity, "t", List.of("a", "b"), null).contains("transformation"));
        assertTrue(LineageKind.validate(LineageKind.identity, "t", List.of("a"), "f(a)").contains("transformation"));
        assertTrue(LineageKind.validate(LineageKind.aggregation, "t", List.of("a"), null).contains("--expression"));
        assertTrue(LineageKind.validate(LineageKind.rule, "t", List.of(), "when x").contains("源列"));
        assertTrue(LineageKind.validate(LineageKind.transformation, "T", List.of("t"), "f").contains("不是自己的上游"),
                "源含目标按大小写不敏感比");
    }

    @Test
    void oldRecordsWithoutKindGetInferredAtLoadButNotWrittenBack() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = seedWorkspace();
        LineageRecord record = LineageRecord.create(ALIAS, columnId("app.summary.total"),
                List.of(columnId("app.orders.price")), null, "copy", GraphActor.agent);
        record.setLineageKind(null);
        workspace.getLineage().put(record.getId(), record);
        store.save(workspace);

        assertEquals(LineageKind.identity, store.load(ALIAS).getLineage().get(record.getId()).getLineageKind());
        String onDisk = java.nio.file.Files.readString(
                java.nio.file.Files.walk(temp).filter(f -> f.getFileName().toString().equals("lineage.jsonl"))
                        .findFirst().orElseThrow());
        assertTrue(!onDisk.contains("lineageKind"), "推断值只在内存里补，不回写");
    }

    @Test
    void agentLineageIsCandidateWithAgentActor() {
        LineageRecord record = LineageRecord.create(ALIAS, "column:a:s.t.c",
                List.of("column:a:s.t.a"), null, null, GraphActor.agent);
        assertEquals(GraphStatus.candidate, record.getStatus());
        assertEquals(GraphActor.agent, record.getCreatedBy());
        assertEquals(GraphObjectKind.lineage, record.getKind());
    }

    @Test
    void storeRoundTripKeepsLineage() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = seedWorkspace();
        LineageRecord record = LineageRecord.create(ALIAS,
                columnId("app.summary.total"),
                List.of(columnId("app.orders.price"), columnId("app.orders.qty")),
                "SUM(price*qty)", "view_summary", GraphActor.agent);
        workspace.getLineage().put(record.getId(), record);
        store.save(workspace);

        GraphWorkspace loaded = store.load(ALIAS);
        assertEquals(1, loaded.getLineage().size());
        LineageRecord reloaded = loaded.getLineage().get(record.getId());
        assertEquals(record.getTarget(), reloaded.getTarget());
        assertEquals(record.getSources(), reloaded.getSources());
        assertEquals("SUM(price*qty)", reloaded.getExpression());
        assertEquals("view_summary", reloaded.getThrough());
        assertEquals(GraphStatus.candidate, reloaded.getStatus());
    }

    @Test
    void oldWorkspaceWithoutLineageFileLoadsAsEmpty() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        store.save(seedWorkspace());
        // 模拟旧图谱：把当前代里的 lineage 文件删掉
        try (var stream = java.nio.file.Files.walk(temp)) {
            for (Path path : stream.filter(p -> p.getFileName().toString().equals("lineage.jsonl")).toList()) {
                java.nio.file.Files.delete(path);
            }
        }
        assertEquals(0, store.load(ALIAS).getLineage().size());
    }

    @Test
    void snapshotExportImportKeepsLineage() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = seedWorkspace();
        LineageRecord record = LineageRecord.create(ALIAS, columnId("app.summary.total"),
                List.of(columnId("app.orders.price")), null, null, GraphActor.agent);
        workspace.getLineage().put(record.getId(), record);

        Path snapshot = temp.resolve("export.json");
        store.exportSnapshot(workspace, snapshot);
        GraphWorkspace imported = store.importSnapshot(snapshot);
        assertEquals(1, imported.getLineage().size());
        assertTrue(imported.getLineage().containsKey(record.getId()));
    }

    // ---------------------------------------------------------------- 深度遍历

    @Test
    void upstreamTraversalFollowsDepthAndDownstreamReverses() {
        // a -> b1 (rec2), {b1,b2} -> c (rec1)
        LineageRecord rec1 = LineageRecord.create(ALIAS, "column:a:s.t.c",
                List.of("column:a:s.t.b1", "column:a:s.t.b2"), "b1+b2", null, GraphActor.agent);
        LineageRecord rec2 = LineageRecord.create(ALIAS, "column:a:s.t.b1",
                List.of("column:a:s.t.a"), null, "etl_job", GraphActor.agent);
        LineageGraph graph = new LineageGraph(List.of(rec1, rec2));

        assertEquals(List.of(rec1), graph.upstream("column:a:s.t.c", 1));
        assertEquals(List.of(rec1, rec2), graph.upstream("column:a:s.t.c", 2), "深度 2 追到 a->b1");
        assertEquals(List.of(rec2), graph.upstream("column:a:s.t.B1", 1), "列 id 大小写不敏感");

        assertEquals(List.of(rec2, rec1), graph.downstream("column:a:s.t.a", 2));
        assertEquals(List.of(rec2), graph.downstream("column:a:s.t.a", 1));
        assertTrue(graph.upstream("column:a:s.t.unknown", 3).isEmpty());
    }

    @Test
    void cyclicLineageTerminates() {
        LineageRecord forward = LineageRecord.create(ALIAS, "column:a:s.t.x",
                List.of("column:a:s.t.y"), null, null, GraphActor.agent);
        LineageRecord backward = LineageRecord.create(ALIAS, "column:a:s.t.y",
                List.of("column:a:s.t.x"), null, null, GraphActor.agent);
        LineageGraph graph = new LineageGraph(List.of(forward, backward));
        assertEquals(2, graph.upstream("column:a:s.t.x", 10).size(), "环上每条记录只出现一次");
    }

    // ---------------------------------------------------------------- CLI 契约

    @Test
    void addLineageThenQueryViaCli() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        store.save(seedWorkspace());

        Run add = run(store, "add-lineage", command -> {
            command.setTargetRef("app.summary.total");
            command.setSourceRefsCsv("app.orders.price,app.orders.qty");
            command.setExpression("SUM(price*qty)");
            command.setThrough("view_summary");
        });
        assertEquals(0, add.exitCode(), add.stderr());

        GraphWorkspace saved = store.load(ALIAS);
        assertEquals(1, saved.getLineage().size());
        LineageRecord record = saved.getLineage().values().iterator().next();
        assertEquals(GraphStatus.candidate, record.getStatus(), "agent 写入是候选");
        assertEquals(GraphActor.agent, record.getCreatedBy());
        assertEquals(columnId("app.summary.total"), record.getTarget());
        assertEquals(List.of(columnId("app.orders.price"), columnId("app.orders.qty")), record.getSources());

        // 幂等：同一条再写一次不产生第二条记录
        Run again = run(store, "add-lineage", command -> {
            command.setTargetRef("app.summary.total");
            command.setSourceRefsCsv("app.orders.price,app.orders.qty");
            command.setExpression("SUM(price*qty)");
            command.setThrough("view_summary");
        });
        assertEquals(0, again.exitCode(), again.stderr());
        assertEquals(1, store.load(ALIAS).getLineage().size());

        Run query = run(store, "lineage", command -> {
            command.setTableName("app.summary.total");
            command.setJsonOutput(true);
        });
        assertEquals(0, query.exitCode(), query.stderr());
        JsonNode data = new ObjectMapper().readTree(query.stdout()).get("data");
        assertEquals("upstream", data.get("direction").asText());
        assertEquals(1, data.get("records").size());
        JsonNode json = data.get("records").get(0);
        assertEquals("SUM(price*qty)", json.get("expression").asText());
        assertEquals("view_summary", json.get("through").asText());
        assertEquals("candidate", json.get("status").asText());

        Run downstream = run(store, "lineage", command -> {
            command.setTableName("app.orders.price");
            command.setLineageDownstream(true);
            command.setJsonOutput(true);
        });
        assertEquals(0, downstream.exitCode(), downstream.stderr());
        JsonNode downData = new ObjectMapper().readTree(downstream.stdout()).get("data");
        assertEquals("downstream", downData.get("direction").asText());
        assertEquals(1, downData.get("records").size());
    }

    @Test
    void addLineageRejectsUnknownColumn() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        store.save(seedWorkspace());

        Run run = run(store, "add-lineage", command -> {
            command.setTargetRef("app.summary.total");
            command.setSourceRefsCsv("app.orders.not_there");
            command.setThrough("view_summary");
        });
        assertEquals(1, run.exitCode());
        assertTrue(store.load(ALIAS).getLineage().isEmpty(), "拒绝路径不得写入");
    }

    @Test
    void addLineageGatesRejectBadShapesWithAFix() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        store.save(seedWorkspace());

        Run noThrough = run(store, "add-lineage", command -> {
            command.setTargetRef("app.summary.total");
            command.setSourceRefsCsv("app.orders.price");
        });
        assertEquals(2, noThrough.exitCode());
        assertTrue(noThrough.stderr().contains("--through"), noThrough.stderr());

        Run selfRef = run(store, "add-lineage", command -> {
            command.setTargetRef("app.summary.total");
            command.setSourceRefsCsv("APP.SUMMARY.TOTAL");
            command.setThrough("submit");
        });
        assertEquals(2, selfRef.exitCode());
        assertTrue(selfRef.stderr().contains("--description"), "自指要指向正确的落点：" + selfRef.stderr());

        Run identityWithExpression = run(store, "add-lineage", command -> {
            command.setTargetRef("app.summary.total");
            command.setSourceRefsCsv("app.orders.price");
            command.setLineageKindArg("identity");
            command.setExpression("price*2");
            command.setThrough("view_summary");
        });
        assertEquals(2, identityWithExpression.exitCode());
        assertTrue(identityWithExpression.stderr().contains("transformation"), identityWithExpression.stderr());

        Run ambiguous = run(store, "add-lineage", command -> {
            command.setTargetRef("app.summary.total");
            command.setSourceRefsCsv("app.orders.price,app.orders.qty");
            command.setThrough("view_summary");
        });
        assertEquals(2, ambiguous.exitCode());
        assertTrue(ambiguous.stderr().contains("aggregation") && ambiguous.stderr().contains("rule"),
                "多源无表达式要告诉人两个可选类别：" + ambiguous.stderr());

        Run badKind = run(store, "add-lineage", command -> {
            command.setTargetRef("app.summary.total");
            command.setSourceRefsCsv("app.orders.price");
            command.setLineageKindArg("copy");
            command.setThrough("x");
        });
        assertEquals(2, badKind.exitCode());
        assertTrue(store.load(ALIAS).getLineage().isEmpty(), "闸门拦下的一条都不写");
    }

    @Test
    void explicitKindIsStoredAndShownWithOpenLineageMapping() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        store.save(seedWorkspace());

        Run add = run(store, "add-lineage", command -> {
            command.setTargetRef("app.summary.total");
            command.setSourceRefsCsv("app.orders.price");
            command.setLineageKindArg("rule");
            command.setExpression("price 非空时写入当前时间");
            command.setThrough("OrderService.submit");
        });
        assertEquals(0, add.exitCode(), add.stderr());
        assertEquals(LineageKind.rule, store.load(ALIAS).getLineage().values().iterator().next().getLineageKind());

        Run query = run(store, "lineage", command -> {
            command.setTableName("app.summary.total");
            command.setJsonOutput(true);
        });
        JsonNode json = new ObjectMapper().readTree(query.stdout()).get("data").get("records").get(0);
        assertEquals("rule", json.get("lineageKind").asText());
        assertEquals("INDIRECT/CONDITIONAL", json.get("openLineage").asText(), "映射值按 kind 算出，不落盘");
    }

    @Test
    void removeLineageByTargetThenById() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        store.save(seedWorkspace());
        for (String through : List.of("job_a", "job_b")) {
            Run add = run(store, "add-lineage", command -> {
                command.setTargetRef("app.summary.total");
                command.setSourceRefsCsv("app.orders.price");
                command.setThrough(through);
            });
            assertEquals(0, add.exitCode(), add.stderr());
        }
        assertEquals(2, store.load(ALIAS).getLineage().size(), "through 不同是两条");

        Run ambiguous = run(store, "remove-lineage", command -> command.setTargetRef("app.summary.total"));
        assertEquals(1, ambiguous.exitCode());
        assertTrue(ambiguous.stderr().contains("--id lineage:"), "多条时列出 id 让人挑：" + ambiguous.stderr());
        assertEquals(2, store.load(ALIAS).getLineage().size());

        Run byThrough = run(store, "remove-lineage", command -> {
            command.setTargetRef("app.summary.total");
            command.setThrough("job_a");
        });
        assertEquals(0, byThrough.exitCode(), byThrough.stderr());
        assertEquals(1, store.load(ALIAS).getLineage().size());

        String remaining = store.load(ALIAS).getLineage().keySet().iterator().next();
        Run byId = run(store, "remove-lineage", command -> command.setLineageId(remaining));
        assertEquals(0, byId.exitCode(), byId.stderr());
        assertTrue(store.load(ALIAS).getLineage().isEmpty());

        Run missing = run(store, "remove-lineage", command -> command.setLineageId(remaining));
        assertEquals(1, missing.exitCode());
    }

    @Test
    void approvingACandidateLineagePublishesIt() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = seedWorkspace();
        LineageRecord record = LineageRecord.create(ALIAS, columnId("app.summary.total"),
                List.of(columnId("app.orders.price")), null, "copy", GraphActor.agent);
        workspace.getLineage().put(record.getId(), record);
        store.save(workspace);
        assertEquals(GraphStatus.candidate, record.getStatus());

        com.sqlcli.graph.ui.service.WorkspaceMutationService service =
                new com.sqlcli.graph.ui.service.WorkspaceMutationService(store,
                        new WorkspaceValidator(), new com.sqlcli.graph.ui.service.WorkspaceLockManager());
        long revision = store.load(ALIAS).getManifest().getRevision();
        // 发布：默认置信度 0.8 → partial，不再是候选
        assertTrue(service.publishLineage(ALIAS, record.getId(), revision, "看过了").isSuccess());
        LineageRecord published = store.load(ALIAS).getLineage().get(record.getId());
        assertEquals(GraphStatus.partial, published.getStatus(), "候选批准后必须离开 candidate，否则状态没有意义");
        assertEquals(false, published.getVerified());

        // publish 类审批按对象种类分派到血缘
        LineageRecord another = LineageRecord.create(ALIAS, columnId("app.summary.total"),
                List.of(columnId("app.orders.qty")), null, "other", GraphActor.agent);
        GraphWorkspace again = store.load(ALIAS);
        again.getLineage().put(another.getId(), another);
        store.save(again);
        var payload = com.sqlcli.graph.ui.service.GraphChangePayload.publish(another.getId(), "agent", 0);
        assertTrue(service.decideGraphApproval(ALIAS, 1, payload, false, "不要").isSuccess());
        assertEquals(GraphStatus.ignored, store.load(ALIAS).getLineage().get(another.getId()).getStatus(),
                "拒绝转 ignored 留痕，不物理删");
    }

    // -------------------------------------------------------------------- 辅助

    private GraphWorkspace seedWorkspace() {
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create(ALIAS, "app", "orders", GraphActor.extractor);
        orders.getColumns().add(ColumnWorkspaceNode.create("price"));
        orders.getColumns().add(ColumnWorkspaceNode.create("qty"));
        workspace.getTables().put(orders.getId(), orders);
        TableWorkspaceNode summary = TableWorkspaceNode.create(ALIAS, "app", "summary", GraphActor.extractor);
        summary.getColumns().add(ColumnWorkspaceNode.create("total"));
        workspace.getTables().put(summary.getId(), summary);
        return workspace;
    }

    private static String columnId(String qualified) {
        int lastDot = qualified.lastIndexOf('.');
        String schemaTable = qualified.substring(0, lastDot);
        int dot = schemaTable.indexOf('.');
        return GraphIds.columnId(ALIAS, schemaTable.substring(0, dot), schemaTable.substring(dot + 1),
                qualified.substring(lastDot + 1));
    }

    private record Run(int exitCode, String stdout, String stderr) {
    }

    private Run run(GraphWorkspaceStore store, String action, Consumer<SchemaActionCommand> configure) {
        SchemaActionCommand command = new SchemaActionCommand(store);
        command.setAlias(ALIAS);
        command.setAction(action);
        command.setCommandArgs(List.of(action));
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
