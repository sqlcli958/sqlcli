package com.sqlcli.graph.ui.api;

import com.sqlcli.graph.ui.JsonHttpSupport;
import com.sqlcli.graph.workspace.GraphStatus;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sqlcli.graph.workspace.RelationEvidence;
import com.sqlcli.graph.workspace.RelationWorkspaceEdge;
import com.sqlcli.graph.workspace.ValidationIssueRecord;
import com.sqlcli.graph.workspace.WorkspaceValidator;
import com.sqlcli.graph.ui.service.ApprovalEffects;
import com.sqlcli.runstate.ApprovalBatchRow;
import com.sqlcli.runstate.ApprovalRow;
import com.sqlcli.runstate.RunStateStore;
import com.sqlcli.runstate.TaskEventRow;
import com.sqlcli.runstate.TaskRunRow;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评审页的后端：待审批列表与裁决。
 *
 * <pre>
 * GET  /api/approvals?status=pending&amp;alias=demo&amp;limit=50
 * GET  /api/approvals/{id}          审批行 + 关联 task_run + 事件时间线
 * POST /api/approvals/{id}/decide   {"decision":"approved|rejected","reason":"..."}
 * </pre>
 *
 * <p>这两个端点必须一直是快的：等待方（阻塞中的 CLI 或 UI 请求）就靠它们被放行。
 */
public class ApprovalController {

    private final String allowedOrigin;
    private final JsonHttpSupport json;
    private final RunStateStore runState;
    /** 可为 null（拿不到图谱时详情退化为无候选块）。 */
    private final GraphWorkspaceStore workspaceStore;
    /** 裁决之后真正要做的那件事；null 表示这个服务只读审批不落地（测试用）。 */
    private final ApprovalEffects effects;

    public ApprovalController(String allowedOrigin, JsonHttpSupport json, RunStateStore runState) {
        this(allowedOrigin, json, runState, null, null);
    }

    public ApprovalController(String allowedOrigin, JsonHttpSupport json, RunStateStore runState,
                              GraphWorkspaceStore workspaceStore) {
        this(allowedOrigin, json, runState, workspaceStore, null);
    }

