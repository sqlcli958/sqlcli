package com.sqlcli.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcli.graph.workspace.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 搜索命中场景术语时的渲染。
 *
 * <p>钉住三件事，每一件都是「不这样做 agent 就会写错 SQL」：
 * 场景要给出表之间怎么连（不然它自己拼）、过滤要单独一行（漏了就是静默少数据）、
 * 场景带出来的表不再单独重复一次（否则同一张表出现两遍，看不出哪个是场景内的）。
 */
class SchemaTermScenarioSearchTest {

    private static final String ALIAS = "demo";

    @TempDir Path temp;

    @Test
    void scenarioHitGivesTablesJoinsAndFilters() throws Exception {
        String out = searchFor(seedScenario());

        assertTrue(out.contains("场景「报事」"), out);
        assertTrue(out.contains("住户提交的一次维修请求"), out);
        assertTrue(out.contains("同义词: 工单、报修"), out);
        // join 条件必须给出来——这正是「只给点不给边」要修的
        assertTrue(out.contains("ON app.report.id = app.assign.report_id"), out);
        // 过滤单独一行，含占位符的必填参数
        assertTrue(out.contains("过滤: state IN (0,1,6)  AND  subject_id = :subjectId"), out);
        assertTrue(out.contains("★ app.report"), out);
    }

    @Test
    void tablesBroughtOutByTheScenarioAreNotListedTwice() throws Exception {
        String out = searchFor(seedScenario());
        // 「不重复」看的是它有没有另起一组，不是字面出现几次——
        // ON 条件里本来就会再出现一次表名
        assertFalse(out.contains("[TABLE] app.assign"),
                "场景已经列出的表又单独成了一组 -> " + out);
        assertTrue(out.contains("按表折叠为 1 组"),
                "表头报的组数是折叠前的，跟屏幕上看到的对不上 -> " + out);
        assertFalse(out.contains("[TERM] 报事"),
                "场景术语不该再占一条普通结果位：\n" + out);
    }

    /**
     * T3 的 JSON 契约缺口：命中项要标出它是「术语明确带出来的」还是「关键词凑巧命中的」，
     * 两者可信度差一个量级，agent 不该拿同一个字段去混排。
     */
    @Test
    void jsonHitsAreTaggedWithTermExpansionOrigin() throws Exception {
        GraphWorkspace workspace = seedScenario();
        new GraphWorkspaceStore(temp).save(workspace);
        SchemaActionCommand cmd = new SchemaActionCommand(new GraphWorkspaceStore(temp));
        cmd.setAlias(ALIAS);
        cmd.setAction("search");
        cmd.setKeyword("报事");
        cmd.setJsonOutput(true);
        cmd.setCommandArgs(List.of("search", "报事", "--json"));

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(buffer, true, "UTF-8"));
        try {
            cmd.executeCommand();
        } finally {
            System.setOut(original);
        }
        JsonNode data = new ObjectMapper().readTree(buffer.toString("UTF-8")).get("data");

        String termId = "term:" + ALIAS + ":报事";
        JsonNode termHit = null;
        JsonNode expandedTable = null;
        for (JsonNode hit : data) {
            if (termId.equals(hit.get("id").asText())) termHit = hit;
            // 这条走的是实时引擎（未建索引），原生字段名是 tableName，不是 table——
            // JSON 契约保持引擎原生结构，只加 origin / expandedByTermId 两个字段
            if ("app".equals(hit.path("schema").asText(null))
                    && "assign".equals(hit.path("tableName").asText(null))) expandedTable = hit;
        }
        assertTrue(termHit != null, "场景术语本身仍要出现在 JSON 里");
        assertEquals("term", termHit.get("origin").asText());
        assertTrue(expandedTable != null, "术语带出的表要在 JSON 命中里能找到");
        assertEquals("term_expansion", expandedTable.get("origin").asText());
        assertEquals(termId, expandedTable.get("expandedByTermId").asText());
    }

    private GraphWorkspace seedScenario() {
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");
        for (String name : List.of("report", "assign")) {
            TableWorkspaceNode table = TableWorkspaceNode.create(ALIAS, "app", name, GraphActor.extractor);
            table.setBusinessName("报事" + name);
            for (String column : List.of("id", "report_id")) {
                table.getColumns().add(ColumnWorkspaceNode.create(column));
            }
            workspace.getTables().put(table.getId(), table);
        }
        RelationWorkspaceEdge edge = RelationWorkspaceEdge.create(ALIAS, RelationType.join_observed,
                GraphIds.columnId(ALIAS, "app", "report", "id"),
                GraphIds.columnId(ALIAS, "app", "assign", "report_id"), GraphActor.agent);
        edge.setConfidence(0.9);
        edge.setJoinExpression("app.report.id = app.assign.report_id");
        workspace.getRelations().add(edge);

        TermWorkspaceNode term = TermWorkspaceNode.create(ALIAS, "报事", GraphActor.agent);
        term.setDescription("住户提交的一次维修请求");
        term.getAliases().addAll(List.of("工单", "报修"));
        term.setPrimaryTarget(GraphIds.tableId(ALIAS, "app", "report"));
        term.setFilters(new java.util.ArrayList<>(List.of("state IN (0,1,6)", "subject_id = :subjectId")));
        workspace.getTerms().put(term.getId(), term);
        workspace.getRelations().add(RelationWorkspaceEdge.create(ALIAS, RelationType.term_mapping,
                term.getId(), GraphIds.tableId(ALIAS, "app", "assign"), GraphActor.agent));
        return workspace;
    }

    private String searchFor(GraphWorkspace workspace) throws Exception {
        new GraphWorkspaceStore(temp).save(workspace);
        SchemaActionCommand cmd = new SchemaActionCommand(new GraphWorkspaceStore(temp));
        cmd.setAlias(ALIAS);
        cmd.setAction("search");
        cmd.setKeyword("报事");
        cmd.setCommandArgs(List.of("search", "报事"));
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(buffer, true, "UTF-8"));
        try {
            cmd.executeCommand();
        } finally {
            System.setOut(original);
        }
        return buffer.toString("UTF-8");
    }
}
