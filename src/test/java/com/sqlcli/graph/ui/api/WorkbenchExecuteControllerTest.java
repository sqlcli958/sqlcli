package com.sqlcli.graph.ui.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcli.approval.ApprovalGate;
import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.connection.ConnectionManager;
import com.sqlcli.graph.ui.JsonHttpSupport;
import com.sqlcli.graph.workspace.ColumnWorkspaceNode;
import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphIds;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sqlcli.graph.workspace.MetricRecord;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import com.sqlcli.graph.workspace.TermWorkspaceNode;
import com.sqlcli.metric.MetricSqlExpander;
import com.sqlcli.metric.MetricSqlRequest;
import com.sqlcli.runstate.RunStateStore;
import com.sqlcli.strategy.DatabaseStrategies;
import com.sqlcli.task.SqlTaskModule;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 这一组用例全部**不连数据库**：拒绝发生在 GuardStage（纯静态检查），
 * 失败发生在建连接的那一步（URL 指向不存在的端口）。RunStateStore 指向临时目录，
 * 不碰真实的 ~/.sql-cli/sqlcli.db。
 */
class WorkbenchExecuteControllerTest {

    private HttpServer server;
    private Path root;
    private String origin;
    private RunStateStore runState;
    private GraphWorkspaceStore workspaceStore;
    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void startServer() throws Exception {
        root = Files.createTempDirectory("workbench-execute-");
        runState = new RunStateStore(root.resolve("sqlcli.db"), root.resolve("history"));
        workspaceStore = new GraphWorkspaceStore(root.resolve("graphs"));
        seedSemanticWorkspace();
        SqlTaskModule taskModule = new SqlTaskModule(
                new ConnectionManager(), runState, new ApprovalGate(runState, 1000));

        DatabaseConfig locked = config("locked");
        locked.setReadonly(true);
        DatabaseConfig open = config("open");

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/api/workbench/execute", exchange ->
                new WorkbenchExecuteController(Map.of("locked", locked, "open", open), null,
                        origin, new JsonHttpSupport(), taskModule, workspaceStore).handle(exchange));
        server.createContext("/api/workbench/cancel", exchange ->
                new WorkbenchExecuteController(Map.of("locked", locked, "open", open), null,
                        origin, new JsonHttpSupport(), taskModule, workspaceStore).handleCancel(exchange));
        server.start();
    }

    @AfterEach
    void stopServer() throws Exception {
        if (server != null) server.stop(0);
        if (root != null) {
            try (var files = Files.walk(root)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    /** 核心决策：被拒绝是业务结果，走 200 + status=REJECTED，不是 HTTP 错误。 */
    @Test
    void readonlyAliasRejectsWriteWithTwoHundred() throws Exception {
        HttpResponse<String> response = execute("locked", "{\"sql\":\"DELETE FROM users WHERE id = 1\"}");
        assertEquals(200, response.statusCode());
        JsonNode body = mapper.readTree(response.body());
        assertEquals("REJECTED", body.get("status").asText());
        assertTrue(body.get("errorSummary").asText().contains("readonly"), body.get("errorSummary").asText());
        assertEquals(0, body.get("rowCount").asInt());
        assertFalse(body.get("truncated").asBoolean());
    }

    /** 工作台没有只读白名单——写语句必须能走到执行这一步，挡它的是别名配置不是端点。 */
    @Test
    void writeStatementIsNotWhitelistedAway() throws Exception {
        HttpResponse<String> response = execute("open", "{\"sql\":\"DELETE FROM users WHERE id = 1\"}");
        assertEquals(200, response.statusCode());
        JsonNode body = mapper.readTree(response.body());
        // 连不上库，所以是 FAILED 而不是 REJECTED：说明它没有在语句类型这一关被挡掉。
        assertEquals("FAILED", body.get("status").asText());
        assertEquals("DELETE", body.get("sqlType").asText());
    }

    /** 无 WHERE 的 DELETE 由 GuardStage 拒绝，端点自己不重写一份规则。 */
    @Test
    void deleteWithoutWhereIsRejected() throws Exception {
        JsonNode body = mapper.readTree(execute("open", "{\"sql\":\"DELETE FROM users\"}").body());
        assertEquals("REJECTED", body.get("status").asText());
    }

    @Test
    void multipleStatementsAreRejected() throws Exception {
        JsonNode body = mapper.readTree(execute("open", "{\"sql\":\"SELECT 1; SELECT 2\"}").body());
        assertEquals("REJECTED", body.get("status").asText());
    }

    @Test
    void blankSqlAndUnknownAliasAndBadOriginAreRequestErrors() throws Exception {
        assertEquals(400, execute("open", "{\"sql\":\"  \"}").statusCode());
        assertEquals(404, execute("nope", "{\"sql\":\"SELECT 1\"}").statusCode());

        HttpResponse<String> forged = client.send(HttpRequest.newBuilder()
                .uri(URI.create(origin + "/api/workbench/execute?alias=open"))
                .header("Content-Type", "application/json")
                .header("Origin", "http://evil.example")
                .POST(HttpRequest.BodyPublishers.ofString("{\"sql\":\"SELECT 1\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, forged.statusCode());
    }

    @Test
    void semanticContextIsServerVerifiedAndMetricRunKeepsTermAttribution() throws Exception {
        GraphWorkspace workspace = workspaceStore.load("open");
        MetricRecord metric = workspace.getMetrics().get(GraphIds.metricId("open", "gmv_paid"));
        TermWorkspaceNode term = workspace.getTerms().get(GraphIds.termId("open", "paid_orders"));
        String sql = MetricSqlExpander.expand(
                workspace,
                metric,
                DatabaseStrategies.resolve("mysql"),
                new MetricSqlRequest("day", null, null, java.util.List.of(), term.getFilters()));

        Map<String, Object> context = new java.util.LinkedHashMap<>();
        context.put("metricId", metric.getId());
        context.put("metricRevision", workspace.getManifest().getRevision());
        context.put("termId", term.getId());
        context.put("grain", "day");
        Map<String, Object> request = Map.of("sql", sql, "semanticContext", context);

        HttpResponse<String> accepted = execute("open", mapper.writeValueAsString(request));
        assertEquals(200, accepted.statusCode(), accepted.body());
        assertEquals("FAILED", mapper.readTree(accepted.body()).get("status").asText(),
                "通过语义校验后才会走到故意失败的数据库连接");

        var runs = runState.listMetricRuns("open", metric.getId(), term.getId(), 10);
        assertEquals(1, runs.size());
        assertEquals(term.getId(), runs.get(0).termId());
        assertEquals("failed", runs.get(0).status());

        Map<String, Object> forgedSql = Map.of(
                "sql", "SELECT 123",
                "semanticContext", context);
        HttpResponse<String> rejectedSql = execute("open", mapper.writeValueAsString(forgedSql));
        assertEquals(400, rejectedSql.statusCode(), rejectedSql.body());
        assertTrue(mapper.readTree(rejectedSql.body()).get("message").asText().contains("does not match SQL"));

        Map<String, Object> forgedContext = new java.util.LinkedHashMap<>(context);
        forgedContext.put("termId", GraphIds.termId("open", "all_orders"));
        HttpResponse<String> rejectedTerm = execute("open", mapper.writeValueAsString(Map.of(
                "sql", sql,
                "semanticContext", forgedContext)));
        assertEquals(400, rejectedTerm.statusCode(), rejectedTerm.body());
        assertTrue(mapper.readTree(rejectedTerm.body()).get("message").asText().contains("not bound"));
    }

    /** 执行已结束（或 token 不对）时中止不是错误，答 cancelled=false 让前端别标「已中止」。 */
    @Test
    void cancelWithUnknownTokenAnswersFalse() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                .uri(URI.create(origin + "/api/workbench/cancel"))
                .header("Content-Type", "application/json")
                .header("Origin", origin)
                .POST(HttpRequest.BodyPublishers.ofString("{\"cancelToken\":\"gone\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertFalse(mapper.readTree(response.body()).get("cancelled").asBoolean());
    }

    @Test
    void nonPostIsRejected() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                .uri(URI.create(origin + "/api/workbench/execute?alias=open")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, response.statusCode());
    }

    private void seedSemanticWorkspace() throws Exception {
        GraphWorkspace workspace = GraphWorkspace.create("open", "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create("open", "app", "orders", GraphActor.extractor);
        for (String name : java.util.List.of("id", "amount", "status", "created_at")) {
            orders.getColumns().add(ColumnWorkspaceNode.create(name));
        }
        workspace.getTables().put(orders.getId(), orders);

        MetricRecord metric = MetricRecord.create("open", "gmv_paid", GraphActor.human);
        metric.setExpression("SUM(orders.amount)");
        MetricRecord.MetricGrain grain = new MetricRecord.MetricGrain();
        grain.setTimeColumn(GraphIds.columnId("open", "app", "orders", "created_at"));
        grain.setGrains(java.util.List.of("day"));
        metric.setGrain(grain);
        workspace.getMetrics().put(metric.getId(), metric);

        TermWorkspaceNode paid = TermWorkspaceNode.create("open", "paid_orders", GraphActor.human);
        paid.setPrimaryTarget(orders.getId());
        paid.setFilters(new java.util.ArrayList<>(java.util.List.of("status IN (2,3)")));
        paid.setMetricRefs(new java.util.ArrayList<>(java.util.List.of(metric.getId())));
        workspace.getTerms().put(paid.getId(), paid);

        TermWorkspaceNode all = TermWorkspaceNode.create("open", "all_orders", GraphActor.human);
        all.setPrimaryTarget(orders.getId());
        workspace.getTerms().put(all.getId(), all);

        workspaceStore.save(workspace);
    }

    private HttpResponse<String> execute(String alias, String body) throws Exception {
        return client.send(HttpRequest.newBuilder()
                .uri(URI.create(origin + "/api/workbench/execute?alias=" + alias))
                .header("Content-Type", "application/json")
                .header("Origin", origin)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 指向一个没人监听的端口：建连接必然失败，不会碰到任何真实数据库。 */
    private DatabaseConfig config(String name) {
        DatabaseConfig config = new DatabaseConfig();
        config.setAliasName(name);
        config.setType("mysql");
        config.setJdbcUrl("jdbc:mysql://127.0.0.1:1/nonexistent");
        config.setUsername("nobody");
        return config;
    }
}
