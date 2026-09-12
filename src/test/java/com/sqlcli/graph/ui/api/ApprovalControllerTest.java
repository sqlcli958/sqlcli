package com.sqlcli.graph.ui.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcli.graph.ui.JsonHttpSupport;
import com.sqlcli.graph.ui.service.ApprovalEffects;
import com.sqlcli.graph.ui.service.GraphChangePayload;
import com.sqlcli.graph.ui.service.WorkspaceLockManager;
import com.sqlcli.graph.ui.service.WorkspaceMutationService;
import com.sqlcli.graph.workspace.ColumnWorkspaceNode;
import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphIds;
import com.sqlcli.graph.workspace.GraphStatus;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sqlcli.graph.workspace.RelationType;
import com.sqlcli.graph.workspace.RelationWorkspaceEdge;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import com.sqlcli.graph.workspace.WorkspaceValidator;
import com.sqlcli.runstate.RunStateStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalControllerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private String start(RunStateStore runState) throws Exception {
        return start(runState, null, null);
    }

    private String start(RunStateStore runState, GraphWorkspaceStore store, ApprovalEffects effects)
            throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        ApprovalController controller = new ApprovalController(origin, new JsonHttpSupport(),
                runState, store, effects);
        server.createContext("/api/approvals", controller::handle);
        server.start();
        return origin;
    }

    @Test
    void detailInlinesPrecheckPayloadAndTimeline(@TempDir Path dir) throws Exception {
        RunStateStore runState = new RunStateStore(dir.resolve("sqlcli.db"), dir.resolve("history"));
        long runId = runState.createTaskRun("demo", "sql_write", "cli", "hash");
        runState.recordTaskEvent(runId, "submitted", null);
        runState.recordTaskEvent(runId, "precheck",
                "{\"table\":\"users\",\"recoverySupported\":true,\"primaryKey\":[\"id\"],\"estimatedRows\":7}");
        runState.recordTaskEvent(runId, "executed", "{\"affectedRows\":7,\"truncated\":false}");
        long id = runState.createApproval("demo", "update", "UPDATE users …",
                "UPDATE users SET a = 1", runId);

        JsonNode body = get(start(runState) + "/api/approvals/" + id, 200);

        assertEquals("demo", body.get("alias").asText());
        assertEquals(runId, body.get("taskRun").get("id").asLong());
        assertEquals("running", body.get("taskRun").get("status").asText());
        assertEquals(3, body.get("events").size());
        assertEquals("submitted", body.get("events").get(0).get("eventType").asText());
        assertTrue(body.get("events").get(0).get("payload").isNull());
        // payload 必须是内联的对象，不是一串还要前端再解一次的 JSON 文本
        JsonNode precheck = body.get("events").get(1).get("payload");
        assertTrue(precheck.isObject(), "precheck payload 应该内联成对象");
        assertEquals("users", precheck.get("table").asText());
        assertEquals(7, precheck.get("estimatedRows").asInt());
        assertEquals(7, body.get("events").get(2).get("payload").get("affectedRows").asInt());
    }

    @Test
    void detailWithoutTaskRunHasNoEvents(@TempDir Path dir) throws Exception {
        RunStateStore runState = new RunStateStore(dir.resolve("sqlcli.db"), dir.resolve("history"));
        long id = runState.createApproval("demo", "graph", "发布候选关系", "detail");

        JsonNode body = get(start(runState) + "/api/approvals/" + id, 200);

        assertTrue(body.get("taskRun").isNull());
        assertEquals(0, body.get("events").size());
    }

    /**
     * 审批记录按别名过滤；pending 角标不跟着别名走——
     * CLI 在别的库上等审批时，不该因为顶栏选中的是另一个别名就看不见。
     */
    @Test
    void listFiltersByAliasButPendingBadgeStaysGlobal(@TempDir Path dir) throws Exception {
        RunStateStore runState = new RunStateStore(dir.resolve("sqlcli.db"), dir.resolve("history"));
        runState.createApproval("demo", "update", "demo 的", null);
        runState.createApproval("other", "update", "other 的", null);
        String origin = start(runState);

        JsonNode scoped = get(origin + "/api/approvals?status=all&alias=demo", 200);
        assertEquals(1, scoped.get("approvals").size());
        assertEquals("demo", scoped.get("approvals").get(0).get("alias").asText());
        assertEquals(1, scoped.get("total").asInt());
        assertEquals(2, scoped.get("pending").asInt(), "角标是跨数据源的");

        assertEquals(2, get(origin + "/api/approvals?status=all&alias=all", 200).get("total").asInt());
        assertEquals(2, get(origin + "/api/approvals?status=all", 200).get("total").asInt());
    }

    @Test
    void unknownApprovalIsNotFound(@TempDir Path dir) throws Exception {
        RunStateStore runState = new RunStateStore(dir.resolve("sqlcli.db"), dir.resolve("history"));
        get(start(runState) + "/api/approvals/999", 404);
    }

    /**
     * 批准一条候选边的发布审批：走完整 HTTP 路径。
     *
     * <p>钉的是「落地动作自己把审批结掉」和「控制器再结一次」之间那道坎：
     * {@code publishRelation} 内部会把同 target 的待审批置成 approved，
     * 控制器随后的 {@code decideApproval} 就更新不到行了。早先这里直接抛
     * 「已经是 approved，无法再次裁决」——明明全都成功了，用户看到的是失败，
     * 于是再点一次，两个请求撞在一起才产生那条 revision mismatch。
     */
    @Test
    void approvingACandidatePublishSucceedsEvenThoughTheEffectSettlesTheRowItself(
            @TempDir Path dir) throws Exception {
        System.setProperty("sqlcli.home", dir.resolve("home").toString());
        try {
            RunStateStore runState = new RunStateStore(
                    dir.resolve("home/sqlcli.db"), dir.resolve("home/history"));
            GraphWorkspaceStore store = new GraphWorkspaceStore(dir.resolve("graphs"));
            String relationId = seedCandidate(store);
            long id = runState.createApproval("demo", "graph", "发布候选关系", null, null, relationId,
                    GraphChangePayload.publish(relationId, "agent", 1).toJson());

            String origin = start(runState, store,
                    new ApprovalEffects(store, java.util.Map.of(), null));
            JsonNode body = decide(origin, id, "approved", "证据充分", origin, 200);

            assertEquals("approved", body.get("status").asText());
            assertEquals(GraphStatus.verified,
                    store.load("demo").getRelations().get(0).getStatus(), "批准就该把它发布出去");
        } finally {
            System.clearProperty("sqlcli.home");
        }
    }

    /** 两张表 + 一条 agent 写的候选边；返回候选边 id。 */
    private String seedCandidate(GraphWorkspaceStore store) throws Exception {
        GraphWorkspace workspace = GraphWorkspace.create("demo", "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create("demo", "app", "orders", GraphActor.extractor);
        orders.getColumns().add(ColumnWorkspaceNode.create("user_id"));
        workspace.getTables().put(orders.getId(), orders);
        TableWorkspaceNode users = TableWorkspaceNode.create("demo", "app", "users", GraphActor.extractor);
        users.getColumns().add(ColumnWorkspaceNode.create("id"));
        workspace.getTables().put(users.getId(), users);
        RelationWorkspaceEdge edge = RelationWorkspaceEdge.create("demo", RelationType.join_observed,
                GraphIds.columnId("demo", "app", "orders", "user_id"),
                GraphIds.columnId("demo", "app", "users", "id"), GraphActor.agent);
        edge.setConfidence(0.95);
        workspace.getRelations().add(edge);
        store.save(workspace);
        return edge.getId();
    }

    private JsonNode decide(String base, long id, String decision, String reason, String origin,
            int expectedStatus) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create(base + "/api/approvals/" + id + "/decide"))
                        .header("Origin", origin)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"decision\":\"" + decision + "\",\"reason\":\"" + reason + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedStatus, response.statusCode(), response.body());
        return MAPPER.readTree(response.body());
    }

    private JsonNode get(String url, int expectedStatus) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedStatus, response.statusCode(), response.body());
        return MAPPER.readTree(response.body());
    }
}
