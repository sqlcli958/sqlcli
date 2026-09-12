package com.sqlcli.graph.ui.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcli.graph.ui.GraphUiSession;
import com.sqlcli.graph.ui.JsonHttpSupport;
import com.sqlcli.graph.workspace.ColumnDataType;
import com.sqlcli.graph.workspace.ColumnValueHints;
import com.sqlcli.graph.workspace.ColumnWorkspaceNode;
import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphStatus;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.RelationType;
import com.sqlcli.graph.workspace.RelationWorkspaceEdge;
import com.sqlcli.graph.workspace.SemanticType;
import com.sqlcli.graph.workspace.TableType;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import com.sqlcli.graph.workspace.TermWorkspaceNode;
import com.sqlcli.graph.workspace.ValidationIssueRecord;
import com.sqlcli.graph.workspace.ValidationIssueStatus;
import com.sqlcli.graph.workspace.ValidationSeverity;
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

/**
 * 图谱完整性看板接口：覆盖率 / 待办量 / 关系健康，全靠内存 fixture，不碰真库。
 *
 * fixture 里三张表：
 * <ul>
 *   <li>orders —— 有 comment/businessName，两列都齐全的语义标注</li>
 *   <li>customers —— 没 comment，一列没标注；跟 orders 只有 foreign_key，没有 join_observed</li>
 *   <li>report —— candidate 状态，零关系（孤立表）</li>
 * </ul>
 */
class WorkspaceQueryControllerCompletenessTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ALIAS = "completeness-test";

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
        server.createContext("/api/workspace", controller::handle);
        server.start();
        return origin;
    }

    private static ColumnWorkspaceNode column(String name, String comment, String businessName,
            SemanticType semanticType, boolean withValueHints) {
        ColumnWorkspaceNode column = new ColumnWorkspaceNode();
        column.setName(name);
        column.setDataType(new ColumnDataType());
        column.setComment(comment);
        column.setBusinessName(businessName);
        column.setSemanticType(semanticType);
        if (withValueHints) {
            ColumnValueHints hints = new ColumnValueHints();
            hints.setFormat("YYYY-MM-DD");
            column.setValueHints(hints);
        }
        return column;
    }

    private GraphWorkspace buildWorkspace() {
        WorkspaceManifest manifest = WorkspaceManifest.create(ALIAS);
        manifest.setRevision(1);

        TableWorkspaceNode orders = TableWorkspaceNode.create(ALIAS, "app", "orders", GraphActor.human);
        orders.setTableType(TableType.base_table);
        orders.setComment("订单表");
        orders.setBusinessName("订单");
        orders.getColumns().add(column("id", "主键", "ID", null, false));
        orders.getColumns().add(column("customer_id", "客户id", "客户", null, false));

        TableWorkspaceNode customers = TableWorkspaceNode.create(ALIAS, "app", "customers", GraphActor.human);
        customers.setTableType(TableType.base_table);
        customers.getColumns().add(column("id", null, null, null, false));
        customers.getColumns().add(column("phone", "手机号", "手机号", SemanticType.phone, true));

        TableWorkspaceNode report = TableWorkspaceNode.create(ALIAS, "app", "report", GraphActor.agent);
        report.setTableType(TableType.base_table);
        // TableWorkspaceNode.create 恒定写 discovered，不像 relation/term 那样按 actor 派生候选状态；
        // 这里手动设成 candidate，模拟一张等人发布的候选表。
        report.setStatus(GraphStatus.candidate);
        report.getColumns().add(column("total", null, null, null, false));

        GraphWorkspace workspace = new GraphWorkspace();
        workspace.setManifest(manifest);
        workspace.getTables().put(orders.getId(), orders);
        workspace.getTables().put(customers.getId(), customers);
        workspace.getTables().put(report.getId(), report);

        // orders.customer_id -> customers.id：只有 foreign_key，没有 join_observed
        RelationWorkspaceEdge fk = RelationWorkspaceEdge.create(ALIAS, RelationType.foreign_key,
                "column:" + ALIAS + ":app.orders.customer_id", "column:" + ALIAS + ":app.customers.id",
                GraphActor.system);
        fk.setStatus(GraphStatus.verified);
        workspace.getRelations().add(fk);

        // 一条候选 join_observed，还没人发布
        RelationWorkspaceEdge candidateJoin = RelationWorkspaceEdge.create(ALIAS, RelationType.join_observed,
                "column:" + ALIAS + ":app.orders.id", "column:" + ALIAS + ":app.report.total", GraphActor.agent);
        workspace.getRelations().add(candidateJoin);

        // 一条被拒绝的候选
        RelationWorkspaceEdge rejected = RelationWorkspaceEdge.create(ALIAS, RelationType.join_observed,
                "column:" + ALIAS + ":app.customers.phone", "column:" + ALIAS + ":app.report.total",
                GraphActor.agent);
        rejected.setStatus(GraphStatus.ignored);
        workspace.getRelations().add(rejected);

        TermWorkspaceNode term = TermWorkspaceNode.create(ALIAS, "订单", GraphActor.agent);
        workspace.getTerms().put(term.getId(), term);

        workspace.getValidationIssues().add(ValidationIssueRecord.create(
                ALIAS, ValidationSeverity.error, "missing_comment", "缺注释", orders.getId(), null));
        workspace.getValidationIssues().add(ValidationIssueRecord.create(
                ALIAS, ValidationSeverity.warning, "missing_business_name", "缺业务名", customers.getId(), null));
        ValidationIssueRecord ignoredIssue = ValidationIssueRecord.create(
                ALIAS, ValidationSeverity.info, "low_confidence", "置信度低", report.getId(), null);
        ignoredIssue.setStatus(ValidationIssueStatus.ignored);
        workspace.getValidationIssues().add(ignoredIssue);

        return workspace;
    }

    @Test
    void reportsCoverageBacklogAndRelationHealth() throws Exception {
        String origin = start(buildWorkspace());

        JsonNode body = get(origin + "/api/workspace/completeness");

        JsonNode tables = body.get("tables");
        assertEquals(3, tables.get("total").asInt());
        assertEquals(1, tables.get("withComment").asInt());
        assertEquals(1, tables.get("withBusinessName").asInt());

        JsonNode columns = body.get("columns");
        assertEquals(5, columns.get("total").asInt());
        assertEquals(3, columns.get("withComment").asInt());
        assertEquals(3, columns.get("withBusinessName").asInt());
        assertEquals(1, columns.get("withSemanticType").asInt());
        assertEquals(1, columns.get("withValueHints").asInt());

        JsonNode backlog = body.get("backlog");
        assertEquals(1, backlog.get("candidateTables").asInt());
        assertEquals(1, backlog.get("candidateRelations").asInt());
        assertEquals(1, backlog.get("ignoredRelations").asInt());
        assertEquals(1, backlog.get("candidateTerms").asInt());
        assertEquals(1, backlog.get("ignoredIssues").asInt());
        assertEquals(1, backlog.get("openIssuesBySeverity").get("error").asInt());
        assertEquals(1, backlog.get("openIssuesBySeverity").get("warning").asInt());
        // 三列有语义内容（orders.id / orders.customer_id / customers.phone），fixture 里没人确认过；
        // customers.id 和 report.total 只有结构事实，不算待确认
        assertEquals(3, backlog.get("unverifiedColumnSemantics").asInt());

        JsonNode relations = body.get("relations");
        assertEquals(3, relations.get("totalTables").asInt());
        // report 有一条候选 join_observed（orders -> report），三张表都不孤立
        assertEquals(0, relations.get("isolatedTables").asInt());
        // orders 除了 foreign_key 还有那条候选 join_observed，不算 fkOnly；
        // customers 只在 foreign_key 里出现过，一条 join_observed 都没有
        assertEquals(1, relations.get("fkOnlyTables").asInt());
    }

    /**
     * 字段语义的闸门是 verified 位（见 {@link ColumnWorkspaceNode} 类头）——确认过的
     * 不再计入待确认。这个数说的是"生效了但没人认过"，跟 candidate 的"写进去了还不算数"
     * 不是一回事，所以没跟那几个数合并。
     */
    @Test
    void confirmedColumnDropsOutOfPendingCount() throws Exception {
        GraphWorkspace workspace = buildWorkspace();
        workspace.getTableByQualifiedName("app.customers").findColumn("phone").setVerified(true);

        JsonNode backlog = get(start(workspace) + "/api/workspace/completeness").get("backlog");

        assertEquals(2, backlog.get("unverifiedColumnSemantics").asInt());
    }

    private JsonNode get(String url) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return MAPPER.readTree(response.body());
    }
}
