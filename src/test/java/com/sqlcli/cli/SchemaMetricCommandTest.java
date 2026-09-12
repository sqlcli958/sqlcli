package com.sqlcli.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcli.graph.workspace.ColumnWorkspaceNode;
import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphIds;
import com.sqlcli.graph.workspace.GraphStatus;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sqlcli.graph.workspace.MetricRecord;
import com.sqlcli.graph.workspace.RelationType;
import com.sqlcli.graph.workspace.RelationWorkspaceEdge;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * schema add-metric / metrics / metric / expand-metric 四个 action 的 CLI 契约。
 * 用法跟 GraphLineageTest 的 CLI 部分一致：SchemaActionCommand 直接调用，不经过 SqlCli 解析层。
 */
class SchemaMetricCommandTest {

    private static final String ALIAS = "metric-cli-test";

    @TempDir Path temp;

    @Test
    void addMetricThenListAndShowViaCli() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        store.save(seedWorkspace());

        Run add = run(store, "add-metric", command -> {
            command.setMetricName("gmv_paid");
            command.setExpression("SUM(orders.amount)");
            command.setFilters("status IN (2,3)");
            command.setEditBusinessName("已支付GMV");
            command.setAliasesCsv("GMV,已支付金额");
            command.setGrainColumnRef("app.orders.created_at");
            command.setGrainsCsv("day,month");
            command.setDimensionsCsv("app.orders.channel");
        });
        assertEquals(0, add.exitCode(), add.stderr());

        GraphWorkspace saved = store.load(ALIAS);
        assertEquals(1, saved.getMetrics().size());
        MetricRecord metric = saved.getMetrics().get(GraphIds.metricId(ALIAS, "gmv_paid"));
        assertEquals(GraphStatus.candidate, metric.getStatus(), "agent 写入是候选");
        assertEquals(GraphActor.agent, metric.getCreatedBy());
        assertEquals("SUM(orders.amount)", metric.getExpression());
        assertEquals("status IN (2,3)", metric.getFilters());
        assertEquals("已支付GMV", metric.getBusinessName());
        assertTrue(metric.getAliases().contains("GMV"));
        assertEquals(columnId("app.orders.created_at"), metric.getGrain().getTimeColumn());
        assertEquals(java.util.List.of("day", "month"), metric.getGrain().getGrains());
        assertEquals(java.util.List.of(columnId("app.orders.channel")), metric.getDimensions());

        // 幂等：同名重写更新同一条记录，不产生第二条
        Run again = run(store, "add-metric", command -> {
            command.setMetricName("gmv_paid");
            command.setExpression("SUM(orders.amount)");
            command.setEditBusinessName("已支付GMV(更新)");
        });
        assertEquals(0, again.exitCode(), again.stderr());
        assertEquals(1, store.load(ALIAS).getMetrics().size());
        assertEquals("已支付GMV(更新)",
                store.load(ALIAS).getMetrics().get(GraphIds.metricId(ALIAS, "gmv_paid")).getBusinessName());

        Run list = run(store, "metrics", command -> command.setJsonOutput(true));
        assertEquals(0, list.exitCode(), list.stderr());
        JsonNode listData = new ObjectMapper().readTree(list.stdout()).get("data");
        assertEquals(1, listData.size());

