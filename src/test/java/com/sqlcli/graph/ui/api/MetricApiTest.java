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
import com.sqlcli.graph.workspace.MetricRecord;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指标的 Web UI 入口：{@code GET/POST /api/metrics} 与 {@code GET /api/metrics/{name}/sql}。
 *
 * <p>这一组端点存在的理由是 BI 语义层那条验收标准——「一条真实指标，从<b>人在 UI 里定义</b>，
 * 到生成出正确 SQL 并执行成功」。在此之前 metric 只有 CLI，`grep MetricRecord` 在整个
 * graph/ui 包里零命中，那条验收按字面根本跑不了。
 */
class MetricApiTest {

    private static final String ALIAS = "metric-api";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir Path temp;

    private HttpServer server;
    private GraphWorkspaceStore store;
    private GraphUiSession session;
    private String origin;
    private String previousHome;

    @BeforeEach
    void setUp() throws Exception {
        previousHome = System.getProperty("sqlcli.home");
        System.setProperty("sqlcli.home", temp.resolve("home").toString());
        store = new GraphWorkspaceStore(temp);

        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create(ALIAS, "app", "orders", GraphActor.extractor);
        orders.getColumns().add(ColumnWorkspaceNode.create("id"));
        orders.getColumns().add(ColumnWorkspaceNode.create("amount"));
        orders.getColumns().add(ColumnWorkspaceNode.create("channel"));
        orders.getColumns().add(ColumnWorkspaceNode.create("created_at"));
        workspace.getTables().put(orders.getId(), orders);
        store.save(workspace);

        session = new GraphUiSession(ALIAS, false);
        WorkspaceMutationController controller = new WorkspaceMutationController(
                workspace, session, new JsonHttpSupport(), null, store);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/metrics", controller);
        server.start();
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
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

    /** 人在 UI 里定义 → 图谱里落一条 verified 的指标 → 展开成可执行 SQL。这条就是那个验收链路。 */
    @Test
    void humanDefinesMetricThenExpandsToSql() throws Exception {
        JsonNode created = post(gmvBody(revision()), 200);
        assertEquals(GraphIds.metricId(ALIAS, "gmv_paid"), created.get("metricId").asText());

        MetricRecord saved = store.load(ALIAS).getMetrics().get(GraphIds.metricId(ALIAS, "gmv_paid"));
        assertEquals("SUM(app.orders.amount)", saved.getExpression());
        assertEquals("status IN (2,3)", saved.getFilters());
        // UI 写入的 actor 是 human：人声明的口径就是权威口径，不是等人发布的候选
        assertEquals(GraphStatus.verified, saved.getStatus());
        assertEquals(Boolean.TRUE, saved.getVerified());
        // 列引用按 schema.table.column 收，落库存的是列 id——与 CLI 同一份解析
        assertEquals(GraphIds.columnId(ALIAS, "app", "orders", "created_at"), saved.getGrain().getTimeColumn());
        assertEquals(java.util.List.of(GraphIds.columnId(ALIAS, "app", "orders", "channel")),
                saved.getDimensions());

        JsonNode listed = get("/api/metrics", 200);
        assertEquals(1, listed.get("metrics").size());
        assertEquals("gmv_paid", listed.get("metrics").get(0).get("name").asText());

        String sql = get("/api/metrics/gmv_paid/sql?grain=day", 200).get("sql").asText();
        // expression 原样进 SELECT，展开层不改写它（MetricRecord#expression 的类注释写明了
        // 「是否可执行由消费方解析」）——被引号包起来的只有展开层自己拼的表名与时间列
        assertTrue(sql.contains("SUM(app.orders.amount)"), sql);
        assertTrue(sql.contains("DATE(`app`.`orders`.`created_at`)"), sql);
        assertTrue(sql.contains("status IN (2,3)"), sql);
        assertTrue(sql.toUpperCase(java.util.Locale.ROOT).contains("GROUP BY"), sql);
    }

    /**
     * 拼错的维度列必须当场 400。吞成 null 的后果是展开出来的 SQL 少一个 GROUP BY 列——
     * 不报错、有结果、数是错的。
     */
    @Test
    void unknownColumnIsRejectedWithTheOffendingValue() throws Exception {
        Map<String, Object> body = gmvBody(revision());
        body.put("dimensions", java.util.List.of("app.orders.channl"));

        JsonNode error = post(body, 400);

        assertTrue(error.get("message").asText().contains("app.orders.channl"), error.toString());
        assertTrue(store.load(ALIAS).getMetrics().isEmpty(), "定义失败就不该留下半条指标");
    }

    /** 没有 expression 的 metric 展开必炸，在定义的时候就拦住，而不是等消费方才发现。 */
    @Test
    void metricWithoutExpressionIsRejectedAtDefinitionTime() throws Exception {
        Map<String, Object> body = gmvBody(revision());
        body.remove("expression");

        JsonNode error = post(body, 400);
        assertTrue(error.get("message").asText().contains("expression"), error.toString());
    }

    /** 请求的粒度不在声明范围内时，错误原样带出来——那句话是写给定义指标的人看的。 */
    @Test
    void undeclaredGrainReportsTheDeclaredRange() throws Exception {
        post(gmvBody(revision()), 200);

        JsonNode error = get("/api/metrics/gmv_paid/sql?grain=year", 400);
        assertTrue(error.get("message").asText().contains("year"), error.toString());
    }

    @Test
    void writeWithoutSessionTokenIsForbidden() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(origin + "/api/metrics"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(gmvBody(revision()))))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, response.statusCode());
    }

    // ---------------------------------------------------------------- 辅助

    private Map<String, Object> gmvBody(long revision) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("name", "gmv_paid");
        body.put("businessName", "已支付GMV");
        body.put("expression", "SUM(app.orders.amount)");
        body.put("filters", "status IN (2,3)");
        body.put("grainColumn", "app.orders.created_at");
        body.put("grains", java.util.List.of("day", "month"));
        body.put("dimensions", java.util.List.of("app.orders.channel"));
        body.put("expectedRevision", revision);
        return body;
    }

    private long revision() throws Exception {
        return store.load(ALIAS).getManifest().getRevision();
    }

    private JsonNode post(Map<String, Object> body, int expectedStatus) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(origin + "/api/metrics"))
                        .header("Content-Type", "application/json")
                        .header("X-Session-Token", session.getSessionToken())
                        .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedStatus, response.statusCode(), response.body());
        return MAPPER.readTree(response.body());
    }

    private JsonNode get(String path, int expectedStatus) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(origin + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedStatus, response.statusCode(), response.body());
        return MAPPER.readTree(response.body());
    }
}
