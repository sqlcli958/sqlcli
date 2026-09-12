package com.sqlcli.graph.ui;

import com.sqlcli.graph.workspace.*;
import com.sqlcli.graph.ui.api.WorkspaceQueryController;
import com.sqlcli.graph.ui.dto.*;
import com.sqlcli.graph.ui.service.GraphViewService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the graph UI server infrastructure.
 * Uses real HTTP server for integration tests and unit tests for service/logic layer.
 */
class GraphUiServerTest {

    private static HttpServer testServer;
    private static int testPort;
    private static GraphWorkspace testWorkspace;
    private static GraphUiSession testSession;
    private static JsonHttpSupport jsonSupport;
    private static final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void startTestServer() throws IOException {
        testWorkspace = createTestWorkspace();
        testSession = new GraphUiSession("test", true);
        jsonSupport = new JsonHttpSupport();

        WorkspaceQueryController controller = new WorkspaceQueryController(
                testWorkspace, testSession, jsonSupport, null);
        StaticResourceHandler staticHandler = new StaticResourceHandler();

        testServer = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        testServer.createContext("/api/session", controller);
        testServer.createContext("/api/workspace", controller);
        testServer.createContext("/api/schemas", controller);
        testServer.createContext("/api/tables", controller);
        testServer.createContext("/api/terms", controller);
        testServer.createContext("/api/search", controller);
        testServer.createContext("/api/graph", controller);
        testServer.createContext("/", staticHandler);
        testServer.setExecutor(null);
        testServer.start();
        testPort = testServer.getAddress().getPort();
    }

    @AfterAll
    static void stopTestServer() {
        if (testServer != null) {
            testServer.stop(0);
        }
    }

    // --- HTTP Integration Tests ---

    @Test
    void sessionEndpointReturnsSessionInfo() throws IOException {
        String body = httpGet("/api/session");
        assertTrue(body.contains("\"alias\":\"test\""));
        assertTrue(body.contains("\"readOnly\":true"));
        assertTrue(body.contains("\"revision\""));
        assertTrue(body.contains("\"capabilities\""));
    }

    @Test
    void termsEndpointListsTermsWithMappingsAndCandidateStatus() throws IOException {
        JsonNode body = mapper.readTree(httpGet("/api/terms"));
        assertEquals(1, body.get("total").asInt());
        JsonNode term = body.get("terms").get(0);
        assertEquals("buyer", term.get("name").asText());
        assertEquals("买家", term.get("displayName").asText());
        assertEquals("购买人", term.get("aliases").get(0).asText());
        assertEquals("candidate", term.get("status").asText());
        assertEquals(1, term.get("mappedTargets").size());
        assertTrue(term.get("mappedTargets").get(0).asText().contains("orders"));
    }

    @Test
    void workspaceEndpointReturnsSummary() throws IOException {
        String body = httpGet("/api/workspace");
        assertTrue(body.contains("\"alias\":\"test\""));
        assertTrue(body.contains("\"modelVersion\""));
        assertTrue(body.contains("\"storageVersion\""));
        assertTrue(body.contains("\"stats\""));
    }

    @Test
    void workspaceStatsEndpoint() throws IOException {
        String body = httpGet("/api/workspace/stats");
        assertTrue(body.contains("\"schemas\""));
        assertTrue(body.contains("\"tables\""));
        assertTrue(body.contains("\"columns\""));
        assertTrue(body.contains("\"relations\""));
    }

    @Test
    void schemasEndpointReturnsSchemaList() throws IOException {
        String body = httpGet("/api/schemas");
        assertTrue(body.contains("\"trade\""));
    }

    @Test
    void tablesEndpointWithPagination() throws IOException {
        String body = httpGet("/api/tables?schema=trade&offset=0&limit=10");
        assertTrue(body.contains("\"total\""));
        assertTrue(body.contains("\"items\""));
        assertTrue(body.contains("\"orders\""));
    }

    @Test
    void tablesEndpointWithQuery() throws IOException {
        String body = httpGet("/api/tables?q=user&offset=0&limit=10");
        assertTrue(body.contains("\"items\""));
    }

