package com.sqlcli.graph.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcli.config.SettingsConfig;
import com.sqlcli.graph.workspace.*;
import com.sqlcli.graph.workspace.index.WorkspaceIndexer;
import com.sqlcli.graph.workspace.index.WorkspaceIndexStore;
import com.sqlcli.graph.ui.GraphUiServer;
import com.sqlcli.graph.ui.GraphUiOptions;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端测试：启动 UI 服务器，通过 HTTP 测试所有功能。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GraphUiE2eTest {

    private static final String ALIAS = "e2e-test";
    private GraphUiServer server;
    private String baseUrl;
    private String sessionToken;
    private HttpClient httpClient;
    private ObjectMapper jsonMapper;
    private Path tempDir;
    private long currentRevision;

    private static void restoreProperty(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    @BeforeAll
    void setUp() throws Exception {
        jsonMapper = new ObjectMapper();
        jsonMapper.findAndRegisterModules();

        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        tempDir = Files.createTempDirectory("sql-cli-e2e-");

        // 服务端有几处走的是**默认构造**的 store（`new RunStateStore()` /
        // `new GraphWorkspaceStore()`），只给下面那个 store 传 tempDir 拦不住它们。
        // 不设这两个系统属性，这个测试会往真实的 ~/.sql-cli 写东西：
        // 2026-08-27 就在用户的运行库里留下了 4 条 e2e-test 的待审批，
        // 而工作区随测试删掉了——那 4 条从此批也批不了、拒也拒不掉。
        previousHome = System.getProperty("sqlcli.home");
        previousConfigRoot = System.getProperty(SettingsConfig.CONFIG_ROOT_PROPERTY);
        System.setProperty("sqlcli.home", tempDir.resolve("home").toString());
        System.setProperty(SettingsConfig.CONFIG_ROOT_PROPERTY, tempDir.resolve("config-root").toString());

        // 创建测试工作区
        createTestFixture(tempDir.resolve(ALIAS));

        // 启动服务器 (port 0 = 自动选择)
        GraphUiOptions options = new GraphUiOptions();
        options.setAlias(ALIAS);
        options.setPort(0);
        options.setHost("127.0.0.1");
        options.setNoOpen(true);

        GraphWorkspaceStore store = new GraphWorkspaceStore(tempDir);
        GraphWorkspace workspace = store.load(ALIAS);

        GraphUiSession session = new GraphUiSession(ALIAS, false);
        session.setWorkspaceRevision(workspace.getManifest().getRevision());
        sessionToken = session.getSessionToken();

        JsonHttpSupport jsonSupport = new JsonHttpSupport();
        WorkspaceIndexStore indexStore = new WorkspaceIndexStore(tempDir);

        server = new GraphUiServer(options, workspace, session, jsonSupport, indexStore, store);
        int actualPort = server.start();
        baseUrl = "http://127.0.0.1:" + actualPort;
        currentRevision = workspace.getManifest().getRevision();
    }

    private String previousHome;
    private String previousConfigRoot;

    @AfterAll
    void tearDown() {
        restoreProperty("sqlcli.home", previousHome);
        restoreProperty(SettingsConfig.CONFIG_ROOT_PROPERTY, previousConfigRoot);
        if (server != null) {
            server.stop();
        }
        // Cleanup temp dir
        try {
            Files.walk(tempDir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
        System.clearProperty("sqlcli.graph.path");
    }

    // ---- Helper methods ----

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Accept", "application/json")
                .header("X-Session-Token", sessionToken)
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Session-Token", sessionToken)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> patch(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Session-Token", sessionToken)
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Accept", "application/json")
                .header("X-Session-Token", sessionToken)
                .DELETE()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void createTestFixture(Path workspaceRoot) throws IOException {
        GraphWorkspaceStore store = new GraphWorkspaceStore(workspaceRoot.getParent());

        WorkspaceManifest manifest = WorkspaceManifest.create(ALIAS);
        manifest.setRevision(1);

        DataSourceNode dataSource = DataSourceNode.create(ALIAS, "mysql", GraphActor.system);

        // Schema: trade
        SchemaWorkspaceNode tradeSchema = SchemaWorkspaceNode.create(ALIAS, "trade", GraphActor.extractor);
        // Schema: user
        SchemaWorkspaceNode userSchema = SchemaWorkspaceNode.create(ALIAS, "user", GraphActor.extractor);

        // Table: trade.orders
        TableWorkspaceNode orders = TableWorkspaceNode.create(ALIAS, "trade", "orders", GraphActor.extractor);
        orders.setComment("订单表");
        orders.setTableType(TableType.base_table);
        addColumn(orders, "id", "BIGINT", false, true, 1);
        addColumn(orders, "buyer_id", "BIGINT", false, false, 2);
        addColumn(orders, "amount", "DECIMAL", false, false, 3);
        addColumn(orders, "status", "VARCHAR", true, false, 4);

        // Table: trade.order_items
        TableWorkspaceNode orderItems = TableWorkspaceNode.create(ALIAS, "trade", "order_items", GraphActor.extractor);
        orderItems.setComment("订单明细");
        orderItems.setTableType(TableType.base_table);
        addColumn(orderItems, "id", "BIGINT", false, true, 1);
        addColumn(orderItems, "order_id", "BIGINT", false, false, 2);
        addColumn(orderItems, "product_id", "BIGINT", false, false, 3);
        addColumn(orderItems, "quantity", "INT", false, false, 4);

        // Table: user.users
        TableWorkspaceNode users = TableWorkspaceNode.create(ALIAS, "user", "users", GraphActor.extractor);
        users.setComment("用户表");
        users.setTableType(TableType.base_table);
        addColumn(users, "id", "BIGINT", false, true, 1);
        addColumn(users, "name", "VARCHAR", false, false, 2);
        addColumn(users, "email", "VARCHAR", true, false, 3);

        // Table: user.addresses
        TableWorkspaceNode addresses = TableWorkspaceNode.create(ALIAS, "user", "addresses", GraphActor.extractor);
        addresses.setComment("地址表");
        addresses.setTableType(TableType.base_table);
        addColumn(addresses, "id", "BIGINT", false, true, 1);
        addColumn(addresses, "user_id", "BIGINT", false, false, 2);
        addColumn(addresses, "city", "VARCHAR", true, false, 3);

        // Isolated table: trade.audit_log
        TableWorkspaceNode auditLog = TableWorkspaceNode.create(ALIAS, "trade", "audit_log", GraphActor.extractor);
        auditLog.setComment("审计日志");
        auditLog.setTableType(TableType.base_table);
        addColumn(auditLog, "id", "BIGINT", false, true, 1);
        addColumn(auditLog, "action", "VARCHAR", false, false, 2);

        // Relations
        // declared FK: orders.buyer_id -> users.id
        RelationWorkspaceEdge fkBuyer = RelationWorkspaceEdge.create(ALIAS,
                RelationType.foreign_key,
                "column:" + ALIAS + ":trade.orders.buyer_id",
                "column:" + ALIAS + ":user.users.id",
                GraphActor.extractor);
        fkBuyer.setCardinality(RelationCardinality.many_to_one);
        fkBuyer.setConfidence(1.0);
        fkBuyer.setVerified(true);

        // declared FK: order_items.order_id -> orders.id
        RelationWorkspaceEdge fkOrder = RelationWorkspaceEdge.create(ALIAS,
                RelationType.foreign_key,
                "column:" + ALIAS + ":trade.order_items.order_id",
                "column:" + ALIAS + ":trade.orders.id",
                GraphActor.extractor);
        fkOrder.setCardinality(RelationCardinality.many_to_one);
        fkOrder.setConfidence(1.0);
        fkOrder.setVerified(true);

        // inferred: addresses.user_id -> users.id
        RelationWorkspaceEdge inferredAddr = RelationWorkspaceEdge.create(ALIAS,
                RelationType.join_observed,
                "column:" + ALIAS + ":user.addresses.user_id",
                "column:" + ALIAS + ":user.users.id",
                GraphActor.agent);
        inferredAddr.setCardinality(RelationCardinality.many_to_one);
        inferredAddr.setConfidence(0.8);
        inferredAddr.setVerified(false);

        // lineage: orders.id -> order_items.order_id
        RelationWorkspaceEdge lineage = RelationWorkspaceEdge.create(ALIAS,
                RelationType.join_observed,
                "column:" + ALIAS + ":trade.orders.id",
                "column:" + ALIAS + ":trade.order_items.order_id",
                GraphActor.agent);
        lineage.setConfidence(0.9);
        lineage.setVerified(false);

        GraphWorkspace workspace = new GraphWorkspace();
        workspace.setManifest(manifest);
        workspace.setDataSource(dataSource);
        workspace.getSchemas().put(tradeSchema.getId(), tradeSchema);
        workspace.getSchemas().put(userSchema.getId(), userSchema);
        workspace.getTables().put(orders.getId(), orders);
        workspace.getTables().put(orderItems.getId(), orderItems);
        workspace.getTables().put(users.getId(), users);
        workspace.getTables().put(addresses.getId(), addresses);
        workspace.getTables().put(auditLog.getId(), auditLog);
        workspace.getRelations().add(fkBuyer);
        workspace.getRelations().add(fkOrder);
        workspace.getRelations().add(inferredAddr);
        workspace.getRelations().add(lineage);

        store.save(workspace);

        // Build index
        WorkspaceIndexer indexer = new WorkspaceIndexer();
        WorkspaceIndexStore indexStore = new WorkspaceIndexStore(workspaceRoot.getParent());
        indexStore.save(ALIAS, indexer.rebuild(workspace));
    }

    private void addColumn(TableWorkspaceNode table, String name, String rawType,
                           boolean nullable, boolean primaryKey, int ordinal) {
        ColumnWorkspaceNode col = new ColumnWorkspaceNode();
        col.setName(name);
        col.setDataType(new ColumnDataType());
        col.getDataType().setRaw(rawType);
        col.setNullable(nullable);
        col.setPrimaryKey(primaryKey);
        col.setOrdinal(ordinal);
        col.setConfidence(0.7);
        col.setVerified(false);
        table.getColumns().add(col);
    }

    // ---- Test cases ----

    @Test
    @Order(1)
    void test01_SessionApi() throws Exception {
        HttpResponse<String> resp = get("/api/session");
        assertEquals(200, resp.statusCode());
        JsonNode json = jsonMapper.readTree(resp.body());
        assertEquals(ALIAS, json.get("alias").asText());
        assertTrue(json.has("revision"));
        assertTrue(json.has("capabilities"));
    }

    @Test
    @Order(2)
    void test02_WorkspaceApi() throws Exception {
        HttpResponse<String> resp = get("/api/workspace");
        assertEquals(200, resp.statusCode());
        JsonNode json = jsonMapper.readTree(resp.body());
        assertTrue(json.has("alias"));
        assertTrue(json.has("revision"));
    }

    @Test
    @Order(3)
    void test03_WorkspaceStats() throws Exception {
        HttpResponse<String> resp = get("/api/workspace/stats");
        assertEquals(200, resp.statusCode());
        JsonNode json = jsonMapper.readTree(resp.body());
        assertTrue(json.has("tables"));
        assertEquals(5, json.get("tables").asInt());
    }

    @Test
    @Order(4)
    void test04_SchemasApi() throws Exception {
        HttpResponse<String> resp = get("/api/schemas");
        assertEquals(200, resp.statusCode());
        JsonNode json = jsonMapper.readTree(resp.body());
        assertTrue(json.isArray());
        assertTrue(json.size() >= 2);
    }

    @Test
    @Order(5)
    void test05_TablesApi() throws Exception {
        HttpResponse<String> resp = get("/api/tables?limit=100");
        assertEquals(200, resp.statusCode());
        JsonNode json = jsonMapper.readTree(resp.body());
        // Should be an array or paginated object
        if (json.isArray()) {
            assertTrue(json.size() >= 5);
        } else {
            assertTrue(json.has("items") || json.has("tables"));
        }
    }

    @Test
    @Order(6)
    void test06_TableDetail() throws Exception {
        String tableId = "table:" + ALIAS + ":trade.orders";
        HttpResponse<String> resp = get("/api/tables/" + encode(tableId));
        assertEquals(200, resp.statusCode());
        JsonNode json = jsonMapper.readTree(resp.body());
        assertTrue(json.has("name") || json.has("columns"));
    }

    @Test
    @Order(7)
    void test07_SearchTable() throws Exception {
        HttpResponse<String> resp = get("/api/search?q=orders&type=table&limit=10");
        assertEquals(200, resp.statusCode());
        JsonNode json = jsonMapper.readTree(resp.body());
        assertTrue(json.isArray() || json.has("results"));
    }

    @Test
    @Order(8)
    void test08_SearchColumn() throws Exception {
        HttpResponse<String> resp = get("/api/search?q=buyer_id&type=column&limit=10");
        assertEquals(200, resp.statusCode());
    }

    @Test
    @Order(9)
    void test09_GraphApi() throws Exception {
        String tableId = "table:" + ALIAS + ":trade.orders";
        HttpResponse<String> resp = get("/api/graph?table=" + encode(tableId) + "&depth=1");
        assertEquals(200, resp.statusCode());
        JsonNode json = jsonMapper.readTree(resp.body());
        assertTrue(json.has("nodes") || json.has("edges"));
    }

    @Test
    @Order(10)
    void test10_PatchTable() throws Exception {
        String tableId = "table:" + ALIAS + ":trade.orders";
        String body = jsonMapper.writeValueAsString(Map.of(
                "expectedRevision", currentRevision,
                "patch", Map.of("description", "销售订单表"),
                "reason", "E2E测试修改表描述"
        ));
        HttpResponse<String> resp = patch("/api/tables/" + encode(tableId), body);
        assertEquals(200, resp.statusCode(), "test10 body: " + resp.body());
        JsonNode json = jsonMapper.readTree(resp.body());
        assertTrue(json.has("newRevision"), "response should contain newRevision: " + resp.body());
        currentRevision = json.get("newRevision").asLong();

        // comment 是数据库注释镜像，只由导入写，UI 没有写入路径
        String rejected = jsonMapper.writeValueAsString(Map.of(
                "expectedRevision", currentRevision,
                "patch", Map.of("comment", "手改注释"),
                "reason", "应被拒绝"
        ));
        HttpResponse<String> rejectResp = patch("/api/tables/" + encode(tableId), rejected);
        assertEquals(400, rejectResp.statusCode(), "table comment must not be editable: " + rejectResp.body());
    }

    @Test
    @Order(11)
    void test11_PatchColumn() throws Exception {
        String tableId = "table:" + ALIAS + ":trade.orders";
        String body = jsonMapper.writeValueAsString(Map.of(
                "expectedRevision", currentRevision,
                "patch", Map.of("businessName", "买家ID"),
                "reason", "E2E测试修改字段业务描述"
        ));
        HttpResponse<String> resp = patch("/api/tables/" + encode(tableId) + "/columns/buyer_id", body);
        assertEquals(200, resp.statusCode(), "test11 body: " + resp.body());
        JsonNode json = jsonMapper.readTree(resp.body());
        assertTrue(json.has("newRevision"), "response should contain newRevision: " + resp.body());
        currentRevision = json.get("newRevision").asLong();

        // 字段注释来自数据库，导入会覆盖，因此不开放编辑
        String rejected = jsonMapper.writeValueAsString(Map.of(
                "expectedRevision", currentRevision,
                "patch", Map.of("comment", "手改注释"),
                "reason", "应被拒绝"
        ));
        HttpResponse<String> rejectResp =
                patch("/api/tables/" + encode(tableId) + "/columns/buyer_id", rejected);
        assertEquals(400, rejectResp.statusCode(), "column comment must not be editable: " + rejectResp.body());
    }

    @Test
    @Order(12)
    void test12_CreateRelation() throws Exception {
        String body = jsonMapper.writeValueAsString(Map.of(
                "expectedRevision", currentRevision,
                "type", "join_observed",
                "from", "column:" + ALIAS + ":trade.order_items.product_id",
                "to", "column:" + ALIAS + ":trade.orders.id",
                "cardinality", "many_to_one",
                "joinExpression", "trade.order_items.product_id = trade.orders.id",
                "confidence", 0.7,
                "verified", false,
                "reason", "E2E测试创建关系"
        ));
        HttpResponse<String> resp = post("/api/relations", body);
        assertEquals(200, resp.statusCode(), "test12 body: " + resp.body());
        JsonNode json = jsonMapper.readTree(resp.body());
        assertTrue(json.has("newRevision"), "response should contain newRevision: " + resp.body());
        currentRevision = json.get("newRevision").asLong();

        HttpResponse<String> graphResp = get("/api/graph?includeIsolated=true&maxNodes=500&maxEdges=1000");
        assertEquals(200, graphResp.statusCode(), "graph should refresh after relation creation: " + graphResp.body());
        assertTrue(graphResp.body().contains("join_observed:column_" + ALIAS + "_trade.order_items.product_id"),
                "new relation should be visible in graph response: " + graphResp.body());
        assertTrue(graphResp.body().contains("trade.order_items.product_id"),
                "new relation field pair should be visible in graph response: " + graphResp.body());
    }

    @Test
    @Order(13)
    void test13_DeclaredFkProtected() throws Exception {
        // Try to delete declared FK - should fail
        // Find the declared FK relation ID
        HttpResponse<String> graphResp = get("/api/graph?depth=2");
        // We know the declared FK is created by GraphIds.relationId which sanitizes refs
        String fromRef = "column:" + ALIAS + ":trade.orders.buyer_id";
        String toRef = "column:" + ALIAS + ":user.users.id";
        String relationId = GraphIds.relationId(ALIAS, RelationType.foreign_key, fromRef, toRef);
        HttpResponse<String> resp = delete("/api/relations/" + encode(relationId));
        // Should be 403 or 400
        assertTrue(resp.statusCode() == 403 || resp.statusCode() == 400,
                "Expected 403/400 but got " + resp.statusCode() + ": " + resp.body());
    }

    @Test
    @Order(14)
    void test14_RevisionConflict() throws Exception {
        String tableId = "table:" + ALIAS + ":trade.orders";
        // Use a stale expectedRevision (currentRevision - 1, which is guaranteed to be outdated)
        long staleRevision = currentRevision - 1;
        String body = jsonMapper.writeValueAsString(Map.of(
                "expectedRevision", staleRevision,  // stale revision
                "patch", Map.of("description", "冲突测试"),
                "reason", "E2E测试revision冲突"
        ));
        HttpResponse<String> resp = patch("/api/tables/" + encode(tableId), body);
        assertEquals(409, resp.statusCode(), "Expected 409 conflict but got " + resp.statusCode() + " body: " + resp.body());
    }

    @Test
    @Order(15)
    void test15_Validate() throws Exception {
        long previousRevision = currentRevision;
        HttpResponse<String> resp = post("/api/validate", "{}");
        assertEquals(200, resp.statusCode(), "Validation should succeed: " + resp.body());
        currentRevision = jsonMapper.readTree(resp.body()).get("newRevision").asLong();
        assertTrue(currentRevision > previousRevision);
    }

    @Test
    @Order(16)
    void test17_IndexRebuildAndSearch() throws Exception {
        HttpResponse<String> rebuildResp = post("/api/index/rebuild", "{}");
        assertEquals(200, rebuildResp.statusCode(), "Rebuild index should succeed: " + rebuildResp.body());
        currentRevision = jsonMapper.readTree(rebuildResp.body()).get("sourceRevision").asLong();

        // Check index status
        HttpResponse<String> statusResp = get("/api/index/status");
        assertEquals(200, statusResp.statusCode(), "Index status should return 200: " + statusResp.body());
        JsonNode status = jsonMapper.readTree(statusResp.body());
        assertEquals("ready", status.get("status").asText());
        assertEquals(currentRevision, status.get("sourceRevision").asLong());

        // Search for the new description
        HttpResponse<String> searchResp = get("/api/search?q=销售订单&type=table&limit=10");
        assertEquals(200, searchResp.statusCode(), "Search should return 200: " + searchResp.body());
    }

    @Test
    @Order(17)
    void test18_StaticResourceIndexHtml() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/"))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        // May be 200 (HTML) or 404 (no frontend built yet) - both acceptable
        assertTrue(resp.statusCode() == 200 || resp.statusCode() == 404);
    }

    @Test
    @Order(18)
    void test19_PathTraversalBlocked() throws Exception {
        HttpResponse<String> resp = get("/api/../../etc/passwd");
        assertTrue(resp.statusCode() == 403 || resp.statusCode() == 404 || resp.statusCode() == 400,
                "Path traversal should be blocked");
    }

    @Test
    @Order(19)
    void test20_DeleteInferredRelation() throws Exception {
        // Delete the inferred relation: addresses.user_id -> users.id
        String fromRef = "column:" + ALIAS + ":user.addresses.user_id";
        String toRef = "column:" + ALIAS + ":user.users.id";
        String relationId = GraphIds.relationId(ALIAS, RelationType.join_observed, fromRef, toRef);
        HttpResponse<String> resp = delete("/api/relations/" + encode(relationId) + "?expectedRevision=" + currentRevision + "&reason=E2E测试删除推断关系");
        // Should succeed (inferred relations can be deleted)
        assertEquals(200, resp.statusCode(), "Delete inferred relation should succeed: " + resp.body());
        JsonNode json = jsonMapper.readTree(resp.body());
        assertTrue(json.has("newRevision"), "response should contain newRevision: " + resp.body());
        currentRevision = json.get("newRevision").asLong();
    }

    private String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
