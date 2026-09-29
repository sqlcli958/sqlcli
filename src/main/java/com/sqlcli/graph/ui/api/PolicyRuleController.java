package com.sqlcli.graph.ui.api;

import com.sqlcli.approval.ApprovalGate;
import com.sqlcli.graph.policy.PolicyRuleProposals;
import com.sqlcli.graph.policy.PolicyRuleSetManager;
import com.sqlcli.graph.policy.RuleSet;
import com.sqlcli.graph.ui.GraphUiSession;
import com.sqlcli.graph.ui.JsonHttpSupport;
import com.sqlcli.graph.ui.dto.ApiError;
import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** API for editing an alias-scoped policy/rules directory. */
public class PolicyRuleController implements HttpHandler {
    private static final String ROOT = "/api/policy/rules";

    private final GraphUiSession session;
    private final JsonHttpSupport json;
    private final PolicyRuleSetManager manager;
    private final PolicyRuleProposals proposals;

    public PolicyRuleController(GraphUiSession session, JsonHttpSupport json, GraphWorkspaceStore workspaceStore) {
        this.session = session;
        this.json = json;
        this.manager = new PolicyRuleSetManager(workspaceStore);
        this.proposals = new PolicyRuleProposals(workspaceStore);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        try {
            if (ROOT.equals(path) && "GET".equals(method)) {
                json.writeOk(exchange, Map.of("ruleSets", manager.list(session.getAlias())));
                return;
            }
            if (!canWrite(exchange)) {
                json.writeJson(exchange, 403, ApiError.unauthorized("Invalid or missing session token"));
                return;
            }
            if (ROOT.equals(path) && "POST".equals(method)) {
                Map<String, Object> body = json.readBody(exchange, Map.class);
                String fileName = requireString(body, "fileName");
                RuleSet ruleSet = json.getObjectMapper().convertValue(body.get("ruleSet"), RuleSet.class);
                String reason = optionalReason(body, "Web 新建规则集 " + fileName);
                json.writeOk(exchange, proposalResponse(proposals.proposeRuleSet(
                        session.getAlias(), fileName, ruleSet, GraphActor.human, reason, manual())));
                return;
            }
            String suffix = path.startsWith(ROOT + "/") ? path.substring((ROOT + "/").length()) : null;
            if (suffix == null || suffix.isBlank()) {
                json.writeNotFound(exchange, "API endpoint not found: " + path);
                return;
            }
            if (suffix.endsWith("/binding") && "PUT".equals(method)) {
                String fileName = suffix.substring(0, suffix.length() - "/binding".length());
                Map<String, Object> body = json.readBody(exchange, Map.class);
                Object enabled = body == null ? null : body.get("enabled");
                if (!(enabled instanceof Boolean value)) {
                    throw new IllegalArgumentException("enabled is required");
                }
                String reason = optionalReason(body, (value ? "Web 启用规则集 " : "Web 停用规则集 ") + fileName);
                json.writeOk(exchange, proposalResponse(proposals.proposeBinding(
                        session.getAlias(), fileName, value, GraphActor.human, reason, manual())));
                return;
            }
            if ("PUT".equals(method)) {
                Map<String, Object> body = json.readBody(exchange, Map.class);
                Object rawRuleSet = body != null && body.containsKey("ruleSet") ? body.get("ruleSet") : body;
                RuleSet ruleSet = json.getObjectMapper().convertValue(rawRuleSet, RuleSet.class);
                String reason = optionalReason(body, "Web 修改规则集 " + suffix);
                json.writeOk(exchange, proposalResponse(proposals.proposeRuleSet(
                        session.getAlias(), suffix, ruleSet, GraphActor.human, reason, manual())));
                return;
            }
            if ("DELETE".equals(method)) {
                json.writeOk(exchange, proposalResponse(proposals.proposeDeleteRuleSet(
                        session.getAlias(), suffix, GraphActor.human,
                        "Web 删除规则集 " + suffix, manual())));
                return;
            }
            exchange.sendResponseHeaders(405, -1);
        } catch (IllegalArgumentException e) {
            json.writeJson(exchange, 400, ApiError.badRequest(e.getMessage()));
        } catch (IllegalStateException e) {
            json.writeJson(exchange, 409, ApiError.badRequest(e.getMessage()));
        } catch (Exception e) {
            json.writeError(exchange, e);
        }
    }

    private boolean manual() {
        return ApprovalGate.isEnabled(session.getAlias(), ApprovalGate.Kind.GRAPH);
    }

    private Map<String, Object> proposalResponse(PolicyRuleProposals.Proposal proposal) throws IOException {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("fileName", proposal.fileName());
        response.put("approvalId", proposal.approvalId());
        response.put("applied", proposal.applied());
        if (proposal.applied()) {
            manager.list(session.getAlias()).stream()
                    .filter(item -> item.fileName().equals(proposal.fileName()))
                    .findFirst()
                    .ifPresent(item -> {
                        response.put("ruleSet", item.ruleSet());
                        response.put("enabled", item.enabled());
                    });
        }
        return response;
    }

    private String optionalReason(Map<String, Object> body, String fallback) {
        Object raw = body == null ? null : body.get("reason");
        return raw == null || String.valueOf(raw).isBlank() ? fallback : String.valueOf(raw);
    }

    private boolean canWrite(HttpExchange exchange) {
        return session.validateWriteRequest(exchange.getRequestHeaders().getFirst("X-Session-Token"),
                exchange.getRequestHeaders().getFirst("Origin"));
    }

    private String requireString(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return String.valueOf(value);
    }
}
