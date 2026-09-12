package com.sqlcli.graph.ui.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.graph.eval.GraphEvaluation;
import com.sqlcli.graph.eval.GraphEvaluator;
import com.sqlcli.graph.ui.JsonHttpSupport;
import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sqlcli.graph.workspace.TableType;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import com.sqlcli.graph.workspace.TermWorkspaceNode;
import com.sqlcli.graph.workspace.index.WorkspaceIndexStore;
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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评估页的「跑一次」按钮：{@code POST /api/eval/run}。
 *
 * <p>这里真正要钉住的不是「按钮能用」，是**两个入口必须算出同一个结果**。
 * 评估页原来是只读的，理由写在 CLAUDE.md 里：「加一个『跑一次』等于开出第二条产出路径，
 * 两边结果迟早对不上」。放开按钮的前提就是这条不成立——CLI 和这个端点调同一个
 * {@code GraphEvalRunner}。哪天有人在控制器里另抄一份落库代码，下面第二个断言会挂。
 */
class RunEvalFromUiTest {

    @TempDir Path temp;

    private HttpServer server;
    private String previousHome;

    @BeforeEach
    void setUp() {
        previousHome = System.getProperty("sqlcli.home");
        System.setProperty("sqlcli.home", temp.resolve("run-state-home").toString());
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        if (previousHome == null) System.clearProperty("sqlcli.home");
        else System.setProperty("sqlcli.home", previousHome);
    }

    /** 一张表 + 一条空壳术语（`term.orphan` 硬错误探针会命中它）。 */
    private GraphWorkspace workspace() {
        GraphWorkspace workspace = GraphWorkspace.create("alpha", "mysql");
        TableWorkspaceNode orders =
                TableWorkspaceNode.create("alpha", "app", "orders", GraphActor.human);
        orders.setTableType(TableType.base_table);
        workspace.getTables().put(orders.getId(), orders);
        // 既没有 description、没有 primaryTarget、也没有任何 term_mapping —— 空壳
        TermWorkspaceNode shell = TermWorkspaceNode.create("alpha", "空壳", GraphActor.agent);
        workspace.getTerms().put(shell.getId(), shell);
        return workspace;
    }

    @Test
    void runsAnEvaluationAndPersistsItSoTheListPicksItUp() throws Exception {
        GraphWorkspace workspace = workspace();
        startServer(workspace);
        HttpClient client = HttpClient.newHttpClient();

        assertEquals(0, get(client, "/api/eval/evaluations?alias=alpha").get("evaluations").size(),
                "跑之前列表是空的");

        JsonNode run = post(client, "/api/eval/run?alias=alpha");
        assertTrue(run.get("id").asText().startsWith("eval-"));
        assertEquals("alpha", run.get("alias").asText());

        JsonNode list = get(client, "/api/eval/evaluations?alias=alpha");
        assertEquals(1, list.get("evaluations").size(), "跑完立刻能在列表里看到");
        JsonNode row = list.get("evaluations").get(0);
        assertEquals(run.get("id").asText(), row.get("id").asText());
        assertEquals("eval", row.get("source").asText(),
                "必须落 source=eval，否则评估页的趋势图会把它过滤掉（那里只连 source=eval 的点）");
        assertTrue(row.get("metrics").isObject(), "覆盖率指标要有，趋势图靠它");
    }

    @Test
    void theButtonAndTheCliComputeTheSameThing() throws Exception {
        GraphWorkspace workspace = workspace();
        startServer(workspace);
        HttpClient client = HttpClient.newHttpClient();

        // CLI 那条路径最终调的也是 new GraphEvaluator().evaluate(workspace)
        GraphEvaluation direct = new GraphEvaluator().evaluate(workspace);

        JsonNode run = post(client, "/api/eval/run?alias=alpha");
        assertEquals(direct.status(), run.get("status").asText());
        assertEquals(direct.errorCount(), run.get("errorCount").asLong(),
                "两个入口的硬错误数必须一致——不一致就说明有人另写了一套评估");
        assertEquals(direct.warningCount(), run.get("warningCount").asLong());
        assertTrue(direct.errorCount() > 0, "fixture 里那条空壳术语本来就该被 term.orphan 抓到");

        JsonNode findings =
                get(client, "/api/eval/evaluations/" + run.get("id").asText() + "/findings");
        assertEquals(direct.findings().size(), findings.get("total").asInt(),
                "finding 条数也必须一致");
    }

    private void startServer(GraphWorkspace workspace) throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp.resolve("graph"));
        store.save(workspace);
        Map<String, DatabaseConfig> aliases = new LinkedHashMap<>();
        DatabaseConfig config = new DatabaseConfig();
        config.setType("mysql");
        aliases.put("alpha", config);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", new GraphUiApiRouter(aliases, null,
                "http://127.0.0.1:" + server.getAddress().getPort(), new JsonHttpSupport(),
                new WorkspaceIndexStore(temp.resolve("graph")), store));
        server.start();
    }

    private JsonNode get(HttpClient client, String path) throws Exception {
        return send(client, HttpRequest.newBuilder().uri(uri(path)).GET().build(), path);
    }

    private JsonNode post(HttpClient client, String path) throws Exception {
        return send(client, HttpRequest.newBuilder().uri(uri(path))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), path);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private JsonNode send(HttpClient client, HttpRequest request, String path) throws Exception {
        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), path + " -> " + response.body());
        return new ObjectMapper().readTree(response.body());
    }
}
