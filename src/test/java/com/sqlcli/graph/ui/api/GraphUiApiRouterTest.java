package com.sqlcli.graph.ui.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcli.config.AliasConfigStore;
import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.config.SettingsConfig;
import com.sqlcli.graph.ui.JsonHttpSupport;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sqlcli.graph.workspace.index.WorkspaceIndexStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphUiApiRouterTest {
    private HttpServer server;
    private Path root;
    private String previousConfigRoot;

    @AfterEach
    void cleanUp() throws Exception {
        if (server != null) server.stop(0);
        if (previousConfigRoot != null) {
            System.setProperty(SettingsConfig.CONFIG_ROOT_PROPERTY, previousConfigRoot);
        } else {
            System.clearProperty(SettingsConfig.CONFIG_ROOT_PROPERTY);
        }
        if (root != null) {
            try (var files = Files.walk(root)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void listsConfiguredAliasesAndRoutesRequestsByAlias() throws Exception {
        root = Files.createTempDirectory("graph-ui-router-");
        GraphWorkspaceStore store = new GraphWorkspaceStore(root);
        store.save(GraphWorkspace.create("alpha", "mysql"));
        Map<String, DatabaseConfig> aliases = new LinkedHashMap<>();
        aliases.put("alpha", config("mysql", "Alpha graph"));
        aliases.put("beta", config("postgresql", "Not imported"));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/api", new GraphUiApiRouter(
                aliases, null, origin, new JsonHttpSupport(), new WorkspaceIndexStore(root), store));
        server.start();

        HttpClient client = HttpClient.newHttpClient();
        JsonNode directory = get(client, "/api/aliases");
        assertEquals(2, directory.get("aliases").size());
        assertTrue(directory.get("aliases").get(0).get("graphAvailable").asBoolean());
        assertFalse(directory.get("aliases").get(1).get("graphAvailable").asBoolean());

        JsonNode session = get(client, "/api/session?alias=alpha");
        assertEquals("alpha", session.get("alias").asText());
        HttpResponse<String> policyList = client.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/policy/rules?alias=alpha")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, policyList.statusCode());
        HttpResponse<String> unknown = client.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/session?alias=missing")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, unknown.statusCode());
        HttpResponse<String> unknownImport = client.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/aliases/missing/import"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, unknownImport.statusCode());

        HttpResponse<String> invalidCreate = client.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/aliases"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"../unsafe\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, invalidCreate.statusCode());
    }

    /**
     * 创建别名的成功路径：{@code POST /api/aliases} 最终落到
     * {@code new AliasConfigStore().saveAlias(...)}——挂了 {@code sqlcli.configRoot}
     * 覆盖之后才敢在这里真的走一遍写盘，之前只测得到 400 那条不写盘的分支。
     */
    @Test
    void createsSqliteAliasAgainstIsolatedAliasStore() throws Exception {
        root = Files.createTempDirectory("graph-ui-router-create-");
        previousConfigRoot = System.getProperty(SettingsConfig.CONFIG_ROOT_PROPERTY);
        System.setProperty(SettingsConfig.CONFIG_ROOT_PROPERTY, root.toString());

        GraphWorkspaceStore store = new GraphWorkspaceStore(root);
        Map<String, DatabaseConfig> aliases = new LinkedHashMap<>();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/api", new GraphUiApiRouter(
                aliases, null, origin, new JsonHttpSupport(), new WorkspaceIndexStore(root), store));
        server.start();

        HttpClient client = HttpClient.newHttpClient();
        String dbPath = root.resolve("created.db").toString().replace('\\', '/');
        String body = "{\"name\":\"demo-sqlite\",\"dbType\":\"sqlite\",\"database\":\"" + dbPath + "\"}";
        HttpResponse<String> create = client.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/aliases"))
                .header("Content-Type", "application/json")
                .header("Origin", origin)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, create.statusCode());

        DatabaseConfig persisted = new AliasConfigStore().get("demo-sqlite");
        assertNotNull(persisted, "别名应已写进隔离目录里的 aliases.yaml");
        assertEquals("sqlite", persisted.getType());

        // GraphUiApiRouter 对传入的 aliases 做了防御性拷贝，创建成功后要刷新的是它内部那份，
        // 不是测试这边传进去的引用——用 GET /api/aliases 读路由自己的内存态来验证。
        JsonNode directory = get(client, "/api/aliases");
        assertEquals(1, directory.get("aliases").size());
        assertEquals("demo-sqlite", directory.get("aliases").get(0).get("name").asText());
    }

    /**
     * readonly 语义：别名 readonly（数据库只读）要进 UI 会话并随 /api/session 下发；
     * 图谱写能力不受影响，capabilities 恒含 write（图谱是本地元数据）。
     */
    @Test
    void sessionCarriesAliasReadonlyFlag() throws Exception {
        root = Files.createTempDirectory("graph-ui-router-ro-");
        GraphWorkspaceStore store = new GraphWorkspaceStore(root);
        store.save(GraphWorkspace.create("locked", "mysql"));
        DatabaseConfig readonly = config("mysql", "Readonly source");
        readonly.setReadonly(true);
        Map<String, DatabaseConfig> aliases = new LinkedHashMap<>();
        aliases.put("locked", readonly);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/api", new GraphUiApiRouter(
                aliases, null, origin, new JsonHttpSupport(), new WorkspaceIndexStore(root), store));
        server.start();

        HttpClient client = HttpClient.newHttpClient();
        JsonNode session = get(client, "/api/session?alias=locked");
        assertTrue(session.get("readOnly").asBoolean());
        boolean hasWrite = false;
        for (JsonNode cap : session.get("capabilities")) {
            hasWrite |= "write".equals(cap.asText());
        }
        assertTrue(hasWrite, "图谱写能力不该被数据库 readonly 关掉");
    }

    /**
     * sqlite 的路径预检是纯文件系统检查——不需要别名存在，也不用真的开一次 JDBC 连接
     * （sqlite 驱动本身在文件不存在时会静默新建，拿它来测反而测不出「路径拼错了」）。
     */
    @Test
    void sqlitePathCheckReportsWhetherTheFileExists() throws Exception {
        root = Files.createTempDirectory("graph-ui-router-sqlite-check-");
        GraphWorkspaceStore store = new GraphWorkspaceStore(root);
        Map<String, DatabaseConfig> aliases = new LinkedHashMap<>();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/api", new GraphUiApiRouter(
                aliases, null, origin, new JsonHttpSupport(), new WorkspaceIndexStore(root), store));
        server.start();

        HttpClient client = HttpClient.newHttpClient();
        String path = root.resolve("shop.db").toString().replace('\\', '/');

        JsonNode missing = postJson(client, "/api/aliases/sqlite/check-path", "{\"path\":\"" + path + "\"}");
        assertFalse(missing.get("exists").asBoolean());

        Files.writeString(root.resolve("shop.db"), "sqlite-ish");
        JsonNode found = postJson(client, "/api/aliases/sqlite/check-path", "{\"path\":\"" + path + "\"}");
        assertTrue(found.get("exists").asBoolean());
    }

    /**
     * Router 重构成路由表（dev-checklist 2026-08 §「Router/Controller 手工分发收成路由表」）
     * 后的回归检查：把已注册的每条路径都打一遍，断言它落到了预期的 handler
     * ——尤其是顺序敏感的几组：
     * <ul>
     *   <li>"/api/aliases/*​/import"、"/api/aliases/sqlite/check-path" 这类具体路径
     *       必须先于通配的 "/api/aliases/" catch-all 命中——用错误方法（非 405 兜底）
     *       打这些具体路径，若被 catch-all 抢先接住会得到 404 而不是 405；</li>
     *   <li>方法限定（GET-only / POST-only）在路由表里的返回时机跟重构前一致：
     *       命中路径但方法不对时统一 405，不进 handler。</li>
     * </ul>
     */
    @Test
    void everyRegisteredRouteDispatchesToExpectedHandler() throws Exception {
        root = Files.createTempDirectory("graph-ui-router-table-");
        GraphWorkspaceStore store = new GraphWorkspaceStore(root);
        Map<String, DatabaseConfig> aliases = new LinkedHashMap<>();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", new GraphUiApiRouter(
                aliases, null, baseUrlFor(server), new JsonHttpSupport(), new WorkspaceIndexStore(root), store));
        server.start();

        HttpClient client = HttpClient.newHttpClient();

        // 1) /api/executions —— GET-only，别名不存在时该路由自己的 404 文案
        assertStatusAndBody(client, "GET", "/api/executions?alias=missing", 404, "Unknown alias: missing");
        assertStatus(client, "POST", "/api/executions", 405);

        // 2) /api/executions/{id}/recovery —— GET-only，命中该 handler 独有的文案
        assertStatusAndBody(client, "GET", "/api/executions/999/recovery", 404, "回滚脚本");
        assertStatus(client, "POST", "/api/executions/999/recovery", 405);

        // 3) /api/executions/{id}/rollback —— POST-only，命中该 handler 独有的文案
        assertStatusAndBody(client, "POST", "/api/executions/999/rollback", 404, "不存在");
        assertStatus(client, "GET", "/api/executions/999/rollback", 405);

        // 4) /api/approvals —— 无方法限定，交给 ApprovalController 自己判断
        assertStatus(client, "GET", "/api/approvals", 200);
        assertStatus(client, "POST", "/api/approvals", 405);

        // 5) /api/graph-changes —— GET-only
        assertStatusAndBody(client, "GET", "/api/graph-changes", 200, "changes");
        assertStatus(client, "POST", "/api/graph-changes", 405);

        // 6) /api/aliases —— GET/POST 各走各的 handler，其余方法由 handler 自己 405
        assertStatus(client, "DELETE", "/api/aliases", 405);

        // 7)-8) /api/sql/execute、/api/workbench/execute、/api/workbench/cancel —— 都是 POST-only，
        //       方法判断在各自 controller 内部，路由表不重复限定
        assertStatus(client, "GET", "/api/sql/execute", 405);
        assertStatus(client, "GET", "/api/workbench/execute", 405);
        assertStatus(client, "GET", "/api/workbench/cancel", 405);

        // 9) /api/aliases/{name}/import 必须先于通配 "/api/aliases/" 命中：
        //    handleImport 自己判断方法，GET 得到 405；若被 catch-all 抢先会是 404
        assertStatus(client, "GET", "/api/aliases/foo/import", 405);

        // 10) /api/aliases/sqlite/check-path 同理必须先于通配前缀命中：GET 得到 405 而非 404
        assertStatus(client, "GET", "/api/aliases/sqlite/check-path", 405);

        // 11) /api/fs/list —— GET-only
        assertStatus(client, "POST", "/api/fs/list", 405);

        // 12) /api/tables/row-count —— 无方法限定，缺参数时 400
        assertStatusAndBody(client, "GET", "/api/tables/row-count", 400, "required");

        // 13) /api/schemas/catalog —— 无方法限定，未知别名 404
        assertStatusAndBody(client, "GET", "/api/schemas/catalog?alias=missing", 404, "Unknown alias: missing");

        // 14) /api/drivers —— 落到 DriverAdminController
        assertStatus(client, "GET", "/api/drivers", 200);

        // 15) 通配 "/api/aliases/" catch-all 本身仍然工作（没有被上面几条具体路径误吞）
        assertStatusAndBody(client, "GET", "/api/aliases/other-name", 404, "Unknown alias: other-name");

        // 16) 路由表全部落空后的兜底：dispatchWorkspaceScoped 仍然接得住任意未注册路径
        assertStatusAndBody(client, "GET", "/api/some/unregistered/path?alias=missing",
                404, "Unknown alias: missing");
    }

    private void assertStatus(HttpClient client, String method, String path, int expectedStatus) throws Exception {
        HttpResponse<String> response = send(client, method, path);
        assertEquals(expectedStatus, response.statusCode(), () -> method + " " + path + " -> " + response.body());
    }

    private void assertStatusAndBody(HttpClient client, String method, String path, int expectedStatus,
                                     String expectedBodyFragment) throws Exception {
        HttpResponse<String> response = send(client, method, path);
        assertEquals(expectedStatus, response.statusCode(), () -> method + " " + path + " -> " + response.body());
        assertTrue(response.body().contains(expectedBodyFragment),
                () -> method + " " + path + " body should contain '" + expectedBodyFragment + "': " + response.body());
    }

    private HttpResponse<String> send(HttpClient client, String method, String path) throws Exception {
        HttpRequest.BodyPublisher body = "GET".equals(method) || "DELETE".equals(method)
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString("{}");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .method(method, body)
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String baseUrlFor(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private JsonNode postJson(HttpClient client, String path, String body) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return new ObjectMapper().readTree(response.body());
    }

    private DatabaseConfig config(String type, String description) {
        DatabaseConfig config = new DatabaseConfig();
        config.setType(type);
        config.setDescription(description);
        return config;
    }

    private JsonNode get(HttpClient client, String path) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return new ObjectMapper().readTree(response.body());
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