    public ApprovalController(String allowedOrigin, JsonHttpSupport json, RunStateStore runState,
                              GraphWorkspaceStore workspaceStore, ApprovalEffects effects) {
        this.allowedOrigin = allowedOrigin;
        this.json = json;
        this.runState = runState;
        this.workspaceStore = workspaceStore;
        this.effects = effects;
    }

    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        try {
            if (path.startsWith("/api/batches/") && path.endsWith("/decide")) {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                if (!json.requireAllowedOrigin(exchange, allowedOrigin)) return;
                handleBatchDecide(exchange, path);
                return;
            }
            if ("/api/approvals".equals(path)) {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                handleList(exchange);
                return;
            }
            if (path.startsWith("/api/approvals/") && path.endsWith("/decide")) {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                if (!json.requireAllowedOrigin(exchange, allowedOrigin)) return;
                handleDecide(exchange, path);
                return;
            }
            if (path.startsWith("/api/approvals/")) {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                handleDetail(exchange, path);
                return;
            }
            json.writeNotFound(exchange, "Unknown approvals endpoint: " + path);
        } catch (Exception e) {
            json.writeError(exchange, e);
        }
    }

    private void handleList(HttpExchange exchange) throws IOException {
        Map<String, String> params = json.parseQueryParams(exchange);
        // status/kind/alias=all 或缺省表示不过滤，这样审计视图和待办列表能共用一个端点
        String status = wildcard(json.getParam(params, "status", "pending"));
        String kind = wildcard(json.getParam(params, "kind", null));
        String alias = wildcard(json.getParam(params, "alias", null));
        // 两个排除项都是为了同一件事：一个待裁决的审批只能有一个入口。
        // 「审批记录」传 excludeKind=graph（图谱的历史在图谱标签里），
        // 「图谱」传 excludeStatus=pending（还没裁决的在待审批标签里）。
        String excludeKind = wildcard(json.getParam(params, "excludeKind", null));
        String excludeStatus = wildcard(json.getParam(params, "excludeStatus", null));
        Long createdAfter = longParam(params, "createdAfter");
        List<ApprovalRow> rows = runState.listApprovals(alias, status, excludeStatus, kind, excludeKind,
                createdAfter, json.getIntParam(params, "limit", 50),
                json.getIntParam(params, "offset", 0));
        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (ApprovalRow row : rows) {
            items.add(toMap(row));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("approvals", items);
        // 这一页里出现过的批次，随列表一起返回：待审批要按批渲染（一批一张卡、标题是
        // intent），单独再拉一次 /api/batches 会多一次往返，还得自己对齐两边的分页。
        body.put("batches", batchesOn(rows));
        // total 跟着筛选条件走（算总页数）；pending 不跟，它是顶栏角标，永远是"还有几件事等着我"。
        body.put("total", runState.countApprovals(alias, status, excludeStatus, kind, excludeKind,
                createdAfter));
        // pending 不跟 alias 走：CLI 在别的库上等审批时，不该因为顶栏选中的是另一个别名就看不见。
        body.put("pending", runState.countApprovals(null, "pending", null, null));
        json.writeOk(exchange, body);
    }

    /** 这一页条目引用到的批次，去重后逐个取。页大小最多 500，循环查够用。 */
    private List<Map<String, Object>> batchesOn(List<ApprovalRow> rows) {
        java.util.LinkedHashSet<Long> ids = new java.util.LinkedHashSet<>();
        for (ApprovalRow row : rows) {
            if (row.batchId() != null) ids.add(row.batchId());
        }
        List<Map<String, Object>> items = new ArrayList<>(ids.size());
        for (Long id : ids) {
            ApprovalBatchRow batch = runState.findBatch(id);
            if (batch != null) items.add(toMap(batch));
        }
        return items;
    }

    private static Map<String, Object> toMap(ApprovalBatchRow batch) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", batch.id());
        item.put("alias", batch.alias());
        item.put("kind", batch.kind());
        item.put("intent", batch.intent());
        item.put("status", batch.status());
        item.put("recoverable", batch.recoverable());
        item.put("createdAt", batch.createdAt());
        item.put("submittedAt", batch.submittedAt());
        item.put("decidedAt", batch.decidedAt());
        item.put("reason", batch.reason());
        return item;
    }

    /**
     * 整批裁决：{@code POST /api/batches/{id}/decide}。
     *
     * <p>批准 = 落地这一批中所有没被单独否掉的条目，原子。逐条「否掉这条」仍然走
     * {@code /api/approvals/{id}/decide}——那是裁决单位，这里是审批单位。
     */
    private void handleBatchDecide(HttpExchange exchange, String path) throws IOException {
        long id = parseId(path);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = json.readBody(exchange, Map.class);
        String decision = body == null ? null : String.valueOf(body.get("decision"));
        if (!"approved".equals(decision) && !"rejected".equals(decision)) {
            throw new IllegalArgumentException("decision 只能是 approved 或 rejected");
        }
        String reason = body.get("reason") == null ? null : String.valueOf(body.get("reason")).trim();
        if ("rejected".equals(decision) && (reason == null || reason.isEmpty())) {
            throw new IllegalArgumentException("拒绝必须填写理由");
        }
        if (effects == null) {
            throw new IllegalStateException("当前服务不能落地批次");
        }
        try {
            effects.decideBatch(id, "approved".equals(decision), reason, "ui");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        ApprovalBatchRow batch = runState.findBatch(id);
        Map<String, Object> result = new LinkedHashMap<>(batch == null ? Map.of() : toMap(batch));
        List<Map<String, Object>> items = new ArrayList<>();
        for (ApprovalRow item : runState.listBatchItems(id)) items.add(toMap(item));
        result.put("items", items);
        json.writeOk(exchange, result);
    }

    private static String wildcard(String value) {
        return value == null || value.isBlank() || "all".equalsIgnoreCase(value) ? null : value;
    }

    /** 缺失或不是数字都当没传——筛选参数写错不该让整个列表 400。 */
    private static Long longParam(Map<String, String> params, String name) {
        String value = params.get(name);
        if (value == null || value.isBlank()) return null;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void handleDecide(HttpExchange exchange, String path) throws IOException {
        long id = parseId(path);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = json.readBody(exchange, Map.class);
        String decision = body == null ? null : String.valueOf(body.get("decision"));
        if (!"approved".equals(decision) && !"rejected".equals(decision)) {
            throw new IllegalArgumentException("decision 只能是 approved 或 rejected");
        }
        String reason = body.get("reason") == null ? null : String.valueOf(body.get("reason")).trim();
        if ("rejected".equals(decision) && (reason == null || reason.isEmpty())) {
            throw new IllegalArgumentException("拒绝必须填写理由");
        }
        applyEffects(id, decision, reason);
        if (!runState.decideApproval(id, decision, reason)) {
            // 落地动作可能自己就把这条审批结掉了：发布候选边走的 publishRelation 会把同
            // target 的待审批一并置位（它有第二个触发点——批量评审、CLI——不能只靠这里结）。
            // 那种情况下状态已经是我们要的结果，是成功不是冲突；早先一律报错，
            // 用户看到「已经是 approved，无法再次裁决」以为失败，再点一次就撞出并发问题。
            ApprovalRow current = runState.findApproval(id);
            if (current == null) {
                throw new IllegalStateException("审批请求 #" + id + " 不存在");
            }
            if (!decision.equals(current.status())) {
                // 真的冲突：等待方超时置了 expired，或者另一个标签页做了相反的裁决。
                throw new IllegalStateException(
                        "审批请求 #" + id + " 已经是「" + current.status() + "」，无法再次裁决");
            }
        }
        json.writeOk(exchange, toMap(runState.findApproval(id)));
    }

    /**
     * 裁决落地：图谱变更写进图谱、候选边发布或忽略、回滚脚本执行。
     *
     * <p>**先落地、再改状态**。反过来的话，落地失败（冲突、校验不过、库连不上）会留下
     * 一条「已批准」但什么也没发生的记录，人看不出这次批准到底生没生效。
     * 这里抛出去，审批留在 pending，人看清原因后重新决定。
     *
     * <p>拒绝也有落地动作：候选边的拒绝要把它转成 ignored，不然「拒绝」只是记了一笔，
     * 那条边下次挖掘又冒出来。
     */
    private void applyEffects(long id, String decision, String reason) {
        ApprovalRow row = runState.findApproval(id);
        if (row == null || effects == null) return;
        try {
            effects.apply(row, "approved".equals(decision), reason);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /**
     * 审批详情：审批行 + 关联写任务 + 该任务的事件时间线。
     *
     * <p>预检结论（预估影响行数、能不能恢复）只存在于 {@code precheck} 事件的 payload 里，
     * 所以这个端点是评审页看到「批的到底是什么」的唯一来源。
     */
    private void handleDetail(HttpExchange exchange, String path) throws IOException {
        long id = parseId(path);
        ApprovalRow row = runState.findApproval(id);
        if (row == null) {
            json.writeNotFound(exchange, "审批请求 #" + id + " 不存在");
            return;
        }
        Map<String, Object> detail = new LinkedHashMap<>(toMap(row));
        detail.put("taskRun", taskRunMap(row.taskRunId()));
        List<Map<String, Object>> events = new ArrayList<>();
        if (row.taskRunId() != null) {
            for (TaskEventRow event : runState.listTaskEvents(row.taskRunId())) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("eventType", event.eventType());
                item.put("createdAt", event.createdAt());
                item.put("payload", parsePayload(event.payload()));
                events.add(item);
            }
        }
        detail.put("events", events);
        detail.put("graphCandidate", graphCandidate(row));
        json.writeOk(exchange, detail);
    }

    /**
     * kind=graph 且审批挂了关系 targetId 时，从图谱工作区取出候选关系的
     * 字段级 diff（候选=尚未进正式图谱，全部字段都是「新增」）、RelationEvidence
     * 证据列表和该关系当前的校验结果。取不到（图谱缺失 / 关系已被手动删除——
     * 拒绝候选不再删边，只转 {@code ignored}，仍能在这里查到）也要如实说，
     * 不能让详情悄悄少一块。
     */
    private Map<String, Object> graphCandidate(ApprovalRow row) {
        if (workspaceStore == null || !"graph".equals(row.kind())
                || row.targetId() == null || !row.targetId().startsWith("relation:")) {
            return null;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("relationId", row.targetId());
        RelationWorkspaceEdge relation = null;
        GraphWorkspace workspace = null;
        try {
            if (workspaceStore.exists(row.alias())) {
                workspace = workspaceStore.load(row.alias());
                for (RelationWorkspaceEdge edge : workspace.getRelations()) {
                    if (edge.getId().equals(row.targetId())) {
                        relation = edge;
                        break;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            body.put("found", false);
            body.put("note", "图谱工作区读取失败，无法展示候选详情");
            return body;
        }
        if (relation == null) {
            body.put("found", false);
            body.put("note", "关系已不在图谱中（可能已被手动删除）");
            return body;
        }
        body.put("found", true);
        boolean candidate = relation.getStatus() == GraphStatus.candidate;
        body.put("status", relation.getStatus() == null ? null : relation.getStatus().name());
        // 候选 = 尚未进正式图谱，相对现状就是整条「新增」；已发布的关系只展示当前值
        body.put("changeType", candidate ? "create" : null);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("type", relation.getType() == null ? null : relation.getType().name());
        fields.put("from", relation.getFrom());
        fields.put("to", relation.getTo());
        fields.put("joinExpression", relation.getJoinExpression());
        fields.put("confidence", relation.getConfidence());
        fields.put("cardinality", relation.getCardinality() == null ? null : relation.getCardinality().name());
        fields.put("verified", relation.getVerified());
        body.put("fields", fields);
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (RelationEvidence item : relation.getEvidence()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("sourceType", item.getSourceType());
            entry.put("sourceRef", item.getSourceRef());
            entry.put("observedAt", item.getObservedAt() == null ? null : item.getObservedAt().toString());
            evidence.add(entry);
        }
        body.put("evidence", evidence);
        // 现跑一遍 WorkspaceValidator（内存副本，不落盘），只留这条关系的结论
        new WorkspaceValidator().validate(workspace);
        List<Map<String, Object>> validation = new ArrayList<>();
        for (ValidationIssueRecord issue : workspace.getValidationIssues()) {
            if (!row.targetId().equals(issue.getTargetId())) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("severity", issue.getSeverity() == null ? null : issue.getSeverity().name());
            entry.put("code", issue.getCode());
            entry.put("message", issue.getMessage());
            validation.add(entry);
        }
        body.put("validation", validation);
        return body;
    }

    private Map<String, Object> taskRunMap(Long taskRunId) {
        if (taskRunId == null) return null;
        TaskRunRow run = runState.findTaskRun(taskRunId);
        if (run == null) return null;
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", run.id());
        item.put("status", run.status());
        item.put("createdAt", run.createdAt());
        item.put("updatedAt", run.updatedAt());
        return item;
    }

    /** payload 是 JSON 文本，内联成对象返回——前端不该再解一次字符串。解不动就当没有。 */
    private Object parsePayload(String payload) {
        if (payload == null || payload.isBlank()) return null;
        try {
            return json.getObjectMapper().readValue(payload, Map.class);
        } catch (IOException e) {
            return null;
        }
    }

    private long parseId(String path) {
        String[] segments = path.split("/");
        // /api/approvals/{id}/decide
        try {
            return Long.parseLong(segments[3]);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("无效的审批 id");
        }
    }

    private Map<String, Object> toMap(ApprovalRow row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", row.id());
        item.put("alias", row.alias());
        item.put("kind", row.kind());
        item.put("summary", row.summary());
        item.put("detail", row.detail());
        item.put("status", row.status());
        item.put("reason", row.reason());
        item.put("createdAt", row.createdAt());
        item.put("decidedAt", row.decidedAt());
        item.put("taskRunId", row.taskRunId());
        item.put("targetId", row.targetId());
        item.put("batchId", row.batchId());
        item.put("seq", row.seq());
        item.put("sessionId", row.sessionId());
        item.put("agentId", row.agentId());
        item.put("executionId", row.executionId());
        // 批准之后实际发生了什么。评审页据此在「已批准」旁边并排执行结果——
        // 没有它，一条批准后被拒的 SQL 在列表里只有一个绿色的「已批准」。
        item.put("taskStatus", row.taskStatus());
        // 待审批的图谱变更内容：评审页靠它渲染「批的到底是什么」的 before/after
        item.put("payload", parsePayload(row.payload()));
        return item;
    }
}
