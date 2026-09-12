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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * POST /api/relations/{id}/reject 与 /unignore 的 HTTP 路由与端到端行为。
 *
 * <p>{@link RelationBatchReviewTest} 已经在批量端点上钉住了「拒绝转 ignored 不删边」，
 * 这里补的是单条端点的路由本身（含 {@code /unignore} 后缀在 {@code extractRelationId}
 * 里正确剥离）与撤销回路。
 */
class RelationRejectUnignoreTest {

    private static final String ALIAS = "reject-http";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir Path temp;

    private HttpServer server;
    private GraphWorkspaceStore store;
    private GraphUiSession session;
    private String candidateId;
    private long revision;
    private String previousHome;

    @BeforeEach
    void setUp() throws Exception {
        previousHome = System.getProperty("sqlcli.home");
        System.setProperty("sqlcli.home", temp.resolve("home").toString());
        store = new GraphWorkspaceStore(temp);

        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create(ALIAS, "app", "orders", GraphActor.extractor);
        orders.getColumns().add(ColumnWorkspaceNode.create("user_id"));
        workspace.getTables().put(orders.getId(), orders);
        TableWorkspaceNode users = TableWorkspaceNode.create(ALIAS, "app", "users", GraphActor.extractor);
        users.getColumns().add(ColumnWorkspaceNode.create("id"));
        workspace.getTables().put(users.getId(), users);

        RelationWorkspaceEdge edge = RelationWorkspaceEdge.create(ALIAS, RelationType.join_observed,
                GraphIds.columnId(ALIAS, "app", "orders", "user_id"),
                GraphIds.columnId(ALIAS, "app", "users", "id"), GraphActor.agent);
        edge.setConfidence(0.6);
        workspace.getRelations().add(edge);
        candidateId = edge.getId();
        store.save(workspace);
        // 乐观锁按真实 revision 走，不能猜一个数字——setUp 只 save 了一次，
        // 这里就是新建工作区之后的那一个 revision，后续请求都从它起步。
        revision = workspace.getManifest().getRevision();

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
    void rejectThenUnignoreRoundTrip() throws Exception {
        JsonNode rejectResp = post(candidateId, "reject", revision, "命中率太低", 200);
        assertEquals(candidateId, rejectResp.get("relationId").asText());

        GraphWorkspace afterReject = store.load(ALIAS);
        RelationWorkspaceEdge rejected = findRelation(afterReject, candidateId);
        assertEquals(GraphStatus.ignored, rejected.getStatus());
        assertEquals("命中率太低", rejected.getAttributes().get("rejectionReason"));

        long revisionAfterReject = afterReject.getManifest().getRevision();
        JsonNode unignoreResp = post(candidateId, "unignore", revisionAfterReject, "重新考虑", 200);
        assertEquals(candidateId, unignoreResp.get("relationId").asText());

        GraphWorkspace afterUnignore = store.load(ALIAS);
        RelationWorkspaceEdge restored = findRelation(afterUnignore, candidateId);
        assertEquals(GraphStatus.candidate, restored.getStatus());
        assertNull(restored.getAttributes().get("rejectionReason"));
    }

    @Test
    void rejectWithoutSessionTokenIsForbidden() throws Exception {
        HttpResponse<String> response = send(request(candidateId, "reject", revision, "x").build());
        assertEquals(403, response.statusCode());
    }

    // ---------------------------------------------------------------- 辅助

    private RelationWorkspaceEdge findRelation(GraphWorkspace workspace, String relationId) {
        return workspace.getRelations().stream()
                .filter(edge -> edge.getId().equals(relationId))
                .findFirst().orElseThrow();
    }

    private JsonNode post(String relationId, String action, long expectedRevision, String reason,
            int expectedStatus) throws Exception {
        HttpResponse<String> response = send(request(relationId, action, expectedRevision, reason)
                .header("X-Session-Token", session.getSessionToken()).build());
        assertEquals(expectedStatus, response.statusCode(), response.body());
        return MAPPER.readTree(response.body());
    }

    private HttpRequest.Builder request(String relationId, String action, long expectedRevision, String reason)
            throws Exception {
        String payload = MAPPER.writeValueAsString(Map.of("expectedRevision", expectedRevision, "reason", reason));
        // relationId 里的冒号/箭头不是合法的 URI 字符，和前端 encodeURIComponent 一样先编码
        // 再拼路径，服务端 extractRelationId 会用 URLDecoder 还原。
        String encoded = java.net.URLEncoder.encode(relationId, java.nio.charset.StandardCharsets.UTF_8);
        return HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                        + "/api/relations/" + encoded + "/" + action))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload));
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
