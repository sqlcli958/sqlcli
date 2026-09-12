package com.sqlcli.metric;

import com.sqlcli.graph.workspace.ColumnWorkspaceNode;
import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphIds;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.MetricRecord;
import com.sqlcli.graph.workspace.RelationType;
import com.sqlcli.graph.workspace.RelationWorkspaceEdge;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import com.sqlcli.strategy.DatabaseStrategies;
import com.sqlcli.strategy.DatabaseStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * metric -> SQL 骨架展开。重点覆盖两条硬约束：expression/filters 不解析原样拼接、
 * 复合外键的 joinExpression 必须整条使用（任取分组里的哪条边都要拿到全部列对）。
 */
class MetricSqlExpanderTest {

    private static final String ALIAS = "metric-test";

    // ------------------------------------------------------------ 单表

    @Test
    void singleTableMetricExpandsWithoutJoin() {
        GraphWorkspace workspace = seedOrdersOnly();
        MetricRecord metric = MetricRecord.create(ALIAS, "order_count", GraphActor.agent);
        metric.setExpression("COUNT(*)");
        metric.setFilters("status IN (2,3)");
        // 起始表由 grain.timeColumn 定位（模型没有独立的"主表"字段，见类头注释）；
        // 这里不请求按粒度分桶（request.grain()==null），只用它来确定 FROM 哪张表。
        MetricRecord.MetricGrain grain = new MetricRecord.MetricGrain();
        grain.setTimeColumn(columnId("app.orders.created_at"));
        metric.setGrain(grain);

        String sql = MetricSqlExpander.expand(workspace, metric, mysql(), request(null, null, null));

        assertTrue(sql.contains("FROM `app`.`orders`"), sql);
        assertFalse(sql.contains("JOIN"), sql);
        assertFalse(sql.contains("GROUP BY"), sql);
        assertTrue(sql.contains("COUNT(*) AS `order_count`"), sql);
        assertTrue(sql.contains("WHERE (status IN (2,3))"), sql);
    }

    // ------------------------------------------------------------ 跨表 + 复合外键

    @Test
    void compositeForeignKeyJoinCarriesAllColumnPairsRegardlessOfWhichGroupEdgeIsReferenced() {
        GraphWorkspace workspace = seedOrdersAndUsersWithCompositeFk();
        String combinedJoin = "app.orders.tenant_id = app.users.tenant_id AND app.orders.buyer_id = app.users.id";
        // metric 故意引用组内第二条边（buyer_id -> id），验证展开用的是共享的完整 joinExpression，
        // 而不是只拿这条边自己的两个端点重新拼一个等式（那样会漏掉 tenant_id，产出跨租户笛卡尔积）。
        String secondEdgeId = GraphIds.relationId(ALIAS, RelationType.foreign_key,
                columnId("app.orders.buyer_id"), columnId("app.users.id"));

        MetricRecord metric = MetricRecord.create(ALIAS, "gmv_paid", GraphActor.agent);
        metric.setExpression("SUM(orders.amount)");
        metric.setFilters("status IN (2,3)");
        metric.setDimensions(List.of(columnId("app.users.name")));
        metric.setJoinPath(List.of(joinStep(secondEdgeId, MetricRecord.MetricJoinType.inner)));
        MetricRecord.MetricGrain grain = new MetricRecord.MetricGrain();
        grain.setTimeColumn(columnId("app.orders.created_at"));
        grain.setGrains(List.of("day", "month"));
        metric.setGrain(grain);

        String sql = MetricSqlExpander.expand(workspace, metric, mysql(),
                request("month", "2026-01-01", "2026-02-01", columnId("app.users.name")));

        assertTrue(sql.contains("FROM `app`.`orders`"), sql);
        assertTrue(sql.contains("INNER JOIN `app`.`users` ON " + combinedJoin), sql);
        assertTrue(sql.contains("tenant_id"), "复合外键的两列都要出现在 ON 条件里: " + sql);
        assertTrue(sql.contains("SUM(orders.amount) AS `gmv_paid`"), sql);
        assertTrue(sql.contains("WHERE (status IN (2,3))"), sql);
        assertTrue(sql.contains(">= '2026-01-01'"), sql);
        assertTrue(sql.contains("< '2026-02-01'"), sql);
        assertTrue(sql.contains("GROUP BY"), sql);
    }

