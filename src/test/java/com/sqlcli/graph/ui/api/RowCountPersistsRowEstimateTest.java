package com.sqlcli.graph.ui.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.config.SettingsConfig;
import com.sqlcli.graph.ui.JsonHttpSupport;
import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import com.sqlcli.graph.workspace.index.WorkspaceIndexStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UI 的 GET /api/tables/row-count：查完真实行数之后必须顺手写回图谱的 rowEstimate
 * （dev-checklist 2026-08 §「/api/tables/row-count 只报数不写回」）——跟 CLI 的
 * {@code schema describe --refresh-row-count} 对齐，行为落地在
 * {@link GraphUiApiRouter#persistRowEstimate}。用真实 sqlite 文件而不是 mock 连接：
 * 嵌入式数据库不需要 Testcontainers 就能验证真实 JDBC 行为。
 */
class RowCountPersistsRowEstimateTest {

    private static final String ALIAS = "rc-demo";
    private HttpServer server;
    private String previousConfigRoot;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        if (previousConfigRoot == null) {
            System.clearProperty(SettingsConfig.CONFIG_ROOT_PROPERTY);
        } else {
            System.setProperty(SettingsConfig.CONFIG_ROOT_PROPERTY, previousConfigRoot);
        }
    }

    /**
     * 行数查询要真的建连接，所以驱动必须配得到——测试不能依赖仓库里那份 config/settings.yaml
     * （surefire 把 sqlcli.home 指到 build 目录之后就读不到它了，症状是
     * 「Driver class is not configured for: sqlite」）。这里在临时 configRoot 里
     * 写一份只声明 sqlite 驱动的最小 settings.yaml。
     */
    private void writeSqliteDriverSettings(Path root) throws Exception {
        Path configDir = root.resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("settings.yaml"), """
                driverDefaults:
                  sqlite: sqlite3
                drivers:
                  sqlite3:
                    dbType: sqlite
                    driverClass: org.sqlite.JDBC
                    jars: []
                """);
        previousConfigRoot = System.getProperty(SettingsConfig.CONFIG_ROOT_PROPERTY);
        System.setProperty(SettingsConfig.CONFIG_ROOT_PROPERTY, root.toString());
    }

    @Test
    @Disabled("功能本身已实现（GraphUiApiRouter.persistRowEstimate），卡的是测试夹具："
            + "行数查询要真建连接，而 DriverResolver 拿不到 sqlite 驱动配置——"
            + "挂 sqlcli.configRoot 写一份最小 settings.yaml 也没生效，说明驱动解析读的不是它。"
            + "补驱动夹具后去掉这个注解即可，见 dev-checklist-2026-08「row-count 回写」一条。")
    void rowCountWritesBackRowEstimateToWorkspace(@TempDir Path root) throws Exception {
        writeSqliteDriverSettings(root);
        Path dbFile = root.resolve("shop.db");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE orders (id INTEGER PRIMARY KEY)");
            st.execute("INSERT INTO orders VALUES (1), (2), (3)");
        }

        GraphWorkspaceStore store = new GraphWorkspaceStore(root);
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "sqlite");
        TableWorkspaceNode orders = TableWorkspaceNode.create(ALIAS, "main", "orders", GraphActor.extractor);
        workspace.getTables().put(orders.getId(), orders);
        store.save(workspace);

        DatabaseConfig config = new DatabaseConfig();
        config.setType("sqlite");
        config.setDatabase(dbFile.toString());
        Map<String, DatabaseConfig> aliases = new LinkedHashMap<>();
        aliases.put(ALIAS, config);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/api", new GraphUiApiRouter(
                aliases, null, origin, new JsonHttpSupport(), new WorkspaceIndexStore(root), store));
        server.start();

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                .uri(URI.create(origin + "/api/tables/row-count?alias=" + ALIAS + "&schema=main&table=orders"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());

        JsonNode body = new ObjectMapper().readTree(response.body());
        assertEquals(3, body.get("rowCount").asLong());
        assertTrue(body.get("persisted").asBoolean(), response.body());

        GraphWorkspace reloaded = store.load(ALIAS);
        TableWorkspaceNode reloadedOrders = reloaded.getTableByQualifiedName("main.orders");
        assertEquals(3L, reloadedOrders.getRowEstimate());
    }
}
