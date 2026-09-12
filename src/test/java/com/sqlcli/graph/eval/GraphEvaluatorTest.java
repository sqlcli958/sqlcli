package com.sqlcli.graph.eval;

import com.sqlcli.graph.workspace.ColumnWorkspaceNode;
import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphIds;
import com.sqlcli.graph.workspace.GraphStatus;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.LineageRecord;
import com.sqlcli.graph.workspace.RelationType;
import com.sqlcli.graph.workspace.RelationWorkspaceEdge;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import com.sqlcli.graph.workspace.TermWorkspaceNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphEvaluatorTest {

    private static final String ALIAS = "eval-test";

    @Test
    void lineageShapeIsAWarningWithARemoveCommand() {
        GraphWorkspace workspace = workspace();
        TableWorkspaceNode orders = workspace.getTables().values().iterator().next();
        String col = orders.getColumns().get(0).computeId(ALIAS, orders.getSchema(), orders.getName());
        // 自指：任何分类里列都不是自己的上游
        LineageRecord selfRef = LineageRecord.create(ALIAS, col, List.of(col), null, "submit", GraphActor.agent);
        workspace.getLineage().put(selfRef.getId(), selfRef);
        // 端点列不存在
        LineageRecord dangling = LineageRecord.create(ALIAS, col,
                List.of("column:" + ALIAS + ":app.gone.x"), null, "copy", GraphActor.agent);
        workspace.getLineage().put(dangling.getId(), dangling);

        GraphEvaluation evaluation = new GraphEvaluator().evaluate(workspace);
        List<GraphFinding> shape = probe(evaluation, "lineage.shape");
        assertEquals(2, shape.size());
        assertTrue(shape.stream().allMatch(f -> f.severity() == GraphFinding.Severity.warning), "形状错不判评估失败");
        assertTrue(shape.stream().allMatch(f -> f.remediation().contains("schema remove-lineage --id lineage:")),
                "每条都带可粘贴的删除命令");
        assertFalse(evaluation.failed(), "只有 warning 时评估不失败");
    }

    @Test
    void termWithoutDescriptionMappingOrPrimaryTargetIsAHardError() {
        GraphWorkspace workspace = workspace();
        workspace.getTerms().put("term:" + ALIAS + ":工单", term("工单"));

        GraphEvaluation evaluation = new GraphEvaluator().evaluate(workspace);

        List<GraphFinding> orphans = probe(evaluation, "term.orphan");
        assertEquals(1, orphans.size());
        assertEquals(GraphFinding.Severity.error, orphans.get(0).severity());
        assertTrue(evaluation.failed(), "硬错误 > 0 时评估失败");
        assertTrue(orphans.get(0).remediation().startsWith("sql-cli " + ALIAS + " schema add-term 工单"),
                "finding 必须自带可粘贴执行的命令，实际：" + orphans.get(0).remediation());
    }

    @Test
    void termIsNotOrphanWhenItHasDescriptionMappingOrPrimaryTarget() {
        GraphWorkspace workspace = workspace();
        TermWorkspaceNode described = term("描述");
        described.setDescription("有描述");
        TermWorkspaceNode targeted = term("入口");
        targeted.setPrimaryTarget(GraphIds.tableId(ALIAS, "app", "orders"));
        TermWorkspaceNode mapped = term("映射");
        workspace.getTerms().put(described.getId(), described);
        workspace.getTerms().put(targeted.getId(), targeted);
        workspace.getTerms().put(mapped.getId(), mapped);
        workspace.getRelations().add(RelationWorkspaceEdge.create(ALIAS, RelationType.term_mapping,
                mapped.getId(), GraphIds.tableId(ALIAS, "app", "orders"), GraphActor.agent));

        assertTrue(probe(new GraphEvaluator().evaluate(workspace), "term.orphan").isEmpty());
    }

    @Test
    void rejectedMappingDoesNotRescueAnOrphanTerm() {
        GraphWorkspace workspace = workspace();
        TermWorkspaceNode mapped = term("被拒");
        workspace.getTerms().put(mapped.getId(), mapped);
        RelationWorkspaceEdge edge = RelationWorkspaceEdge.create(ALIAS, RelationType.term_mapping,
                mapped.getId(), GraphIds.tableId(ALIAS, "app", "orders"), GraphActor.agent);
        edge.setStatus(GraphStatus.ignored);
        workspace.getRelations().add(edge);

        assertEquals(1, probe(new GraphEvaluator().evaluate(workspace), "term.orphan").size(),
                "人已经否掉的映射不能算这条术语有落点");
    }

    @Test
    void descriptionMentioningAMissingTableIsAHardError() {
        GraphWorkspace workspace = erpWorkspace();
        described(workspace, "描述里写的是 erp_prop_report_assgin（拼错的表名）");

        List<GraphFinding> dead = probe(new GraphEvaluator().evaluate(workspace), "desc.dead-ref");

        assertEquals(1, dead.size(), "拼错的表名必须报出来，实际：" + dead);
        assertTrue(dead.get(0).message().contains("erp_prop_report_assgin"));
        assertTrue(dead.get(0).remediation().contains("schema edit --table erp.erp_prop_report"));
    }

    /**
     * 真图谱（erp_plush_test）上跑出来的 5 条误报，一条都不该报。
     *
     * <p>判据从「带下划线且图谱里查不到」收紧成「长得像本库里的表名」之前，
     * 描述里正常会提到的字典类型名、Mapper 文件名、SQL 函数全被当成死引用——
     * 5 条 finding，0 条真的。
     */
    @Test
    void dictionaryNamesFileNamesAndSqlFunctionsAreNotDeadReferences() {
        for (String token : List.of("report_state", "ErpPropReportMapper.xml",
                "find_in_set", "find_in_set(#{id}, path)", "字段用 find_in_set 匹配")) {
            GraphWorkspace workspace = erpWorkspace();
            described(workspace, "状态取值见 " + token);

            assertTrue(probe(new GraphEvaluator().evaluate(workspace), "desc.dead-ref").isEmpty(),
                    token + " 不是表引用，不该报");
        }
    }

    @Test
    void knownTableAndColumnNamesInDescriptionsAreNotDeadReferences() {
        GraphWorkspace workspace = erpWorkspace();
        described(workspace, "按 erp_prop_task 关联，主键 erp.erp_prop_report.id");

        assertTrue(probe(new GraphEvaluator().evaluate(workspace), "desc.dead-ref").isEmpty());
    }

    @Test
    void joinBetweenIncompatibleTypesIsAHardErrorAndLengthDifferenceIsNot() {
        GraphWorkspace workspace = workspace();
        TableWorkspaceNode orders = workspace.getTables().get(GraphIds.tableId(ALIAS, "app", "orders"));
        TableWorkspaceNode customers = TableWorkspaceNode.create(ALIAS, "app", "customers", GraphActor.extractor);
        column(customers, "id", "bigint", null);
        column(customers, "code", "varchar", 64);
        workspace.getTables().put(customers.getId(), customers);
        // varchar ↔ bigint：无论如何都是错
        workspace.getRelations().add(join(orders, "buyer_code", customers, "id"));
        // varchar(32) ↔ varchar(64)：能跑，是设计建议，归 policy 管
        workspace.getRelations().add(join(orders, "buyer_code", customers, "code"));

        List<GraphFinding> mismatch = probe(new GraphEvaluator().evaluate(workspace), "rel.endpoint-mismatch");

        assertEquals(1, mismatch.size(), "长度不同不该进硬错误，实际：" + mismatch);
        assertTrue(mismatch.get(0).message().contains("varchar"));
        assertTrue(mismatch.get(0).remediation().contains("schema add-relation"));
    }

    @Test
    void intJoinedToBigintIsCompatible() {
        GraphWorkspace workspace = workspace();
        TableWorkspaceNode orders = workspace.getTables().get(GraphIds.tableId(ALIAS, "app", "orders"));
        TableWorkspaceNode customers = TableWorkspaceNode.create(ALIAS, "app", "customers", GraphActor.extractor);
        column(customers, "id", "bigint", null);
        workspace.getTables().put(customers.getId(), customers);
        workspace.getRelations().add(join(orders, "buyer_id", customers, "id"));

        assertTrue(probe(new GraphEvaluator().evaluate(workspace), "rel.endpoint-mismatch").isEmpty());
    }

    /**
     * 数量闸门：51 条同类发现收敛成 1 条。判据来自 policy 那 1215 条命名违规——
     * 一类发现多到人处理不完，它报的不是「图谱错了」而是「规则用错了对象」。
     */
    @Test
    void oneProbeOverTheLimitCollapsesIntoASingleRuleMisappliedFinding() {
        GraphWorkspace workspace = workspace();
        for (int i = 0; i <= GraphEvaluator.FINDING_LIMIT; i++) {
            TermWorkspaceNode term = term("空壳" + i);
            workspace.getTerms().put(term.getId(), term);
        }

        GraphEvaluation evaluation = new GraphEvaluator().evaluate(workspace);

        List<GraphFinding> orphans = probe(evaluation, "term.orphan");
        assertEquals(1, orphans.size(), "超过闸门就停止列举");
        assertTrue(orphans.get(0).message().contains("51 条"));
        assertTrue(orphans.get(0).message().contains("用错了对象"));
        assertTrue(orphans.get(0).remediation().startsWith("sql-cli "),
                "闸门 finding 也得带一条能跑的命令，否则同样没人动");
    }

    @Test
    void exactlyAtTheLimitStillListsEveryFinding() {
        GraphWorkspace workspace = workspace();
        for (int i = 0; i < GraphEvaluator.FINDING_LIMIT; i++) {
            TermWorkspaceNode term = term("空壳" + i);
            workspace.getTerms().put(term.getId(), term);
        }

        assertEquals(GraphEvaluator.FINDING_LIMIT,
                probe(new GraphEvaluator().evaluate(workspace), "term.orphan").size());
    }

    /**
     * 描述覆盖率<b>只数 description</b>：库注释是导入的镜像，不是整理的痕迹。
     * 算进 comment 就是虚荣指标——真图谱上算进去是「表 62.7% / 列 85.9%」，
     * 只数 description 是「表 2.8% / 列 0.4%」，后者才是「有人整理过多少」的答案。
     */
    @Test
    void descriptionCoverageCountsDescriptionOnlyAndCommentIsReportedSeparately() {
        GraphWorkspace workspace = workspace();
        TableWorkspaceNode orders = workspace.getTables().values().iterator().next();
        orders.setComment("库注释：订单表");   // 表描述覆盖仍然是 0
        orders.getColumns().get(0).setDescription("买家 id");
        orders.getColumns().get(1).setComment("库注释：买家编码");
        TermWorkspaceNode term = term("工单");
        term.setDescription("有描述但没有映射");
        workspace.getTerms().put(term.getId(), term);

        GraphEvaluation evaluation = new GraphEvaluator().evaluate(workspace);

        assertEquals(0.0, evaluation.metrics().get("tableDescription"), 0.0001,
                "只有库注释不算整理过");
        assertEquals(0.5, evaluation.metrics().get("columnDescription"), 0.0001);
        assertEquals(0.5, evaluation.metrics().get("columnComment"), 0.0001,
                "comment 有用，但必须和 description 分开报");
        assertEquals(0.0, evaluation.metrics().get("columnValueDomain"), 0.0001);
        assertEquals(0.0, evaluation.metrics().get("termMapping"), 0.0001);
        assertFalse(evaluation.failed(), "覆盖率再低也不判失败——它没有普适阈值");
    }

    // ---------------------------------------------------------------- 固件

    /**
     * 三张 {@code erp_} 前缀的表——凑够 3 张，{@code erp_} 才算这个库的表名前缀
     * （前缀闸门的阈值），dead-ref 才有判据可用。
     */
    private GraphWorkspace erpWorkspace() {
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");
        for (String name : List.of("erp_prop_report", "erp_prop_task", "erp_prop_plan")) {
            TableWorkspaceNode table = TableWorkspaceNode.create(ALIAS, "erp", name, GraphActor.extractor);
            column(table, "id", "bigint", null);
            workspace.getTables().put(table.getId(), table);
        }
        return workspace;
    }

    /** 把描述写在 erp_prop_report 上。 */
    private void described(GraphWorkspace workspace, String description) {
        workspace.getTables().get(GraphIds.tableId(ALIAS, "erp", "erp_prop_report"))
                .setDescription(description);
    }

    /** 一张 app.orders，两个字段：buyer_id bigint、buyer_code varchar(32)。 */
    private GraphWorkspace workspace() {
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create(ALIAS, "app", "orders", GraphActor.extractor);
        column(orders, "buyer_id", "bigint", null);
        column(orders, "buyer_code", "varchar", 32);
        workspace.getTables().put(orders.getId(), orders);
        return workspace;
    }

    private TermWorkspaceNode term(String name) {
        return TermWorkspaceNode.create(ALIAS, name, GraphActor.agent);
    }

    private void column(TableWorkspaceNode table, String name, String type, Integer length) {
        ColumnWorkspaceNode column = ColumnWorkspaceNode.create(name);
        column.getDataType().setNormalized(type);
        column.getDataType().setLength(length);
        table.getColumns().add(column);
    }

    private RelationWorkspaceEdge join(TableWorkspaceNode from, String fromColumn,
            TableWorkspaceNode to, String toColumn) {
        RelationWorkspaceEdge edge = RelationWorkspaceEdge.create(ALIAS, RelationType.join_observed,
                GraphIds.columnId(ALIAS, from.getSchema(), from.getName(), fromColumn),
                GraphIds.columnId(ALIAS, to.getSchema(), to.getName(), toColumn),
                GraphActor.agent);
        edge.setConfidence(0.8);
        return edge;
    }

    private List<GraphFinding> probe(GraphEvaluation evaluation, String probe) {
        return evaluation.findings().stream().filter(finding -> finding.probe().equals(probe)).toList();
    }
}