    @Test
    void tableDetailEndpoint() throws IOException {
        String body = httpGet("/api/tables/trade.orders");
        assertTrue(body.contains("\"table\""));
        assertTrue(body.contains("\"columns\""));
        assertTrue(body.contains("\"inEdges\""));
        assertTrue(body.contains("\"outEdges\""));
    }

    @Test
    void tableDetailNotFound() throws IOException {
        HttpURLConnection conn = connect("/api/tables/nonexistent");
        assertEquals(404, conn.getResponseCode());
    }

    @Test
    void searchEndpointWithFallback() throws IOException {
        String body = httpGet("/api/search?q=order&type=table&limit=10");
        assertTrue(body.contains("\"results\""));
        assertTrue(body.contains("\"indexStatus\":\"missing\""));
    }

    @Test
    void graphEndpoint() throws IOException {
        String body = httpGet("/api/graph?schema=trade&maxNodes=100&maxEdges=200");
        assertTrue(body.contains("\"revision\""));
        assertTrue(body.contains("\"nodes\""));
        assertTrue(body.contains("\"edges\""));
        assertTrue(body.contains("\"stats\""));
    }

    @Test
    void staticResourcePathTraversal() throws IOException {
        HttpURLConnection conn = connect("/../etc/passwd");
        assertEquals(403, conn.getResponseCode());
    }

    @Test
    void staticResourceNotFound() throws IOException {
        HttpURLConnection conn = connect("/nonexistent-page");
        // With frontend on classpath, SPA fallback returns index.html (200)
        // Without frontend, returns 404
        int code = conn.getResponseCode();
        assertTrue(code == 200 || code == 404,
                "Expected 200 (SPA fallback) or 404 but got " + code);
    }

    @Test
    void staticResourceRejectsPost() throws IOException {
        URL url = new URL("http://127.0.0.1:" + testPort + "/index.html");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        assertEquals(405, conn.getResponseCode());
    }

    // --- Unit Tests: GraphUiSession ---

