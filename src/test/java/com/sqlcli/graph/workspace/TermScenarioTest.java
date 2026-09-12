package com.sqlcli.graph.workspace;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 术语展开成场景子图。
 *
 * <p>钉住的是四件事：入口指向什么决定是不是场景、只用集合内部的边、连不上才补桥接并标出来、
 * 补桥也连不上时如实报告而不是假装连上了。
 */
class TermScenarioTest {

    private static final String ALIAS = "demo";

    @Test
    void withoutAPrimaryTableItIsNotAScenario() {
        GraphWorkspace workspace = seed();
        TermWorkspaceNode term = term(workspace, "报事");

        // 没填入口
        assertNull(TermScenario.of(workspace, term), "没有入口表却当成了场景");

        // 入口指向的是一个列——退化成同义词路由，同样不是场景
        term.setPrimaryTarget(GraphIds.columnId(ALIAS, "app", "report", "state"));
        assertNull(TermScenario.of(workspace, term), "入口指向列却当成了场景");
    }

    @Test
    void insideEdgesOnlyNoExtraHop() {
        GraphWorkspace workspace = seed();
        // report ─ assign 直连；dict 与 report 也直连，但术语没有映射它
        relation(workspace, "report", "type_id", "dict", "id");
        relation(workspace, "report", "id", "assign", "report_id");
        TermWorkspaceNode term = term(workspace, "报事");
        term.setPrimaryTarget(tableId("report"));
        map(workspace, term, "assign");

        TermScenario scenario = TermScenario.of(workspace, term);
        assertNotNull(scenario);
        assertEquals(List.of("app.report", "app.assign"),
                scenario.tables().stream().map(TermScenario.ScenarioTable::qualifiedName).toList(),
                "没有映射的 dict 被多展开了一跳");
        assertEquals(1, scenario.hops().size());
        assertEquals(TermScenario.Role.primary, scenario.tables().get(0).role());
        // 完整 ON 条件要原样透传，不能只给列名——复合外键漏掉租户列不报错、数是错的
        assertEquals("app.report.id = app.assign.report_id", scenario.hops().get(0).getJoinExpression());
    }

    @Test
    void bridgesTheGapAndLabelsIt() {
        GraphWorkspace workspace = seed();
        // report 和 score 之间没有直连，只能经过 assign
        relation(workspace, "report", "id", "assign", "report_id");
        relation(workspace, "assign", "id", "score", "assign_id");
        TermWorkspaceNode term = term(workspace, "报事");
        term.setPrimaryTarget(tableId("report"));
        map(workspace, term, "score");   // 只映射了 score，没映射中间的 assign

        TermScenario scenario = TermScenario.of(workspace, term);
        assertNotNull(scenario);
        // assign 必须出现且**标成桥接**：不标的话人会以为它也属于这个场景
        TermScenario.ScenarioTable assign = scenario.tables().stream()
                .filter(t -> t.qualifiedName().equals("app.assign")).findFirst().orElseThrow();
        assertEquals(TermScenario.Role.bridge, assign.role());
        assertTrue(scenario.unreachable().isEmpty());
        assertEquals(2, scenario.hops().size(), "桥接的两跳都要给出来，否则 join 拼不完整");
    }

    @Test
    void reportsWhatItCannotReachInsteadOfPretending() {
        GraphWorkspace workspace = seed();
        TermWorkspaceNode term = term(workspace, "报事");
        term.setPrimaryTarget(tableId("report"));
        map(workspace, term, "score");   // 图谱里一条关系都没有

        TermScenario scenario = TermScenario.of(workspace, term);
        assertNotNull(scenario);
        assertEquals(List.of("app.score"), scenario.unreachable(),
                "连不上就要说连不上——多半是关系还没录进图谱，不是可以忽略");
        assertTrue(scenario.hops().isEmpty());
    }

    @Test
    void filtersRideAlong() {
        GraphWorkspace workspace = seed();
        TermWorkspaceNode term = term(workspace, "报事");
        term.setPrimaryTarget(tableId("report"));
        term.setFilters(new java.util.ArrayList<>(
                List.of("state IN (0,1,6)", "subject_id = :subjectId")));

        TermScenario scenario = TermScenario.of(workspace, term);
        assertNotNull(scenario);
        assertEquals(List.of("state IN (0,1,6)", "subject_id = :subjectId"), scenario.filters());
    }

    // ── fixtures ──

    private static String tableId(String name) {
        return GraphIds.tableId(ALIAS, "app", name);
    }

    private static GraphWorkspace seed() {
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");
        for (String name : List.of("report", "assign", "score", "dict")) {
            TableWorkspaceNode table = TableWorkspaceNode.create(ALIAS, "app", name, GraphActor.extractor);
            for (String column : List.of("id", "report_id", "assign_id", "type_id", "state")) {
                table.getColumns().add(ColumnWorkspaceNode.create(column));
            }
            workspace.getTables().put(table.getId(), table);
        }
        return workspace;
    }

    private static TermWorkspaceNode term(GraphWorkspace workspace, String name) {
        TermWorkspaceNode term = TermWorkspaceNode.create(ALIAS, name, GraphActor.agent);
        workspace.getTerms().put(term.getId(), term);
        return term;
    }

    private static void map(GraphWorkspace workspace, TermWorkspaceNode term, String table) {
        workspace.getRelations().add(RelationWorkspaceEdge.create(
                ALIAS, RelationType.term_mapping, term.getId(), tableId(table), GraphActor.agent));
    }

    private static void relation(GraphWorkspace workspace, String fromTable, String fromColumn,
            String toTable, String toColumn) {
        RelationWorkspaceEdge edge = RelationWorkspaceEdge.create(ALIAS, RelationType.join_observed,
                GraphIds.columnId(ALIAS, "app", fromTable, fromColumn),
                GraphIds.columnId(ALIAS, "app", toTable, toColumn), GraphActor.agent);
        edge.setConfidence(0.9);
        edge.setJoinExpression("app." + fromTable + "." + fromColumn
                + " = app." + toTable + "." + toColumn);
        workspace.getRelations().add(edge);
    }
}