        Run show = run(store, "metric", command -> {
            command.setMetricName("gmv_paid");
            command.setJsonOutput(true);
        });
        assertEquals(0, show.exitCode(), show.stderr());
        JsonNode showData = new ObjectMapper().readTree(show.stdout()).get("data");
        assertEquals("SUM(orders.amount)", showData.get("expression").asText());
        assertEquals("candidate", showData.get("status").asText());
    }

    @Test
    void addMetricRejectsUnknownDimensionColumn() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        store.save(seedWorkspace());

        Run add = run(store, "add-metric", command -> {
            command.setMetricName("broken");
            command.setExpression("COUNT(*)");
            command.setDimensionsCsv("app.orders.not_a_column");
        });
        assertEquals(1, add.exitCode());
        assertTrue(store.load(ALIAS).getMetrics().isEmpty(), "拒绝路径不得写入");
    }

    @Test
    void expandMetricProducesJoinAndGrainSql() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = seedWorkspace();
        String relationId = addCompositeForeignKey(workspace);
        store.save(workspace);

        Run add = run(store, "add-metric", command -> {
            command.setMetricName("gmv_paid");
            command.setExpression("SUM(orders.amount)");
            command.setFilters("status IN (2,3)");
            command.setGrainColumnRef("app.orders.created_at");
            command.setGrainsCsv("day,month");
            command.setDimensionsCsv("app.users.name");
            command.setJoinPathCsv(relationId + "|inner");
        });
        assertEquals(0, add.exitCode(), add.stderr());

        Run expand = run(store, "expand-metric", command -> {
            command.setMetricName("gmv_paid");
            command.setRequestedGrain("month");
            command.setDimensionsCsv("app.users.name");
            command.setJsonOutput(true);
        });
        assertEquals(0, expand.exitCode(), expand.stderr());
        JsonNode data = new ObjectMapper().readTree(expand.stdout()).get("data");
        String sql = data.get("sql").asText();
        assertTrue(sql.contains("INNER JOIN"), sql);
        assertTrue(sql.contains("SUM(orders.amount) AS `gmv_paid`"), sql);
        assertTrue(sql.contains("GROUP BY"), sql);
    }

    @Test
    void expandMetricReportsMissingRelation() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        store.save(seedWorkspace());

        // 直接往图谱写一条引用不存在关系的 metric（绕开 add-metric 的校验），
        // 验证 expand-metric 本身在展开阶段也会明确报错、不产出半截 SQL。
        GraphWorkspace workspace = store.load(ALIAS);
        MetricRecord metric = MetricRecord.create(ALIAS, "dangling", GraphActor.agent);
        metric.setExpression("COUNT(*)");
        MetricRecord.MetricGrain grain = new MetricRecord.MetricGrain();
        grain.setTimeColumn(columnId("app.orders.created_at"));
        metric.setGrain(grain);
        MetricRecord.MetricJoinStep step = new MetricRecord.MetricJoinStep();
        step.setRelationId("relation:" + ALIAS + ":foreign_key:does->not-exist");
        step.setJoinType(MetricRecord.MetricJoinType.inner);
        metric.setJoinPath(java.util.List.of(step));
        workspace.getMetrics().put(metric.getId(), metric);
        store.save(workspace);

        Run expand = run(store, "expand-metric", command -> command.setMetricName("dangling"));
        assertEquals(1, expand.exitCode());
        assertTrue(expand.stderr().contains("does->not-exist"), expand.stderr());
    }

    @Test
    void searchGroupsMetricsSeparatelyInsteadOfNullNull() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        store.save(seedWorkspace());

        Run add = run(store, "add-metric", command -> {
            command.setMetricName("gmv_paid_metric");
            command.setExpression("SUM(orders.amount)");
            command.setEditBusinessName("已支付GMV指标");
        });
        assertEquals(0, add.exitCode(), add.stderr());

        Run search = run(store, "search", command -> command.setKeyword("gmv_paid_metric"));
        assertEquals(0, search.exitCode(), search.stderr());
        assertTrue(search.stdout().contains("[METRIC]"), search.stdout());
        assertFalse(search.stdout().contains("null.null"), search.stdout());
    }

    // -------------------------------------------------------------------- 辅助

    private GraphWorkspace seedWorkspace() {
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create(ALIAS, "app", "orders", GraphActor.extractor);
        orders.getColumns().add(ColumnWorkspaceNode.create("id"));
        orders.getColumns().add(ColumnWorkspaceNode.create("amount"));
        orders.getColumns().add(ColumnWorkspaceNode.create("status"));
        orders.getColumns().add(ColumnWorkspaceNode.create("channel"));
        orders.getColumns().add(ColumnWorkspaceNode.create("created_at"));
        orders.getColumns().add(ColumnWorkspaceNode.create("buyer_id"));
        workspace.getTables().put(orders.getId(), orders);
        TableWorkspaceNode users = TableWorkspaceNode.create(ALIAS, "app", "users", GraphActor.extractor);
        users.getColumns().add(ColumnWorkspaceNode.create("id"));
        users.getColumns().add(ColumnWorkspaceNode.create("name"));
        workspace.getTables().put(users.getId(), users);
        return workspace;
    }

    /** 返回新增关系边的 id，供测试拼 --join-path。 */
    private String addCompositeForeignKey(GraphWorkspace workspace) {
        RelationWorkspaceEdge edge = RelationWorkspaceEdge.create(ALIAS, RelationType.foreign_key,
                columnId("app.orders.buyer_id"), columnId("app.users.id"), GraphActor.extractor);
        edge.setJoinExpression("app.orders.buyer_id = app.users.id");
        edge.setVerified(true);
        edge.setStatus(GraphStatus.verified);
        workspace.getRelations().add(edge);
        return edge.getId();
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
        command.setCommandArgs(java.util.List.of(action));
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
