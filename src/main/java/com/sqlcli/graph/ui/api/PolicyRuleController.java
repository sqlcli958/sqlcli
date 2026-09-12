package com.sqlcli.graph.ui.api;

import com.sqlcli.graph.policy.PolicyRuleSetManager;
import com.sqlcli.graph.policy.RuleSet;
import com.sqlcli.graph.ui.GraphUiSession;
import com.sqlcli.graph.ui.JsonHttpSupport;
import com.sqlcli.graph.ui.dto.ApiError;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.Map;

/** API for editing an alias-scoped policy/rules directory. */
public class PolicyRuleController implements HttpHandler {
    private static final String ROOT = "/api/policy/rules";

    private final GraphUiSession session;
    private final JsonHttpSupport json;
    private final PolicyRuleSetManager manager;

    public PolicyRuleController(GraphUiSession session, JsonHttpSupport json, GraphWorkspaceStore workspaceStore) {
        this.session = session;
        this.json = json;
        this.manager = new PolicyRuleSetManager(workspaceStore);
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
                json.writeOk(exchange, manager.create(session.getAlias(), fileName, ruleSet));
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
                manager.setBound(session.getAlias(), fileName, value);
                json.writeOk(exchange, Map.of("fileName", fileName, "enabled", value));
                return;
            }
            if ("PUT".equals(method)) {
                RuleSet ruleSet = json.readBody(exchange, RuleSet.class);
                json.writeOk(exchange, manager.update(session.getAlias(), suffix, ruleSet));
                return;
            }
            if ("DELETE".equals(method)) {
                manager.delete(session.getAlias(), suffix);
                json.writeOk(exchange, Map.of("fileName", suffix));
                return;
            }
            exchange.sendResponseHeaders(405, -1);
        } catch (IllegalArgumentException e) {
            json.writeJson(exchange, 400, ApiError.badRequest(e.getMessage()));
        } catch (Exception e) {
            json.writeError(exchange, e);
        }
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