    @Test
    void leftJoinDeclarationProducesLeftJoinKeyword() {
        GraphWorkspace workspace = seedOrdersAndUsersWithCompositeFk();
        String firstEdgeId = GraphIds.relationId(ALIAS, RelationType.foreign_key,
                columnId("app.orders.tenant_id"), columnId("app.users.tenant_id"));

        MetricRecord metric = MetricRecord.create(ALIAS, "orders_with_optional_user", GraphActor.agent);
        metric.setExpression("COUNT(*)");
        metric.setJoinPath(List.of(joinStep(firstEdgeId, MetricRecord.MetricJoinType.left)));
        MetricRecord.MetricGrain grain = new MetricRecord.MetricGrain();
        grain.setTimeColumn(columnId("app.orders.created_at"));
        grain.setGrains(List.of("day"));
        metric.setGrain(grain);

        String sql = MetricSqlExpander.expand(workspace, metric, mysql(), request(null, null, null));

        assertTrue(sql.contains("LEFT JOIN `app`.`users`"), sql);
        assertFalse(sql.contains("INNER JOIN"), sql);
    }

    // ------------------------------------------------------------ 粒度 / 方言差异

    @Test
    void grainSelectDifferBySqlDialect() {
        GraphWorkspace workspace = seedOrdersOnly();
        MetricRecord metric = metricWithGrainAndExpression();

        String mysqlSql = MetricSqlExpander.expand(workspace, metric, mysql(), request("month", null, null));
        String postgresSql = MetricSqlExpander.expand(workspace, metric, DatabaseStrategies.resolve("postgresql"),
                request("month", null, null));
        String oracleSql = MetricSqlExpander.expand(workspace, metric, DatabaseStrategies.resolve("oracle"),
                request("month", null, null));

        assertTrue(mysqlSql.contains("DATE_FORMAT("), mysqlSql);
        assertTrue(postgresSql.contains("DATE_TRUNC('month'"), postgresSql);
        assertTrue(oracleSql.contains("TRUNC("), oracleSql);
        assertFalse(mysqlSql.equals(postgresSql));
    }

    @Test
    void grainDeclaredByMetricButUnsupportedByDialectThrowsClearError() {
        GraphWorkspace workspace = seedOrdersOnly();
        MetricRecord metric = MetricRecord.create(ALIAS, "gmv", GraphActor.agent);
        metric.setExpression("SUM(orders.amount)");
        MetricRecord.MetricGrain grain = new MetricRecord.MetricGrain();
        grain.setTimeColumn(columnId("app.orders.created_at"));
        grain.setGrains(List.of("fortnight")); // metric 自己声明了这个粒度，但没有方言支持它
        metric.setGrain(grain);

        MetricExpansionException ex = assertThrows(MetricExpansionException.class,
                () -> MetricSqlExpander.expand(workspace, metric, mysql(), request("fortnight", null, null)));
        assertTrue(ex.getMessage().contains("fortnight"), ex.getMessage());
        assertTrue(ex.getMessage().contains("mysql"), ex.getMessage());
    }

    @Test
    void requestingGrainWhenMetricDeclaresNoneIsRejected() {
        GraphWorkspace workspace = seedOrdersOnly();
        MetricRecord metric = MetricRecord.create(ALIAS, "order_count", GraphActor.agent);
        metric.setExpression("COUNT(*)");
        metric.setDimensions(List.of(columnId("app.orders.status")));

        MetricExpansionException ex = assertThrows(MetricExpansionException.class,
                () -> MetricSqlExpander.expand(workspace, metric, mysql(), request("day", null, null)));
        assertTrue(ex.getMessage().contains("grain.timeColumn"), ex.getMessage());
    }

