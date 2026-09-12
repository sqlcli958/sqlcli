package com.sqlcli.graph.ui.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcli.graph.ui.GraphUiSession;
import com.sqlcli.graph.ui.JsonHttpSupport;
import com.sqlcli.graph.workspace.ColumnDataType;
import com.sqlcli.graph.workspace.ColumnWorkspaceNode;
import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphIds;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.RelationType;
import com.sqlcli.graph.workspace.RelationWorkspaceEdge;
import com.sqlcli.graph.workspace.TableType;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import com.sqlcli.graph.workspace.TermWorkspaceNode;
import com.sqlcli.graph.workspace.WorkspaceManifest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 顶栏搜索必须搜得到术语。
 *
 * <p>这条是回归测试，不是新功能的测试：实测过图谱里明明有「扫码」这条术语、CLI 搜得到、
 * {@code /api/terms} 也返回，但顶栏搜索框一条术语都不出——{@code handleSearch} 的默认
 * {@code type} 过滤器写的是 {@code "table,column"}，引擎返回了、控制器丢了。
 * 而「用业务词找到对的表」正是术语存在的全部理由，搜不到等于这个概念在 UI 上不存在。
 *
 * <p>第二个断言同样重要：术语自己没有 schema / table，不回填它指向的对象，
 * 搜索框里点它什么也不会发生——一条点不动的结果和没出来差不多。
 */
class SearchIncludesTermsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ALIAS = "search-term-test";

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private String start(GraphWorkspace workspace) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        GraphUiSession session = new GraphUiSession(ALIAS, false);
        // indexStore 传 null，走实时引擎那条分支——两条分支的过滤逻辑是同一份默认值
        WorkspaceQueryController controller =
                new WorkspaceQueryController(workspace, session, new JsonHttpSupport(), null);
        server.createContext("/api/search", controller::handle);
        server.start();
        return origin;
    }

    private JsonNode search(String origin, String query) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(origin + "/api/search?q="
                        + URLEncoder.encode(query, StandardCharsets.UTF_8)))
                .GET().build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode());
        return MAPPER.readTree(response.body());
    }

    /** 一张表 + 一列 + 一条指向该列的术语。 */
    private GraphWorkspace buildWorkspace() {
        WorkspaceManifest manifest = WorkspaceManifest.create(ALIAS);
        manifest.setRevision(1);

        TableWorkspaceNode plan = TableWorkspaceNode.create(ALIAS, "app", "task_plan", GraphActor.human);
        plan.setTableType(TableType.base_table);
        ColumnWorkspaceNode isScan = new ColumnWorkspaceNode();
        isScan.setName("is_scan");
        isScan.setDataType(new ColumnDataType());
        isScan.setComment("是否扫码");
        plan.getColumns().add(isScan);

        String columnId = GraphIds.columnId(ALIAS, "app", "task_plan", "is_scan");
        TermWorkspaceNode term = TermWorkspaceNode.create(ALIAS, "扫码", GraphActor.agent);
        term.setDescription("巡检点位打卡扫码，不是考勤打卡");
        term.setPrimaryTarget(columnId);

        RelationWorkspaceEdge mapping = RelationWorkspaceEdge.create(
                ALIAS, RelationType.term_mapping, term.getId(), columnId, GraphActor.agent);

        GraphWorkspace workspace = new GraphWorkspace();
        workspace.setManifest(manifest);
        workspace.getTables().put(plan.getId(), plan);
        workspace.getTerms().put(term.getId(), term);
        workspace.getRelations().add(mapping);
        return workspace;
    }

    @Test
    void searchWithoutTypeParamStillReturnsTerms() throws Exception {
        String origin = start(buildWorkspace());

        JsonNode results = search(origin, "扫码").get("results");
        JsonNode term = null;
        for (JsonNode hit : results) {
            if ("term".equals(hit.path("type").asText())) term = hit;
        }

        assertNotNull(term, "不传 type 时也必须搜得到术语；默认过滤器漏掉 term 等于术语在 UI 上不存在");
        assertEquals("扫码", term.path("name").asText());
    }

    @Test
    void termHitCarriesTheObjectItPointsAt() throws Exception {
        String origin = start(buildWorkspace());

        JsonNode results = search(origin, "扫码").get("results");
        JsonNode term = null;
        for (JsonNode hit : results) {
            if ("term".equals(hit.path("type").asText())) term = hit;
        }
        assertNotNull(term);

        // 术语自己没有坐标，回填的是 primaryTarget 指向的那个对象——前端靠这三个字段跳转
        assertEquals("app", term.path("schema").asText());
        assertEquals("task_plan", term.path("table").asText());
        assertEquals("is_scan", term.path("column").asText());
        // name 仍然是术语名，不是被指向对象的名字
        assertEquals("扫码", term.path("name").asText());
    }

    @Test
    void termWithoutPrimaryTargetFallsBackToItsFirstMapping() throws Exception {
        GraphWorkspace workspace = buildWorkspace();
        workspace.getTerms().values().forEach(term -> term.setPrimaryTarget(null));

        String origin = start(workspace);
        JsonNode results = search(origin, "扫码").get("results");
        boolean found = false;
        for (JsonNode hit : results) {
            if (!"term".equals(hit.path("type").asText())) continue;
            found = true;
            assertTrue(hit.path("table").asText().equals("task_plan"),
                    "没标主次时退到第一条 term_mapping，否则这条术语点不动");
        }
        assertTrue(found, "术语必须在结果里");
    }
}
