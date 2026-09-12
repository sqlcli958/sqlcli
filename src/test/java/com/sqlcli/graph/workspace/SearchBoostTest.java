package com.sqlcli.graph.workspace;

import com.sqlcli.graph.workspace.index.WorkspaceIndexSnapshot;
import com.sqlcli.graph.workspace.index.WorkspaceIndexedSearchEngine;
import com.sqlcli.graph.workspace.index.WorkspaceIndexer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表/字段权重：人工 boost 与系统表降权只影响两个引擎的排序，字段契约不变；
 * 旧图谱文件没有 boost 字段照常加载。
 */
class SearchBoostTest {

    private static final String ALIAS = "boost-test";

    @Test
    void manualBoostReordersRealtimeResults() {
        GraphWorkspace workspace = workspaceWithTwoOrderTables();
        List<WorkspaceSearchEngine.SearchResult> plain = new WorkspaceSearchEngine(workspace).search("orders");
        assertEquals(2, plain.size());
        // 名字更短命中分略高：orders_main 默认排前
        assertEquals("table:" + ALIAS + ":app.orders_main", plain.get(0).getId());

        workspace.getTables().get("table:" + ALIAS + ":app.orders_archive").setBoost(2.0);
        List<WorkspaceSearchEngine.SearchResult> boosted = new WorkspaceSearchEngine(workspace).search("orders");
        assertEquals("table:" + ALIAS + ":app.orders_archive", boosted.get(0).getId(), "boost 应改变排序");
        assertTrue(boosted.get(0).getScore() > boosted.get(1).getScore());
    }

    @Test
    void systemTableIsDemotedInRealtimeSearch() {
        GraphWorkspace workspace = workspaceWithTwoOrderTables();
        workspace.getTables().get("table:" + ALIAS + ":app.orders_main").setSystem(true);
        List<WorkspaceSearchEngine.SearchResult> results = new WorkspaceSearchEngine(workspace).search("orders");
        assertEquals("table:" + ALIAS + ":app.orders_archive", results.get(0).getId(), "系统表降权后排后面");
    }

    @Test
    void columnBoostAppliesToColumnHits() {
        GraphWorkspace workspace = workspaceWithTwoOrderTables();
        TableWorkspaceNode main = workspace.getTables().get("table:" + ALIAS + ":app.orders_main");
        main.getColumns().get(0).setBoost(3.0);
        List<WorkspaceSearchEngine.SearchResult> results = new WorkspaceSearchEngine(workspace).search("status");
        assertEquals(2, results.size());
        assertEquals("column", results.get(0).getType());
        assertEquals("orders_main", results.get(0).getTableName());
        assertTrue(results.get(0).getScore() > results.get(1).getScore());
    }

    @Test
    void indexedEngineAppliesBoostAndSystemDemotion() {
        GraphWorkspace workspace = workspaceWithTwoOrderTables();
        // 默认 orders_main 分略高；boost 归档表 + 把 main 标成系统表后顺序应反转
        workspace.getTables().get("table:" + ALIAS + ":app.orders_archive").setBoost(2.0);
        workspace.getTables().get("table:" + ALIAS + ":app.orders_main").setSystem(true);

        WorkspaceIndexSnapshot snapshot = new WorkspaceIndexer().rebuild(workspace);
        List<WorkspaceIndexedSearchEngine.SearchHit> hits =
                new WorkspaceIndexedSearchEngine(snapshot).search("orders");
        List<String> tableHits = hits.stream()
                .filter(hit -> "table".equals(hit.getType()))
                .map(WorkspaceIndexedSearchEngine.SearchHit::getId).toList();
        assertEquals("table:" + ALIAS + ":app.orders_archive", tableHits.get(0));
        assertTrue(hits.get(0).getScore() > 0);
    }

    @Test
    void effectiveBoostDefaultsAndCombines() {
        assertEquals(1.0, SearchMatching.effectiveBoost(null, false));
        assertEquals(1.5, SearchMatching.effectiveBoost(1.5, false));
        assertEquals(SearchMatching.SYSTEM_TABLE_FACTOR, SearchMatching.effectiveBoost(null, true));
        assertEquals(2.0 * SearchMatching.SYSTEM_TABLE_FACTOR, SearchMatching.effectiveBoost(2.0, true));
    }

    @Test
    void legacyYamlWithoutBoostLoadsWithNullBoost() throws Exception {
        // 旧图谱文件没有 boost 字段：反序列化后为 null，搜索按 1.0 处理
        var yaml = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        TableWorkspaceNode table = yaml.readValue("""
                id: "table:%s:app.orders"
                kind: table
                schema: app
                name: orders
                columns:
                  - name: id
                """.formatted(ALIAS), TableWorkspaceNode.class);
        assertNull(table.getBoost());
        assertNull(table.getColumns().get(0).getBoost());
    }

    private GraphWorkspace workspaceWithTwoOrderTables() {
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");
        for (String name : List.of("orders_main", "orders_archive")) {
            TableWorkspaceNode table = TableWorkspaceNode.create(ALIAS, "app", name, GraphActor.extractor);
            table.getColumns().add(ColumnWorkspaceNode.create("status"));
            workspace.getTables().put(table.getId(), table);
        }
        return workspace;
    }
}