    // ------------------------------------------------------------ 报错要点名缺什么

    @Test
    void missingDimensionColumnReportsWhichIdIsMissing() {
        GraphWorkspace workspace = seedOrdersOnly();
        MetricRecord metric = MetricRecord.create(ALIAS, "order_count", GraphActor.agent);
        metric.setExpression("COUNT(*)");
        metric.setDimensions(List.of(columnId("app.orders.does_not_exist")));

        MetricExpansionException ex = assertThrows(MetricExpansionException.class,
                () -> MetricSqlExpander.expand(workspace, metric, mysql(),
                        request(null, null, null, columnId("app.orders.does_not_exist"))));
        assertTrue(ex.getMessage().contains("app.orders.does_not_exist"), ex.getMessage());
    }

    @Test
    void missingRelationInJoinPathReportsWhichRelationIdIsMissing() {
        GraphWorkspace workspace = seedOrdersAndUsersWithCompositeFk();
        MetricRecord metric = MetricRecord.create(ALIAS, "broken_metric", GraphActor.agent);
        metric.setExpression("COUNT(*)");
        metric.setJoinPath(List.of(joinStep("relation:metric-test:foreign_key:does->not-exist",
                MetricRecord.MetricJoinType.inner)));
        MetricRecord.MetricGrain grain = new MetricRecord.MetricGrain();
        grain.setTimeColumn(columnId("app.orders.created_at"));
        metric.setGrain(grain);

        MetricExpansionException ex = assertThrows(MetricExpansionException.class,
                () -> MetricSqlExpander.expand(workspace, metric, mysql(), request(null, null, null)));
        assertTrue(ex.getMessage().contains("relation:metric-test:foreign_key:does->not-exist"), ex.getMessage());
    }

    @Test
    void metricWithoutGrainDimensionsOrJoinPathCannotResolveBaseTable() {
        GraphWorkspace workspace = seedOrdersOnly();
        MetricRecord metric = MetricRecord.create(ALIAS, "no_anchor", GraphActor.agent);
        metric.setExpression("COUNT(*)");

        MetricExpansionException ex = assertThrows(MetricExpansionException.class,
                () -> MetricSqlExpander.expand(workspace, metric, mysql(), request(null, null, null)));
        assertTrue(ex.getMessage().contains("起始表"), ex.getMessage());
    }

    @Test
    void requestingGrainNotDeclaredByMetricIsRejected() {
        GraphWorkspace workspace = seedOrdersOnly();
        MetricRecord metric = metricWithGrainAndExpression(); // 只声明了 day/month
        MetricExpansionException ex = assertThrows(MetricExpansionException.class,
                () -> MetricSqlExpander.expand(workspace, metric, mysql(), request("year", null, null)));
        assertTrue(ex.getMessage().contains("year"), ex.getMessage());
    }

    // ------------------------------------------------------------ F4b 比率指标 / F4a 可加性

    @Test
    void ratioMetricExpandsNumeratorAndDenominatorWithTheirOwnFilters() {
        GraphWorkspace workspace = seedOrdersOnly();
        MetricRecord metric = MetricRecord.create(ALIAS, "repeat_purchase_rate", GraphActor.agent);
        MetricRecord.MetricComponent numerator = new MetricRecord.MetricComponent();
        numerator.setExpression("COUNT(DISTINCT orders.id)");
        numerator.setFilters("status = 2"); // 只卡分子——"复购"这一侧的条件
        metric.setNumerator(numerator);
        MetricRecord.MetricComponent denominator = new MetricRecord.MetricComponent();
        denominator.setExpression("COUNT(DISTINCT orders.id)"); // 分母不设 filters：全量口径
        metric.setDenominator(denominator);
        MetricRecord.MetricGrain grain = new MetricRecord.MetricGrain();
        grain.setTimeColumn(columnId("app.orders.created_at"));
        grain.setGrains(List.of("day"));
        metric.setGrain(grain);

        String sql = MetricSqlExpander.expand(workspace, metric, mysql(), request("day", null, null));

        assertTrue(sql.contains("numerator_value"), sql);
        assertTrue(sql.contains("denominator_value"), sql);
        assertTrue(sql.contains("NULLIF(d.denominator_value, 0)"), sql);
        assertTrue(sql.contains("AS `repeat_purchase_rate`"), sql);
        // 分子分母各自独立聚合：只有分子的过滤条件出现，且只出现一次——糅进同一个 WHERE
        // 会让分母也被 status = 2 卡住，两个数就都错了。
        assertEquals(1, sql.lines().filter(line -> line.contains("WHERE (status = 2)")).count(), sql);
        assertEquals(1, (int) sql.lines().filter(line -> line.trim().startsWith("WHERE")).count(), sql);
        // 比率结构不用人填 additivity，展开器自己推定成 non_additive。
        assertEquals(MetricRecord.Additivity.non_additive, metric.effectiveAdditivity());
    }

