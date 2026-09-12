package com.sqlcli.graph.ui.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcli.graph.ui.GraphUiSession;
import com.sqlcli.graph.ui.JsonHttpSupport;
import com.sqlcli.graph.workspace.ColumnDataType;
import com.sqlcli.graph.workspace.ColumnWorkspaceNode;
import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphIds;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.LineageRecord;
import com.sqlcli.graph.workspace.TableType;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import com.sqlcli.graph.workspace.WorkspaceManifest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 表详情接口带出列级血缘（一跳），不用连真库，全靠内存 fixture 工作区。 */
class WorkspaceQueryControllerTableLineageTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ALIAS = "lineage-test";

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private String start(GraphWorkspace workspace) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        GraphUiSession session = new GraphUiSession(ALIAS, false);
        WorkspaceQueryController controller =
                new WorkspaceQueryController(workspace, session, new JsonHttpSupport(), null);
        server.createContext("/api/tables", controller::handle);
        server.createContext("/api/lineage", controller::handle);
        server.start();
        return origin;
    }

    private static void addColumn(TableWorkspaceNode table, String name, int ordinal) {
        ColumnWorkspaceNode column = new ColumnWorkspaceNode();
        column.setName(name);
        column.setDataType(new ColumnDataType());
        column.setOrdinal(ordinal);
        table.getColumns().add(column);
    }

    private GraphWorkspace buildWorkspace() {
        WorkspaceManifest manifest = WorkspaceManifest.create(ALIAS);
        manifest.setRevision(1);

        TableWorkspaceNode orders = TableWorkspaceNode.create(ALIAS, "app", "orders", GraphActor.extractor);
        orders.setTableType(TableType.base_table);
        addColumn(orders, "id", 1);
        addColumn(orders, "price", 2);

        TableWorkspaceNode summary = TableWorkspaceNode.create(ALIAS, "app", "order_summary", GraphActor.extractor);
        summary.setTableType(TableType.base_table);
        addColumn(summary, "total_amount", 1);
        addColumn(summary, "note", 2);

        TableWorkspaceNode report = TableWorkspaceNode.create(ALIAS, "app", "report", GraphActor.extractor);
        report.setTableType(TableType.base_table);
        addColumn(report, "total_display", 1);

        String priceId = GraphIds.columnId(ALIAS, "app", "orders", "price");
        String totalId = GraphIds.columnId(ALIAS, "app", "order_summary", "total_amount");
        String displayId = GraphIds.columnId(ALIAS, "app", "report", "total_display");
        LineageRecord record = LineageRecord.create(ALIAS, totalId, java.util.List.of(priceId),
                "SUM(price)", "order_summary_view", GraphActor.agent);
        LineageRecord secondHop = LineageRecord.create(ALIAS, displayId, java.util.List.of(totalId),
                "ROUND(total_amount,2)", "report_view", GraphActor.agent);

        GraphWorkspace workspace = new GraphWorkspace();
        workspace.setManifest(manifest);
        workspace.getTables().put(orders.getId(), orders);
        workspace.getTables().put(summary.getId(), summary);
        workspace.getTables().put(report.getId(), report);
        workspace.getLineage().put(record.getId(), record);
        workspace.getLineage().put(secondHop.getId(), secondHop);
        return workspace;
    }

    @Test
    void tableDetailCarriesUpstreamAndDownstreamLineagePerColumn() throws Exception {
        GraphWorkspace workspace = buildWorkspace();
        String origin = start(workspace);

        JsonNode summaryDetail = get(origin + "/api/tables/" + encode("table:" + ALIAS + ":app.order_summary"));
        JsonNode totalLineage = summaryDetail.get("lineage").get("total_amount");
        assertTrue(totalLineage != null, "total_amount 应该带出血缘");
        assertEquals(1, totalLineage.get("upstream").size());
        JsonNode upstreamEndpoint = totalLineage.get("upstream").get(0);
        assertEquals("app", upstreamEndpoint.get("schema").asText());
        assertEquals("orders", upstreamEndpoint.get("table").asText());
        assertEquals("price", upstreamEndpoint.get("column").asText());
        assertEquals("SUM(price)", upstreamEndpoint.get("expression").asText());
        assertEquals("order_summary_view", upstreamEndpoint.get("through").asText());
        // 没血缘的列不出现在 map 里
        assertFalse(summaryDetail.get("lineage").has("note"));

        JsonNode ordersDetail = get(origin + "/api/tables/" + encode("table:" + ALIAS + ":app.orders"));
        JsonNode priceLineage = ordersDetail.get("lineage").get("price");
        assertEquals(1, priceLineage.get("downstream").size());
        JsonNode downstreamEndpoint = priceLineage.get("downstream").get(0);
        assertEquals("order_summary", downstreamEndpoint.get("table").asText());
        assertEquals("total_amount", downstreamEndpoint.get("column").asText());
        assertFalse(ordersDetail.get("lineage").has("id"));
    }

    @Test
    void lineageEndpointWalksMultipleHopsWithLevelsAndExpression() throws Exception {
        GraphWorkspace workspace = buildWorkspace();
        String origin = start(workspace);

        JsonNode graph = get(origin + "/api/lineage?table=" + encode("table:" + ALIAS + ":app.report")
                + "&column=total_display&depth=2");

        assertEquals("app.report.total_display", graph.get("center").asText());
        assertEquals(2, graph.get("depth").asInt());
        assertFalse(graph.get("truncated").asBoolean());
        assertEquals(3, graph.get("nodes").size(), graph.toString());

        java.util.Map<String, JsonNode> nodesById = new java.util.HashMap<>();
        graph.get("nodes").forEach(n -> nodesById.put(n.get("id").asText(), n));
        assertEquals(0, nodesById.get("app.report.total_display").get("level").asInt());
        assertEquals(-1, nodesById.get("app.order_summary.total_amount").get("level").asInt());
        assertEquals(-2, nodesById.get("app.orders.price").get("level").asInt());

        assertEquals(2, graph.get("edges").size());
        JsonNode firstHop = findEdge(graph, "app.order_summary.total_amount", "app.report.total_display");
        assertEquals("ROUND(total_amount,2)", firstHop.get("expression").asText());
        assertEquals("report_view", firstHop.get("through").asText());
        JsonNode secondHop = findEdge(graph, "app.orders.price", "app.order_summary.total_amount");
        assertEquals("SUM(price)", secondHop.get("expression").asText());
        assertEquals("order_summary_view", secondHop.get("through").asText());

        // depth=1 只应该看到直接上游，二跳的 price 不出现
        JsonNode oneHop = get(origin + "/api/lineage?table=" + encode("table:" + ALIAS + ":app.report")
                + "&column=total_display&depth=1");
        assertEquals(2, oneHop.get("nodes").size());
        assertEquals(1, oneHop.get("edges").size());
    }

    private static JsonNode findEdge(JsonNode graph, String from, String to) {
        for (JsonNode edge : graph.get("edges")) {
            if (from.equals(edge.get("from").asText()) && to.equals(edge.get("to").asText())) {
                return edge;
            }
        }
        throw new AssertionError("edge not found: " + from + " -> " + to + " in " + graph);
    }

    private JsonNode get(String url) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return MAPPER.readTree(response.body());
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
