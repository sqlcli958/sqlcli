package com.sqlcli.graph.ui.api;

import com.sqlcli.graph.policy.PolicyCheckResult;
import com.sqlcli.graph.policy.PolicyService;
import com.sqlcli.graph.policy.PolicyViolation;
import com.sqlcli.graph.policy.PolicyViolationStatus;
import com.sqlcli.graph.policy.RuleEvaluation;
import com.sqlcli.graph.policy.RuleWaiver;
import com.sqlcli.graph.ui.GraphUiSession;
import com.sqlcli.graph.ui.JsonHttpSupport;
import com.sqlcli.graph.ui.dto.ApiError;
import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Policy 的运行态 Web API：评估结果、违规与豁免。
 *
 * <p>规则定义仍由 {@link PolicyRuleController} 管；这里负责“规则跑完以后发生了什么”，
 * 把 CLI 已有的 evaluation / violation / waiver 能力接到 Web，避免规则页只有编辑没有反馈。
 */
public final class PolicyRuntimeController implements HttpHandler {
    private static final String ROOT = "/api/policy/runtime";
    private static final int HISTORY_LIMIT = 20;

    private final GraphUiSession session;
    private final JsonHttpSupport json;
    private final PolicyService service;

    public PolicyRuntimeController(GraphUiSession session, JsonHttpSupport json,
            GraphWorkspaceStore workspaceStore) {
        this.session = session;
        this.json = json;
        this.service = new PolicyService(workspaceStore);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        try {
            if (ROOT.equals(path) && "GET".equals(method)) {
                json.writeOk(exchange, snapshot());
                return;
            }
            if (!canWrite(exchange)) {
                json.writeJson(exchange, 403, ApiError.unauthorized("Invalid or missing session token"));
                return;
            }
            if ((ROOT + "/run").equals(path) && "POST".equals(method)) {
                List<PolicyCheckResult> results =
                        service.checkBound(session.getAlias(), GraphActor.human, Map.of());
                Map<String, Object> body = snapshot();
                body.put("runEvaluations", results.stream().map(PolicyCheckResult::evaluation).toList());
                json.writeOk(exchange, body);
                return;
            }
            if ((ROOT + "/waivers").equals(path) && "POST".equals(method)) {
                Map<String, Object> body = json.readBody(exchange, Map.class);
                String ruleId = require(body, "ruleId");
                String targetId = require(body, "targetId");
                String reason = require(body, "reason");
                String rawExpiry = require(body, "expiresAt");
                LocalDateTime expiresAt;
                try {
                    expiresAt = LocalDateTime.parse(rawExpiry);
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("expiresAt must be ISO local date-time, e.g. 2026-10-06T12:00:00");
                }
                RuleWaiver waiver = service.addWaiver(
                        session.getAlias(), ruleId, targetId, reason, expiresAt, GraphActor.human);
                json.writeOk(exchange, Map.of("waiver", waiver));
                return;
            }
            String prefix = ROOT + "/waivers/";
            if (path.startsWith(prefix) && path.endsWith("/revoke") && "POST".equals(method)) {
                String encoded = path.substring(prefix.length(), path.length() - "/revoke".length());
                String id = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
                Map<String, Object> body = json.readBody(exchange, Map.class);
                String reason = require(body, "reason");
                RuleWaiver waiver = service.revokeWaiver(session.getAlias(), id, reason, GraphActor.human);
                json.writeOk(exchange, Map.of("waiver", waiver));
                return;
            }
            exchange.sendResponseHeaders(405, -1);
        } catch (IllegalArgumentException e) {
            json.writeJson(exchange, 400, ApiError.badRequest(e.getMessage()));
        } catch (Exception e) {
            json.writeError(exchange, e);
        }
    }

    /**
     * “当前”不是简单取最新一轮：一个别名可绑定多个 ruleset，checkBound 会各落一轮。
     * 因此按 ruleSetId 各取最新一次，再汇总这些轮次的 violation。
     */
    private Map<String, Object> snapshot() throws Exception {
        List<RuleEvaluation> history = service.listEvaluations(session.getAlias());
        Map<String, RuleEvaluation> currentByRuleSet = new LinkedHashMap<>();
        for (RuleEvaluation evaluation : history) {
            String key = evaluation.getRuleSetId() == null ? evaluation.getId() : evaluation.getRuleSetId();
            currentByRuleSet.putIfAbsent(key, evaluation);
        }

        List<PolicyViolation> violations = new ArrayList<>();
        for (RuleEvaluation evaluation : currentByRuleSet.values()) {
            violations.addAll(service.listViolations(session.getAlias(), evaluation.getId()));
        }
        violations.sort(java.util.Comparator
                .comparing((PolicyViolation item) -> item.getSeverity() == null ? "" : item.getSeverity().name())
                .thenComparing(item -> item.getRuleId() == null ? "" : item.getRuleId())
                .thenComparing(item -> item.getTargetId() == null ? "" : item.getTargetId()));

        List<RuleWaiver> waivers = service.listWaivers(session.getAlias(), false);
        LocalDateTime now = LocalDateTime.now();
        long open = violations.stream().filter(item -> item.getStatus() == PolicyViolationStatus.open).count();
        long waived = violations.stream().filter(item -> item.getStatus() == PolicyViolationStatus.waived).count();
        long activeWaivers = waivers.stream().filter(item -> item.isActiveAt(now)).count();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("evaluations", history.stream().limit(HISTORY_LIMIT).toList());
        body.put("currentEvaluations", List.copyOf(currentByRuleSet.values()));
        body.put("violations", violations);
        body.put("waivers", waivers);
        body.put("openViolationCount", open);
        body.put("waivedViolationCount", waived);
        body.put("activeWaiverCount", activeWaivers);
        return body;
    }

    private boolean canWrite(HttpExchange exchange) {
        return session.validateWriteRequest(exchange.getRequestHeaders().getFirst("X-Session-Token"),
                exchange.getRequestHeaders().getFirst("Origin"));
    }

    private static String require(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return String.valueOf(value);
    }
}