    @Test
    void sessionTokenIsUUID() {
        GraphUiSession session = new GraphUiSession("test", true);
        assertNotNull(session.getSessionToken());
        assertTrue(session.getSessionToken().matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
    }

    /**
     * 语义决策：readOnly 指数据库只读，图谱工作区是本地元数据，编辑不受影响。
     * 只读数据源的图谱写请求（带对 token 与 Origin）必须照常放行——
     * SQL 写语句由 SqlTaskModule 按别名配置另行拦截，不走这条校验。
     */
    @Test
    void readOnlySessionStillAllowsGraphWrites() {
        GraphUiSession session = new GraphUiSession("test", true);
        session.setAllowedOrigin("http://localhost:8080");
        assertTrue(session.isReadOnly());
        assertTrue(session.validateWriteRequest(session.getSessionToken(), "http://localhost:8080"));
        // token 与 Origin 的校验不因 readOnly 放松
        assertFalse(session.validateWriteRequest("wrong-token", "http://localhost:8080"));
        assertFalse(session.validateWriteRequest(session.getSessionToken(), "http://evil.com"));
    }

    @Test
    void writeSessionRequiresCorrectToken() {
        GraphUiSession session = new GraphUiSession("test", false);
        session.setAllowedOrigin("http://127.0.0.1:8080");
        assertFalse(session.validateWriteRequest("wrong-token", "http://127.0.0.1:8080"));
        assertTrue(session.validateWriteRequest(session.getSessionToken(), "http://127.0.0.1:8080"));
    }

    @Test
    void writeSessionAcceptsAnyLoopbackSpellingButNothingElse() {
        GraphUiSession session = new GraphUiSession("test", false);
        session.setAllowedOrigin("http://127.0.0.1:8080");
        assertFalse(session.validateWriteRequest(session.getSessionToken(), "http://evil.com"));
        assertTrue(session.validateWriteRequest(session.getSessionToken(), "http://127.0.0.1:8080"));
        assertFalse(session.validateWriteRequest(session.getSessionToken(), "http://127.0.0.1:8081"));
        assertFalse(session.validateWriteRequest(session.getSessionToken(), "https://127.0.0.1:8080"));
        // localhost 和 127.0.0.1 是同一台服务器的两种写法，判成跨域会让从另一种
        // 地址打开 UI 的用户所有写操作 403。等价范围只到回环地址为止：
        assertTrue(session.validateWriteRequest(session.getSessionToken(), "http://localhost:8080"));
        assertFalse(session.validateWriteRequest(session.getSessionToken(), "http://127.0.0.2:8080"));
        assertFalse(session.validateWriteRequest(session.getSessionToken(), "http://127.0.0.1.evil.com:8080"));
        assertFalse(session.validateWriteRequest(session.getSessionToken(), "not a uri"));
    }

    // --- Unit Tests: GraphViewService ---

    @Test
    void graphViewBuildView() {
        GraphWorkspace workspace = createTestWorkspace();
        GraphViewService service = new GraphViewService(workspace);

        GraphViewDto dto = service.buildView(null, null, 1, null, false, 500, 1000);
        assertNotNull(dto);
        assertEquals(workspace.getManifest().getRevision(), dto.getRevision());
        assertFalse(dto.isTruncated());
        assertNotNull(dto.getNodes());
        assertNotNull(dto.getEdges());
        assertNotNull(dto.getStats());
    }

    @Test
    void graphViewFilterBySchema() {
        GraphWorkspace workspace = createTestWorkspace();
        GraphViewService service = new GraphViewService(workspace);

        GraphViewDto dto = service.buildView("trade", null, 1, null, false, 500, 1000);
        for (GraphNodeDto node : dto.getNodes()) {
            assertEquals("trade", node.getSchema());
        }
    }

    @Test
    void graphViewExpandFromSeed() {
        GraphWorkspace workspace = createTestWorkspace();
        GraphViewService service = new GraphViewService(workspace);

        GraphViewDto dto = service.buildView(null, "trade.orders", 1, null, false, 500, 1000);
        assertTrue(dto.getNodes().size() >= 1);
    }

    @Test
    void graphViewMaxNodesLimit() {
        GraphWorkspace workspace = createTestWorkspace();
        GraphViewService service = new GraphViewService(workspace);

        GraphViewDto dto = service.buildView(null, null, 1, null, false, 1, 10);
        assertTrue(dto.getStats().getReturnedNodes() <= 1);
    }

    @Test
    void graphViewExcludeIsolated() {
        GraphWorkspace workspace = createTestWorkspace();
        TableWorkspaceNode isolated = TableWorkspaceNode.create("test", "trade", "logs", GraphActor.extractor);
        workspace.getTables().put(isolated.getId(), isolated);

        GraphViewService service = new GraphViewService(workspace);
        GraphViewDto dto = service.buildView(null, null, 1, null, false, 500, 1000);
        for (GraphNodeDto node : dto.getNodes()) {
            assertFalse(node.getId().contains("logs"));
        }
    }

    @Test
    void graphViewIncludeIsolated() {
        GraphWorkspace workspace = createTestWorkspace();
        TableWorkspaceNode isolated = TableWorkspaceNode.create("test", "trade", "logs", GraphActor.extractor);
        workspace.getTables().put(isolated.getId(), isolated);

        GraphViewService service = new GraphViewService(workspace);
        GraphViewDto dto = service.buildView(null, null, 1, null, true, 500, 1000);
        assertTrue(dto.getNodes().stream().anyMatch(n -> n.getId().contains("logs")));
    }

    @Test
    void graphViewFilterByRelationType() {
        GraphWorkspace workspace = createTestWorkspace();
        GraphViewService service = new GraphViewService(workspace);

        GraphViewDto dto = service.buildView(null, null, 1, "join_observed", false, 500, 1000);
        assertNotNull(dto);
        for (GraphEdgeDto edge : dto.getEdges()) {
            assertEquals("join_observed", edge.getType());
        }
    }

    // --- Unit Tests: GraphUiOptions ---

    @Test
    void graphUiOptionsDefaults() {
        GraphUiOptions options = new GraphUiOptions();
        assertEquals(9999, options.getPort());
        assertEquals("127.0.0.1", options.getHost());
        assertFalse(options.isNoOpen());
        assertNull(options.getAlias());
    }

    // --- Unit Tests: ApiError ---

    @Test
    void apiErrorFactoryMethods() {
        assertEquals("NOT_FOUND", ApiError.notFound("x").getCode());
        assertEquals("BAD_REQUEST", ApiError.badRequest("x").getCode());
        assertEquals("UNAUTHORIZED", ApiError.unauthorized("x").getCode());
        assertEquals("INTERNAL_ERROR", ApiError.internal("x").getCode());
    }

    // --- Unit Tests: JsonHttpSupport ---

    @Test
    void jsonHttpSupportGetIntParam() {
        JsonHttpSupport json = new JsonHttpSupport();
        assertEquals(3, json.getIntParam(Map.of("depth", "3"), "depth", 1));
        assertEquals(1, json.getIntParam(Map.of(), "depth", 1));
    }

    // --- Helper methods ---

    private static String httpGet(String path) throws IOException {
        HttpURLConnection conn = connect(path);
        assertEquals(200, conn.getResponseCode());
        return new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static HttpURLConnection connect(String path) throws IOException {
        URL url = new URL("http://127.0.0.1:" + testPort + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.connect();
        return conn;
    }

    private static GraphWorkspace createTestWorkspace() {
        GraphWorkspace workspace = GraphWorkspace.create("test", "mysql");

        SchemaWorkspaceNode schema = SchemaWorkspaceNode.create("test", "trade", GraphActor.system);
        workspace.getSchemas().put(schema.getId(), schema);

        TableWorkspaceNode orders = TableWorkspaceNode.create("test", "trade", "orders", GraphActor.extractor);
        orders.setComment("订单主表");
        ColumnWorkspaceNode orderId = ColumnWorkspaceNode.create("id");
        orderId.setOrdinal(1);
        orderId.setPrimaryKey(true);
        ColumnDataType orderIdType = new ColumnDataType();
        orderIdType.setRaw("int");
        orderId.setDataType(orderIdType);
        ColumnWorkspaceNode userId = ColumnWorkspaceNode.create("user_id");
        userId.setOrdinal(2);
        ColumnDataType userIdType = new ColumnDataType();
        userIdType.setRaw("int");
        userId.setDataType(userIdType);
        orders.getColumns().addAll(List.of(orderId, userId));
        workspace.getTables().put(orders.getId(), orders);

        TableWorkspaceNode users = TableWorkspaceNode.create("test", "trade", "users", GraphActor.extractor);
        users.setComment("用户表");
        ColumnWorkspaceNode userIdCol = ColumnWorkspaceNode.create("id");
        userIdCol.setOrdinal(1);
        userIdCol.setPrimaryKey(true);
        ColumnDataType userIdColType = new ColumnDataType();
        userIdColType.setRaw("int");
        userIdCol.setDataType(userIdColType);
        users.getColumns().add(userIdCol);
        workspace.getTables().put(users.getId(), users);

        RelationWorkspaceEdge fk = RelationWorkspaceEdge.create("test",
                RelationType.join_observed,
                userId.computeId("test", "trade", "orders"),
                userIdCol.computeId("test", "trade", "users"),
                GraphActor.agent);
        fk.setConfidence(0.8);
        workspace.getRelations().add(fk);

        // 术语子视图的数据：一个 agent 写入的候选术语，映射到 orders 表
        TermWorkspaceNode buyer = TermWorkspaceNode.create("test", "buyer", GraphActor.agent);
        buyer.setDisplayName("买家");
        buyer.getAliases().add("购买人");
        workspace.getTerms().put(buyer.getId(), buyer);
        workspace.getRelations().add(RelationWorkspaceEdge.create("test",
                RelationType.term_mapping, buyer.getId(), orders.getId(), GraphActor.agent));

        workspace.getManifest().getStats().setSchemas(1);
        workspace.getManifest().getStats().setTables(2);
        workspace.getManifest().getStats().setColumns(3);
        workspace.getManifest().getStats().setRelations(1);

        return workspace;
    }
}
