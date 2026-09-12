package com.sqlcli.graph.ui.api;

import com.sqlcli.config.AliasConfigStore;
import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.config.SettingsConfig;
import com.sqlcli.graph.ui.JsonHttpSupport;
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
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * PATCH/DELETE 的成功路径现在也测得了：{@link AliasConfigStore} 的落盘路径挂了
 * {@link SettingsConfig#CONFIG_ROOT_PROPERTY} 覆盖开关，测试把它指到临时目录，
 * 写坏的是临时文件，碰不到仓库里的真实 config/aliases.yaml。
 */
class AliasAdminControllerTest {
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
    void rejectsUnknownAliasAndForeignOrigin() throws Exception {
        root = Files.createTempDirectory("alias-admin-");
        Map<String, DatabaseConfig> aliases = new LinkedHashMap<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/api", new GraphUiApiRouter(aliases, null, origin, new JsonHttpSupport(),
                new WorkspaceIndexStore(root), new GraphWorkspaceStore(root)));
        server.start();
        HttpClient client = HttpClient.newHttpClient();

        assertEquals(404, send(client, origin, "GET", "/api/aliases/nope", null, null).statusCode());
        assertEquals(404, send(client, origin, "PATCH", "/api/aliases/nope", "{\"username\":\"x\"}", origin).statusCode());
        assertEquals(404, send(client, origin, "DELETE", "/api/aliases/nope", null, origin).statusCode());
        assertEquals(404, send(client, origin, "POST", "/api/aliases/nope/test", "{}", origin).statusCode());

        // 跨源写请求必须先被 Origin 拦掉，不能走到别名查找。
        HttpResponse<String> foreign = send(client, origin, "PUT", "/api/aliases/nope/secret",
                "{\"value\":\"super-secret\"}", "http://evil.example");
        assertEquals(403, foreign.statusCode());
        assertFalse(foreign.body().contains("super-secret"), "响应体不得回显密码值");
    }

    @Test
    void patchAndDeleteRoundTripAgainstIsolatedAliasStore() throws Exception {
        root = Files.createTempDirectory("alias-admin-success-");
        previousConfigRoot = System.getProperty(SettingsConfig.CONFIG_ROOT_PROPERTY);
        System.setProperty(SettingsConfig.CONFIG_ROOT_PROPERTY, root.toString());

        // sqlite 别名不需要 driverRef/username/secretRef，省掉驱动解析这条支线，
        // 只测 AliasConfigStore 落盘 + AliasAdminController 编排这一层。
        DatabaseConfig seed = new DatabaseConfig();
        seed.setType("sqlite");
        seed.setDatabase(root.resolve("demo.db").toString());
        new AliasConfigStore().saveAlias("demo", seed);

        Map<String, DatabaseConfig> aliases = new LinkedHashMap<>();
        aliases.put("demo", seed);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/api", new GraphUiApiRouter(aliases, null, origin, new JsonHttpSupport(),
                new WorkspaceIndexStore(root), new GraphWorkspaceStore(root)));
        server.start();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> patch = send(client, origin, "PATCH", "/api/aliases/demo",
                "{\"description\":\"更新后的描述\"}", origin);
        assertEquals(200, patch.statusCode());
        assertEquals("更新后的描述", new AliasConfigStore().get("demo").getDescription());

        HttpResponse<String> delete = send(client, origin, "DELETE", "/api/aliases/demo", null, origin);
        assertEquals(200, delete.statusCode());
        assertNull(new AliasConfigStore().get("demo"), "DELETE 之后 store 里不应再找到该别名");
    }

    private HttpResponse<String> send(HttpClient client, String base, String method, String path,
                                      String body, String origin) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) request.header("Content-Type", "application/json");
        if (origin != null) request.header("Origin", origin);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
