package com.sqlcli.graph.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BI 语义层 metric 定义：稳定 id、存取往返、旧图谱兼容、检索命中、引用校验。
 */
class GraphMetricTest {

    private static final String ALIAS = "metric-test";

    @TempDir Path temp;

    // ---------------------------------------------------------------- 模型与稳定 id

    @Test
    void metricIdIsStableByNameSoRewriteIsIdempotent() {
        MetricRecord first = MetricRecord.create(ALIAS, "gmv_paid", GraphActor.agent);
        MetricRecord second = MetricRecord.create(ALIAS, "gmv_paid", GraphActor.agent);
        assertEquals(first.getId(), second.getId(), "同 alias 同 name 必须落在同一个对象上，重写才是幂等 upsert");

        MetricRecord other = MetricRecord.create(ALIAS, "gmv_refunded", GraphActor.agent);
        assertNotEquals(first.getId(), other.getId());
    }

    @Test
    void agentMetricIsCandidateWithAgentActor() {
        MetricRecord metric = MetricRecord.create(ALIAS, "gmv_paid", GraphActor.agent);
        assertEquals(GraphStatus.candidate, metric.getStatus(), "agent 写入的 metric 必须是候选，走人工评审发布");
        assertEquals(GraphActor.agent, metric.getCreatedBy());
        assertEquals(GraphObjectKind.metric, metric.getKind());
    }

    @Test
    void humanMetricStartsAsPartial() {
        MetricRecord metric = MetricRecord.create(ALIAS, "gmv_paid", GraphActor.human);
        assertEquals(GraphStatus.partial, metric.getStatus());
    }

    // ---------------------------------------------------------------- 存取往返

    @Test
    void storeRoundTripKeepsMetric() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = seedWorkspace();
        MetricRecord metric = buildGmvMetric(workspace);
        workspace.getMetrics().put(metric.getId(), metric);
        store.save(workspace);