    @Test
    void ratioMetricStaysSafeEvenWithoutGrainOrDimensions() {
        GraphWorkspace workspace = seedOrdersOnly();
        MetricRecord metric = MetricRecord.create(ALIAS, "repeat_purchase_rate", GraphActor.agent);
        MetricRecord.MetricComponent numerator = new MetricRecord.MetricComponent();
        numerator.setExpression("COUNT(DISTINCT orders.id)");
        metric.setNumerator(numerator);
        MetricRecord.MetricComponent denominator = new MetricRecord.MetricComponent();
        denominator.setExpression("COUNT(DISTINCT orders.id)");
        metric.setDenominator(denominator);
        MetricRecord.MetricGrain grain = new MetricRecord.MetricGrain();
        grain.setTimeColumn(columnId("app.orders.created_at"));
        metric.setGrain(grain);

        // 不请求 grain/dimensions（只查总量）：CROSS JOIN 分支，不应该报错。
        String sql = MetricSqlExpander.expand(workspace, metric, mysql(), request(null, null, null));
        assertTrue(sql.contains("CROSS JOIN"), sql);
    }

    @Test
    void nonAdditivePlainExpressionMetricRejectsGroupedExpansion() {
        GraphWorkspace workspace = seedOrdersOnly();
        MetricRecord metric = MetricRecord.create(ALIAS, "avg_order_value", GraphActor.agent);
        metric.setExpression("AVG(orders.amount)");
        metric.setAdditivity(MetricRecord.Additivity.non_additive); // 人手标的，不是比率结构
        metric.setDimensions(List.of(columnId("app.orders.status")));

        MetricExpansionException ex = assertThrows(MetricExpansionException.class,
                () -> MetricSqlExpander.expand(workspace, metric, mysql(),
                        request(null, null, null, columnId("app.orders.status"))));
        assertTrue(ex.getMessage().contains("non_additive"), ex.getMessage());
        assertTrue(ex.getMessage().contains("numerator/denominator"), ex.getMessage());
    }

    @Test
    void semiAdditiveMetricRejectsGrainRequestButAllowsDimensionsOnly() {
        GraphWorkspace workspace = seedOrdersOnly();
        MetricRecord grouped = metricWithGrainAndExpression(); // SUM(orders.amount)，声明了 day/month
        grouped.setAdditivity(MetricRecord.Additivity.semi_additive); // 跨时间不可加的快照，如库存余额

        MetricExpansionException ex = assertThrows(MetricExpansionException.class,
                () -> MetricSqlExpander.expand(workspace, grouped, mysql(), request("month", null, null)));
        assertTrue(ex.getMessage().contains("semi_additive"), ex.getMessage());

        // 跨维度可加是允许的，只有跨时间不行——不请求 --grain 就不该被拦。
        MetricRecord byDimensionOnly = MetricRecord.create(ALIAS, "balance", GraphActor.agent);
        byDimensionOnly.setExpression("SUM(orders.amount)");
        byDimensionOnly.setAdditivity(MetricRecord.Additivity.semi_additive);
        byDimensionOnly.setDimensions(List.of(columnId("app.orders.status")));
        String sql = MetricSqlExpander.expand(workspace, byDimensionOnly, mysql(),
                request(null, null, null, columnId("app.orders.status")));
        assertTrue(sql.contains("GROUP BY"), sql);
    }

