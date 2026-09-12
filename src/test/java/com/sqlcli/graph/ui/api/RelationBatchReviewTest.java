package com.sqlcli.graph.ui.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcli.graph.ui.GraphUiSession;
import com.sqlcli.graph.ui.JsonHttpSupport;
import com.sqlcli.graph.workspace.ColumnWorkspaceNode;
import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphIds;
import com.sqlcli.graph.workspace.GraphStatus;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sqlcli.graph.workspace.RelationType;
import com.sqlcli.graph.workspace.RelationWorkspaceEdge;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * POST /api/relations/review：候选关系批量发布 / 拒绝。
 *
 * <p>服务端逐条复用单条 publish/reject 的服务逻辑，返回每条结果——
 * 这里钉住「部分失败能看出哪条失败」和「不 bump 失败条目的 revision」。
 */
class RelationBatchReviewTest {

    private static final String ALIAS = "batch";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir Path temp;

    private HttpServer server;
    private GraphWorkspaceStore store;
    private GraphUiSession session;
    private String candidateA;
    private String candidateB;
    private String previousHome;

    @BeforeEach
    void setUp() throws Exception {
        previousHome = System.getProperty("sqlcli.home");
        System.setProperty("sqlcli.home", temp.resolve("home").toString());
        store = new GraphWorkspaceStore(temp);

        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create(ALIAS, "app", "orders", GraphActor.extractor);
        orders.getColumns().add(ColumnWorkspaceNode.create("id"));
        orders.getColumns().add(ColumnWorkspaceNode.create("user_id"));
        orders.getColumns().add(ColumnWorkspaceNode.create("shop_id"));
        workspace.getTables().put(orders.getId(), orders);
        TableWorkspaceNode users = TableWorkspaceNode.create(ALIAS, "app", "users", GraphActor.extractor);
        users.getColumns().add(ColumnWorkspaceNode.create("id"));
        workspace.getTables().put(users.getId(), users);

        RelationWorkspaceEdge first = RelationWorkspaceEdge.create(ALIAS, RelationType.join_observed,
                GraphIds.columnId(ALIAS, "app", "orders", "user_id"),
                GraphIds.columnId(ALIAS, "app", "users", "id"), GraphActor.agent);
        first.setConfidence(0.6);
        workspace.getRelations().add(first);
        candidateA = first.getId();
        RelationWorkspaceEdge second = RelationWorkspaceEdge.create(ALIAS, RelationType.join_observed,
                GraphIds.columnId(ALIAS, "app", "orders", "shop_id"),
                GraphIds.columnId(ALIAS, "app", "users", "id"), GraphActor.agent);
        second.setConfidence(0.95);
        workspace.getRelations().add(second);
        candidateB = second.getId();
        assertEquals(GraphStatus.candidate, first.getStatus());
        store.save(workspace);

        session = new GraphUiSession(ALIAS, false);
        WorkspaceMutationController controller = new WorkspaceMutationController(
                workspace, session, new JsonHttpSupport(), null, store);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/relations", controller);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        if (previousHome == null) {
            System.clearProperty("sqlcli.home");
        } else {
            System.setProperty("sqlcli.home", previousHome);
        }
    }

    @Test
    void batchPublishReportsPerItemResultAndPartialFailure() throws Exception {
        JsonNode body = post("publish", List.of(candidateA, candidateB, "relation:missing"), 200);

        assertEquals(2, body.get("succeeded").asInt());
        assertEquals(1, body.get("failed").asInt());
        JsonNode results = body.get("results");
        assertEquals(3, results.size());
        assertTrue(results.get(0).get("success").asBoolean());
        assertTrue(results.get(1).get("success").asBoolean());
        assertFalse(results.get(2).get("success").asBoolean());
        assertEquals("relation:missing", results.get(2).get("relationId").asText());
        assertTrue(results.get(2).get("error").asText().contains("not found"));

        GraphWorkspace saved = store.load(ALIAS);
        // 发布规则与单条一致：>=0.9 verified，其余 partial
        assertEquals(GraphStatus.partial, findStatus(saved, candidateA));
        assertEquals(GraphStatus.verified, findStatus(saved, candidateB));
        assertEquals(body.get("newRevision").asLong(), saved.getManifest().getRevision());
    }

    @Test
    void batchRejectIgnoresCandidatesInsteadOfDeleting() throws Exception {
        JsonNode body = post("reject", List.of(candidateA, candidateB), 200);
        assertEquals(2, body.get("succeeded").asInt());
        GraphWorkspace saved = store.load(ALIAS);
        // 拒绝不再删边：两条候选仍在图谱里，状态转为 ignored，好让下次导入/挖掘认出来别再产出。
        assertEquals(GraphStatus.ignored, findStatus(saved, candidateA));
        assertEquals(GraphStatus.ignored, findStatus(saved, candidateB));
    }

    @Test
    void batchWithoutSessionTokenIsForbidden() throws Exception {
        HttpResponse<String> response = send(request("publish", List.of(candidateA)).build());
        assertEquals(403, response.statusCode());
    }

    // ---------------------------------------------------------------- 辅助

    private GraphStatus findStatus(GraphWorkspace workspace, String relationId) {
        return workspace.getRelations().stream()
                .filter(edge -> edge.getId().equals(relationId))
                .findFirst().orElseThrow().getStatus();
    }

    private JsonNode post(String action, List<String> ids, int expectedStatus) throws Exception {
        HttpResponse<String> response = send(request(action, ids)
                .header("X-Session-Token", session.getSessionToken()).build());
        assertEquals(expectedStatus, response.statusCode(), response.body());
        return MAPPER.readTree(response.body());
    }

    private HttpRequest.Builder request(String action, List<String> ids) throws Exception {
        String payload = MAPPER.writeValueAsString(
                java.util.Map.of("action", action, "relationIds", ids));
        return HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/relations/review"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload));
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
