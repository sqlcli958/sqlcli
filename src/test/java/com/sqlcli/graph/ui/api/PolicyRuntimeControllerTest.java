package com.sqlcli.graph.ui.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcli.graph.policy.PolicyEnforcement;
import com.sqlcli.graph.policy.PolicyRule;
import com.sqlcli.graph.policy.PolicyRuleSetManager;
import com.sqlcli.graph.policy.PolicySeverity;
import com.sqlcli.graph.policy.RuleSet;
import com.sqlcli.graph.ui.GraphUiSession;
import com.sqlcli.graph.ui.JsonHttpSupport;
import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sqlcli.graph.workspace.TableType;
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
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Web 规则治理闭环：评估 → 违规 → 豁免 → 复评 → 撤销 → 再复评。 */
class PolicyRuntimeControllerTest {

    private static final String ALIAS = "policy-runtime";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir Path temp;

    private GraphWorkspaceStore store;
    private GraphUiSession session;
    private HttpServer server;
    private String origin;
    private String previousHome;

    @BeforeEach
    void setUp() throws Exception {
        previousHome = System.getProperty("sqlcli.home");
        System.setProperty("sqlcli.home", temp.resolve("home").toString());

        store = new GraphWorkspaceStore(temp.resolve("graphs"));
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");
        TableWorkspaceNode orders =
                TableWorkspaceNode.create(ALIAS, "app", "orders", GraphActor.extractor);
        orders.setTableType(TableType.base_table);
        workspace.getTables().put(orders.getId(), orders);
        store.save(workspace);

        PolicyRuleSetManager manager = new PolicyRuleSetManager(store);
        RuleSet rules = new RuleSet();
        rules.setKind("PolicyRuleSet");
        rules.setId("structure-policy");
        rules.setTitle("结构规范");
        rules.setVersion("1");
        PolicyRule rule = new PolicyRule();
        rule.setId("pk-required");
        rule.setTitle("必须有主键");
        rule.setCategory("primary_key_required");
        rule.setSeverity(PolicySeverity.error);
        rule.setEnforcement(PolicyEnforcement.required);
        rules.getRules().add(rule);
        manager.create(ALIAS, "structure.yaml", rules);
        manager.setBound(ALIAS, "structure.yaml", true);

        session = new GraphUiSession(ALIAS, false);
        PolicyRuntimeController controller =
                new PolicyRuntimeController(session, new JsonHttpSupport(), store);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/policy/runtime", controller);
        server.start();
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        if (previousHome == null) System.clearProperty("sqlcli.home");
        else System.setProperty("sqlcli.home", previousHome);
    }

    @Test
    void waiverChangesCurrentViolationStatusAndRevocationRestoresIt() throws Exception {
        JsonNode first = post("/api/policy/runtime/run", Map.of());
        assertEquals(1, first.get("openViolationCount").asInt());
        JsonNode violation = first.get("violations").get(0);
        assertEquals("pk-required", violation.get("ruleId").asText());
        String targetId = violation.get("targetId").asText();

        JsonNode created = post("/api/policy/runtime/waivers", Map.of(
                "ruleId", "pk-required",
                "targetId", targetId,
                "reason", "迁移窗口临时豁免",
                "expiresAt", LocalDateTime.now().plusDays(7).withNano(0).toString()));
        String waiverId = created.get("waiver").get("id").asText();
        assertTrue(waiverId.startsWith("waiver-"));

        JsonNode waived = post("/api/policy/runtime/run", Map.of());
        assertEquals(0, waived.get("openViolationCount").asInt());
        assertEquals(1, waived.get("waivedViolationCount").asInt());
        assertEquals(1, waived.get("activeWaiverCount").asInt());

        post("/api/policy/runtime/waivers/" + waiverId + "/revoke",
                Map.of("reason", "迁移完成"));
        JsonNode restored = post("/api/policy/runtime/run", Map.of());
        assertEquals(1, restored.get("openViolationCount").asInt());
        assertEquals(0, restored.get("activeWaiverCount").asInt());
    }

    @Test
    void getDoesNotRequireWriteSessionButMutationsDo() throws Exception {
        HttpResponse<String> read = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(origin + "/api/policy/runtime")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, read.statusCode(), read.body());

        HttpResponse<String> write = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(origin + "/api/policy/runtime/run"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, write.statusCode(), write.body());
    }

    private JsonNode post(String path, Map<String, Object> body) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(origin + path))
                        .header("Content-Type", "application/json")
                        .header("X-Session-Token", session.getSessionToken())
                        .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return MAPPER.readTree(response.body());
    }
}
