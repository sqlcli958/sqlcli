package com.sqlcli.graph.ui.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.graph.ui.JsonHttpSupport;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sqlcli.graph.workspace.index.WorkspaceIndexStore;
import com.sqlcli.runstate.RunStateStore;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 评估页要读的两个只读端点：列表 + 分页的 findings。 */
class GraphEvalApiTest {

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

    @Test
    void listsEvaluationsAndPagesTheirFindings() throws Exception {
        RunStateStore runState = new RunStateStore(temp.resolve("run-state-home/sqlcli.db"),
                temp.resolve("run-state-home/execution-history"));
        List<RunStateStore.GraphFindingRow> findings = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            findings.add(new RunStateStore.GraphFindingRow(i, "term.orphan", "error", "term:alpha:t" + i,
                    "既没有描述也没有映射", "sql-cli alpha schema add-term t" + i + " --description '…'"));
        }
        runState.saveGraphEvaluation(new RunStateStore.GraphEvaluationRow("eval-1", "alpha", "eval",
                "fail", 5, 1000L, 12L, 3, 0, "{\"tableDescription\":0.5}"), findings);

        startServer();
        HttpClient client = HttpClient.newHttpClient();

        JsonNode list = get(client, "/api/eval/evaluations?alias=alpha");
        assertEquals(1, list.get("evaluations").size());
        JsonNode evaluation = list.get("evaluations").get(0);
        for (String field : List.of("id", "alias", "source", "status", "revision", "startedAt",
                "elapsedMs", "errorCount", "warningCount", "metrics")) {
            assertTrue(evaluation.has(field), "evaluations[].{" + field + "} 是前端契约字段");
        }
        assertEquals(3, evaluation.get("errorCount").asInt());
        assertEquals(0.5, evaluation.get("metrics").get("tableDescription").asDouble(), 0.0001,
                "metrics 内联成对象返回，前端不该再解一次字符串");

        JsonNode page = get(client, "/api/eval/evaluations/eval-1/findings?page=2&pageSize=2");
        assertEquals(3, page.get("total").asInt());
        assertEquals(2, page.get("page").asInt());
        assertEquals(1, page.get("findings").size());
        JsonNode finding = page.get("findings").get(0);
        for (String field : List.of("seq", "probe", "severity", "targetId", "message", "remediation")) {
            assertTrue(finding.has(field), "findings[].{" + field + "} 是前端契约字段");
        }
        assertEquals(2, finding.get("seq").asInt());
        assertTrue(finding.get("remediation").asText().startsWith("sql-cli "));

        assertEquals(0, get(client, "/api/eval/evaluations?alias=other").get("evaluations").size());
    }

    private void startServer() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp.resolve("graph"));
        store.save(GraphWorkspace.create("alpha", "mysql"));
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
        HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), path + " -> " + response.body());
        return new ObjectMapper().readTree(response.body());
    }
}