    // ------------------------------------------------------------ 辅助

    private static DatabaseStrategy mysql() {
        return DatabaseStrategies.resolve("mysql");
    }

    private static MetricSqlRequest request(String grain, String from, String to) {
        return new MetricSqlRequest(grain, from, to, List.of());
    }

    private static MetricSqlRequest request(String grain, String from, String to, String dimensionColumnId) {
        return new MetricSqlRequest(grain, from, to, List.of(dimensionColumnId));
    }

    private MetricRecord metricWithGrainAndExpression() {
        MetricRecord metric = MetricRecord.create(ALIAS, "gmv", GraphActor.agent);
        metric.setExpression("SUM(orders.amount)");
        MetricRecord.MetricGrain grain = new MetricRecord.MetricGrain();
        grain.setTimeColumn(columnId("app.orders.created_at"));
        grain.setGrains(List.of("day", "month"));
        metric.setGrain(grain);
        return metric;
    }

    private MetricRecord.MetricJoinStep joinStep(String relationId, MetricRecord.MetricJoinType type) {
        MetricRecord.MetricJoinStep step = new MetricRecord.MetricJoinStep();
        step.setRelationId(relationId);
        step.setJoinType(type);
        return step;
    }

    private GraphWorkspace seedOrdersOnly() {
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create(ALIAS, "app", "orders", GraphActor.extractor);
        orders.getColumns().add(ColumnWorkspaceNode.create("id"));
        orders.getColumns().add(ColumnWorkspaceNode.create("amount"));
        orders.getColumns().add(ColumnWorkspaceNode.create("status"));
        orders.getColumns().add(ColumnWorkspaceNode.create("created_at"));
        workspace.getTables().put(orders.getId(), orders);
        return workspace;
    }

    private GraphWorkspace seedOrdersAndUsersWithCompositeFk() {
        GraphWorkspace workspace = seedOrdersOnly();
        TableWorkspaceNode orders = workspace.getTables().values().iterator().next();
        orders.getColumns().add(ColumnWorkspaceNode.create("tenant_id"));
        orders.getColumns().add(ColumnWorkspaceNode.create("buyer_id"));

        TableWorkspaceNode users = TableWorkspaceNode.create(ALIAS, "app", "users", GraphActor.extractor);
        users.getColumns().add(ColumnWorkspaceNode.create("id"));
        users.getColumns().add(ColumnWorkspaceNode.create("tenant_id"));
        users.getColumns().add(ColumnWorkspaceNode.create("name"));
        workspace.getTables().put(users.getId(), users);

        String combinedJoin = "app.orders.tenant_id = app.users.tenant_id AND app.orders.buyer_id = app.users.id";
        RelationWorkspaceEdge tenantEdge = RelationWorkspaceEdge.create(ALIAS, RelationType.foreign_key,
                columnId("app.orders.tenant_id"), columnId("app.users.tenant_id"), GraphActor.extractor);
        tenantEdge.setJoinExpression(combinedJoin);
        RelationWorkspaceEdge buyerEdge = RelationWorkspaceEdge.create(ALIAS, RelationType.foreign_key,
                columnId("app.orders.buyer_id"), columnId("app.users.id"), GraphActor.extractor);
        buyerEdge.setJoinExpression(combinedJoin);
        workspace.getRelations().add(tenantEdge);
        workspace.getRelations().add(buyerEdge);
        return workspace;
    }

    private static String columnId(String qualified) {
        int lastDot = qualified.lastIndexOf('.');
        String schemaTable = qualified.substring(0, lastDot);
        int dot = schemaTable.indexOf('.');
        return GraphIds.columnId(ALIAS, schemaTable.substring(0, dot), schemaTable.substring(dot + 1),
                qualified.substring(lastDot + 1));
    }
}