        GraphWorkspace loaded = store.load(ALIAS);
        assertEquals(1, loaded.getMetrics().size());
        MetricRecord reloaded = loaded.getMetrics().get(metric.getId());
        assertEquals("SUM(order.amount)", reloaded.getExpression());
        assertEquals("status IN (2,3)", reloaded.getFilters());
        assertEquals(columnId("app", "orders", "created_at"), reloaded.getGrain().getTimeColumn());
        assertEquals(List.of("day", "week", "month"), reloaded.getGrain().getGrains());
        assertEquals(List.of(columnId("app", "orders", "channel")), reloaded.getDimensions());
        assertEquals(1, reloaded.getJoinPath().size());
        assertEquals(MetricRecord.MetricJoinType.inner, reloaded.getJoinPath().get(0).getJoinType());
        assertEquals(GraphStatus.candidate, reloaded.getStatus());
    }

    @Test
    void oldWorkspaceWithoutMetricsFileLoadsAsEmpty() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        store.save(seedWorkspace());
        // 模拟旧图谱：把当前代里的 metrics 文件删掉，读取不应该抛异常
        try (var stream = java.nio.file.Files.walk(temp)) {
            for (Path path : stream.filter(p -> p.getFileName().toString().equals("metrics.jsonl")).toList()) {
                java.nio.file.Files.delete(path);
            }
        }
        assertEquals(0, store.load(ALIAS).getMetrics().size());
    }

    @Test
    void snapshotExportImportKeepsMetric() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = seedWorkspace();
        MetricRecord metric = buildGmvMetric(workspace);
        workspace.getMetrics().put(metric.getId(), metric);

        Path snapshot = temp.resolve("export.json");
        store.exportSnapshot(workspace, snapshot);
        GraphWorkspace imported = store.importSnapshot(snapshot);
        assertEquals(1, imported.getMetrics().size());
        assertTrue(imported.getMetrics().containsKey(metric.getId()));
    }

    @Test
    void mergeKeepsExistingMetricOnSameName() {
        GraphWorkspace existing = seedWorkspace();
        MetricRecord existingMetric = MetricRecord.create(ALIAS, "gmv_paid", GraphActor.human);
        existingMetric.setBusinessName("已发布口径");
        existing.getMetrics().put(existingMetric.getId(), existingMetric);

        GraphWorkspace incoming = seedWorkspace();
        MetricRecord incomingMetric = MetricRecord.create(ALIAS, "gmv_paid", GraphActor.agent);
        incomingMetric.setBusinessName("导入快照里的另一个版本");
        incoming.getMetrics().put(incomingMetric.getId(), incomingMetric);

        GraphWorkspace merged = new GraphWorkspaceMerger().mergeBatch(incoming, existing, Set.of());
        assertEquals(1, merged.getMetrics().size());
        assertEquals("已发布口径", merged.getMetrics().get(existingMetric.getId()).getBusinessName(),
                "existing wins：人工维护的口径不能被导入快照静默覆盖");
    }

    // ---------------------------------------------------------------- 检索

    @Test
    void searchHitsMetricByName() {
        GraphWorkspace workspace = seedWorkspace();
        MetricRecord metric = buildGmvMetric(workspace);
        workspace.getMetrics().put(metric.getId(), metric);

        List<WorkspaceSearchEngine.SearchResult> hits = new WorkspaceSearchEngine(workspace).search("gmv_paid");
        assertTrue(hits.stream().anyMatch(hit -> hit.getId().equals(metric.getId()) && "metric".equals(hit.getType())));
    }

    @Test
    void searchHitsMetricByBusinessName() {
        GraphWorkspace workspace = seedWorkspace();
        MetricRecord metric = buildGmvMetric(workspace);
        metric.setBusinessName("已付款GMV");
        workspace.getMetrics().put(metric.getId(), metric);

        List<WorkspaceSearchEngine.SearchResult> hits = new WorkspaceSearchEngine(workspace).search("已付款GMV");
        assertTrue(hits.stream().anyMatch(hit -> hit.getId().equals(metric.getId())));
    }

    @Test
    void searchHitsMetricByAlias() {
        GraphWorkspace workspace = seedWorkspace();
        MetricRecord metric = buildGmvMetric(workspace);
        metric.setAliases(List.of("成交额", "交易额"));
        workspace.getMetrics().put(metric.getId(), metric);

        List<WorkspaceSearchEngine.SearchResult> hits = new WorkspaceSearchEngine(workspace).search("成交额");
        assertTrue(hits.stream().anyMatch(hit -> hit.getId().equals(metric.getId())));
    }

    // ---------------------------------------------------------------- 校验

    @Test
    void validMetricProducesNoDanglingIssues() {
        GraphWorkspace workspace = seedWorkspace();
        MetricRecord metric = buildGmvMetric(workspace);
        workspace.getMetrics().put(metric.getId(), metric);

        new WorkspaceValidator().validate(workspace);
        List<String> metricIssues = danglingMetricIssueCodes(workspace, metric.getId());
        assertTrue(metricIssues.isEmpty(), "有效的 metric 不应该产生 dangling_metric_* 问题: " + metricIssues);
    }

    @Test
    void validatorFlagsDanglingDimensionColumn() {
        GraphWorkspace workspace = seedWorkspace();
        MetricRecord metric = MetricRecord.create(ALIAS, "gmv_paid", GraphActor.agent);
        metric.setDimensions(List.of(columnId("app", "orders", "not_a_real_column")));
        workspace.getMetrics().put(metric.getId(), metric);

        new WorkspaceValidator().validate(workspace);
        assertTrue(danglingMetricIssueCodes(workspace, metric.getId()).contains("dangling_metric_dimension"));
    }

    @Test
    void validatorFlagsDanglingGrainColumn() {
        GraphWorkspace workspace = seedWorkspace();
        MetricRecord metric = MetricRecord.create(ALIAS, "gmv_paid", GraphActor.agent);
        MetricRecord.MetricGrain grain = new MetricRecord.MetricGrain();
        grain.setTimeColumn(columnId("app", "orders", "no_such_time_column"));
        metric.setGrain(grain);
        workspace.getMetrics().put(metric.getId(), metric);

        new WorkspaceValidator().validate(workspace);
        assertTrue(danglingMetricIssueCodes(workspace, metric.getId()).contains("dangling_metric_grain_column"));
    }

    @Test
    void validatorFlagsDanglingJoinRelation() {
        GraphWorkspace workspace = seedWorkspace();
        MetricRecord metric = MetricRecord.create(ALIAS, "gmv_paid", GraphActor.agent);
        MetricRecord.MetricJoinStep step = new MetricRecord.MetricJoinStep();
        step.setRelationId("relation:does-not-exist");
        metric.setJoinPath(List.of(step));
        workspace.getMetrics().put(metric.getId(), metric);

        new WorkspaceValidator().validate(workspace);
        assertTrue(danglingMetricIssueCodes(workspace, metric.getId()).contains("dangling_metric_join_relation"));
    }

    @Test
    void validatorAcceptsExistingJoinRelation() {
        GraphWorkspace workspace = seedWorkspace();
        RelationWorkspaceEdge relation = RelationWorkspaceEdge.create(ALIAS, RelationType.join_observed,
                columnId("app", "orders", "channel"), columnId("app", "orders", "amount"), GraphActor.agent);
        relation.setConfidence(0.9);
        workspace.getRelations().add(relation);

        MetricRecord metric = MetricRecord.create(ALIAS, "gmv_paid", GraphActor.agent);
        MetricRecord.MetricJoinStep step = new MetricRecord.MetricJoinStep();
        step.setRelationId(relation.getId());
        metric.setJoinPath(List.of(step));
        workspace.getMetrics().put(metric.getId(), metric);

        new WorkspaceValidator().validate(workspace);
        assertFalse(danglingMetricIssueCodes(workspace, metric.getId()).contains("dangling_metric_join_relation"));
    }

    // -------------------------------------------------------------------- 辅助

    private GraphWorkspace seedWorkspace() {
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create(ALIAS, "app", "orders", GraphActor.extractor);
        orders.getColumns().add(ColumnWorkspaceNode.create("amount"));
        orders.getColumns().add(ColumnWorkspaceNode.create("created_at"));
        orders.getColumns().add(ColumnWorkspaceNode.create("channel"));
        workspace.getTables().put(orders.getId(), orders);
        return workspace;
    }

    private MetricRecord buildGmvMetric(GraphWorkspace workspace) {
        MetricRecord metric = MetricRecord.create(ALIAS, "gmv_paid", GraphActor.agent);
        metric.setExpression("SUM(order.amount)");
        metric.setFilters("status IN (2,3)");
        MetricRecord.MetricGrain grain = new MetricRecord.MetricGrain();
        grain.setTimeColumn(columnId("app", "orders", "created_at"));
        grain.setGrains(List.of("day", "week", "month"));
        metric.setGrain(grain);
        metric.setDimensions(List.of(columnId("app", "orders", "channel")));

        RelationWorkspaceEdge relation = RelationWorkspaceEdge.create(ALIAS, RelationType.join_observed,
                columnId("app", "orders", "channel"), columnId("app", "orders", "amount"), GraphActor.agent);
        relation.setConfidence(0.9);
        workspace.getRelations().add(relation);
        MetricRecord.MetricJoinStep step = new MetricRecord.MetricJoinStep();
        step.setRelationId(relation.getId());
        step.setJoinType(MetricRecord.MetricJoinType.inner);
        metric.setJoinPath(List.of(step));
        return metric;
    }

    private List<String> danglingMetricIssueCodes(GraphWorkspace workspace, String metricId) {
        return workspace.getValidationIssues().stream()
                .filter(issue -> metricId.equals(issue.getTargetId()))
                .map(ValidationIssueRecord::getCode)
                .toList();
    }

    private static String columnId(String schema, String table, String column) {
        return GraphIds.columnId(ALIAS, schema, table, column);
    }
}
