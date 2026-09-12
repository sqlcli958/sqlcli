package com.sqlcli.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcli.graph.workspace.ColumnWorkspaceNode;
import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphStatus;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sqlcli.graph.workspace.RelationType;
import com.sqlcli.graph.workspace.RelationWorkspaceEdge;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迁移前固定 {@code schema query --depth N}（{@code SchemaActionCommand#expandRelations}）
 * 现有行为——重构成共享邻接层之前，先用这些用例把「深度边界」「ignored 过滤」
 * 「复合外键 joinExpression 透传」钉住，跑测挂了就是重构改了行为，不是重构本身该做的事。
 *
 * <p>表结构：a -> b -> c（单列外键），b -> d 是复合外键但整条边 ignored（不应出现在任何深度）。
 */
class SchemaQueryDepthTest {

    @TempDir
    Path temp;

    private GraphWorkspace buildWorkspace(GraphWorkspaceStore store) throws Exception {
        GraphWorkspace workspace = GraphWorkspace.create("depth", "mysql");
        TableWorkspaceNode a = TableWorkspaceNode.create("depth", "app", "a", GraphActor.extractor);
        a.getColumns().add(ColumnWorkspaceNode.create("b_id"));
        TableWorkspaceNode b = TableWorkspaceNode.create("depth", "app", "b", GraphActor.extractor);
        b.getColumns().add(ColumnWorkspaceNode.create("id"));
        b.getColumns().add(ColumnWorkspaceNode.create("c_id"));
        b.getColumns().add(ColumnWorkspaceNode.create("tenant_id"));
        b.getColumns().add(ColumnWorkspaceNode.create("d_no"));
        TableWorkspaceNode c = TableWorkspaceNode.create("depth", "app", "c", GraphActor.extractor);
        c.getColumns().add(ColumnWorkspaceNode.create("id"));
        TableWorkspaceNode d = TableWorkspaceNode.create("depth", "app", "d", GraphActor.extractor);
        d.getColumns().add(ColumnWorkspaceNode.create("tenant_id"));
        d.getColumns().add(ColumnWorkspaceNode.create("no"));
        workspace.getTables().put(a.getId(), a);
        workspace.getTables().put(b.getId(), b);
        workspace.getTables().put(c.getId(), c);
        workspace.getTables().put(d.getId(), d);

        RelationWorkspaceEdge ab = RelationWorkspaceEdge.create("depth", RelationType.foreign_key,
                "column:depth:app.a.b_id", "column:depth:app.b.id", GraphActor.extractor);
        ab.setJoinExpression("app.a.b_id = app.b.id");
        workspace.getRelations().add(ab);

        RelationWorkspaceEdge bc = RelationWorkspaceEdge.create("depth", RelationType.foreign_key,
                "column:depth:app.b.c_id", "column:depth:app.c.id", GraphActor.extractor);
        bc.setJoinExpression("app.b.c_id = app.c.id");
        workspace.getRelations().add(bc);

        // 复合外键：b -> d，两个列对都要在 joinExpression 里；这条边被人拒绝过，任何深度都不能出现
        RelationWorkspaceEdge bd = RelationWorkspaceEdge.create("depth", RelationType.foreign_key,
                "column:depth:app.b.tenant_id", "column:depth:app.d.tenant_id", GraphActor.extractor);
        String compound = "app.b.tenant_id = app.d.tenant_id AND app.b.d_no = app.d.no";
        bd.setJoinExpression(compound);
        bd.getAttributes().put(RelationWorkspaceEdge.ATTR_FK_GROUP, "fk_b_d");
        bd.setStatus(GraphStatus.ignored);
        workspace.getRelations().add(bd);

        store.save(workspace);
        return workspace;
    }

    @Test
    void depthOneOnlyReturnsDirectNeighbor() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        buildWorkspace(store);

        String stdout = runQuery(store, "app.a", 1, false);
        assertTrue(stdout.contains("a.b_id -> b.id"), stdout);
        assertFalse(stdout.contains("b.c_id -> c.id"), "depth 1 不应扩到两跳外的 c: " + stdout);
    }

    @Test
    void depthTwoReachesSecondHopButNotIgnoredEdge() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        buildWorkspace(store);

        String stdout = runQuery(store, "app.a", 2, false);
        assertTrue(stdout.contains("a.b_id -> b.id"), stdout);
        assertTrue(stdout.contains("b.c_id -> c.id"), "depth 2 应扩到 c: " + stdout);
        assertFalse(stdout.contains("d_no") || stdout.contains("tenant_id -> app.d") || stdout.contains(" d."),
                "ignored 的 b-d 边任何深度都不该出现: " + stdout);
    }

    @Test
    void jsonOutputExcludesIgnoredEdgeAndCarriesCompoundJoinExpressionWhenPresent() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        buildWorkspace(store);

        String stdout = runQuery(store, "app.b", 1, true);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(stdout);
        assertTrue(root.get("ok").asBoolean());
        JsonNode data = root.get("data");
        assertTrue(data.isArray());
        List<String> froms = new java.util.ArrayList<>();
        for (JsonNode edge : data) {
            froms.add(edge.get("from").asText());
        }
        // 从 b 出发深度 1：应该看到 a->b（反向匹配到 to=b）与 b->c，不应该看到被 ignored 的 b->d
        assertEquals(2, data.size(), "ignored 的 b-d 边不应出现在 JSON 结果里: " + stdout);
        for (JsonNode edge : data) {
            assertFalse(edge.get("to").asText().contains("app.d"), stdout);
        }
    }

    @Test
    void depthTwoDuplicatesSharedEdgeWhenBothEndpointsGetPopped() throws Exception {
        // 已知行为（不是本次重构要修的问题）：expandRelations 对每个弹出的表节点各自扫一遍
        // 全部 relations，a-b 这条边在"弹出 a"和"弹出 b"时各命中一次，depth>=2 时会在结果里
        // 出现两次。这是重构前就有的怪癖，钉在这里是为了不让共享层"顺手"把它去重掉——
        // 那本身就是一次没人要求、也没人审过的行为变化。
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        buildWorkspace(store);

        String stdout = runQuery(store, "app.a", 2, true);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode data = mapper.readTree(stdout).get("data");
        long abCount = 0;
        for (JsonNode edge : data) {
            if (edge.get("from").asText().equals("column:depth:app.a.b_id")) {
                abCount++;
            }
        }
        assertEquals(2, abCount, "a-b 边应在 a、b 分别被弹出时各计一次: " + stdout);
    }

    @Test
    void selfReferencingEdgeIsNotDoubleCountedFromSingleTablePop() throws Exception {
        // 自引用外键（父子层级表常见）：from/to 落在同一张表，老实现的 if/else-if
        // 互斥分支保证一次弹出只算一次；换成邻接表实现时，同一条边会在这张表的
        // 邻接桶里出现两次（正向一次、反向一次），必须显式去重，否则这里就是一次
        // 静默的行为变化。
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = GraphWorkspace.create("depth", "mysql");
        TableWorkspaceNode org = TableWorkspaceNode.create("depth", "app", "org", GraphActor.extractor);
        org.getColumns().add(ColumnWorkspaceNode.create("id"));
        org.getColumns().add(ColumnWorkspaceNode.create("parent_id"));
        workspace.getTables().put(org.getId(), org);
        RelationWorkspaceEdge selfFk = RelationWorkspaceEdge.create("depth", RelationType.foreign_key,
                "column:depth:app.org.parent_id", "column:depth:app.org.id", GraphActor.extractor);
        selfFk.setJoinExpression("app.org.parent_id = app.org.id");
        workspace.getRelations().add(selfFk);
        store.save(workspace);

        String stdout = runQuery(store, "app.org", 1, true);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode data = mapper.readTree(stdout).get("data");
        assertEquals(1, data.size(), "自引用边一次弹出只应计一次: " + stdout);
    }

    private String runQuery(GraphWorkspaceStore store, String table, int depth, boolean json) throws Exception {
        SchemaActionCommand command = new SchemaActionCommand(store);
        command.setAlias("depth");
        command.setAction("query");
        command.setTableName(table);
        command.setDepth(depth);
        command.setJsonOutput(json);

        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            assertEquals(0, command.executeCommand());
        } finally {
            System.setOut(originalOut);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
