package com.sqlcli.graph.ui.api;

import com.sqlcli.graph.workspace.*;
import com.sqlcli.graph.workspace.index.*;
import com.sqlcli.graph.policy.PolicyService;
import com.sqlcli.graph.ui.GraphUiSession;
import com.sqlcli.graph.ui.JsonHttpSupport;
import com.sqlcli.graph.ui.dto.ApiError;
import com.sqlcli.graph.ui.service.WorkspaceLockManager;
import com.sqlcli.graph.ui.service.WorkspaceMutationService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST API controller for workspace mutation operations.
 * <ul>
 *   <li>POST   /api/relations              - create a relation</li>
 *   <li>PATCH  /api/relations/{relationId}  - update a relation</li>
 *   <li>DELETE /api/relations/{relationId}  - delete a relation</li>
 *   <li>POST   /api/relations/{relationId}/reject   - 候选转 ignored（不删除，记原因）</li>
 *   <li>POST   /api/relations/{relationId}/unignore - ignored 转回 candidate</li>
 *   <li>GET    /api/metrics                 - list BI metrics</li>
 *   <li>POST   /api/metrics                 - create/update a metric (idempotent upsert by name)</li>
 *   <li>GET    /api/metrics/{name}/sql      - expand to executable SQL (read-only preview)</li>
 *   <li>GET    /api/validation/issues       - list validation issues</li>
 *   <li>PATCH  /api/tables/{tableId}        - update table description</li>
 *   <li>PATCH  /api/tables/{tableId}/columns/{columnName} - update column description</li>
 *   <li>POST   /api/validate               - run validation</li>
 *   <li>POST   /api/index/rebuild          - rebuild search index</li>
 *   <li>GET    /api/index/status           - get index status</li>
 * </ul>
 * All mutation operations require session token validation.
 */
public class WorkspaceMutationController implements HttpHandler {

    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceMutationController.class);

    // Immutable relation fields that cannot be patched (must delete and recreate)
    private static final Set<String> IMMUTABLE_RELATION_FIELDS = Set.of("type", "from", "to");

    // Allowed fields for table patch。comment 不在白名单里：它是数据库注释的镜像，只由
    // 导入写，UI 没有写入路径——注释错了去库里改、重新导入，不是在这里改一份跟库不一致的文本。
    private static final Set<String> TABLE_PATCH_FIELDS = Set.of(
            "description", "businessName", "tags"
    );

    // Allowed fields for column patch（值域三槽位与 CLI schema edit 写同一批字段；
    // comment 同上不在白名单里）
    private static final Set<String> COLUMN_PATCH_FIELDS = Set.of(
            "description", "businessName", "semanticType", "enumValues", "format", "sampleValues"
    );

    private volatile GraphWorkspace workspace;
    private final GraphUiSession session;
    private final JsonHttpSupport json;
    private final WorkspaceIndexStore indexStore;
    private final WorkspaceMutationService mutationService;
    private final WorkspaceValidator validator;
    private final GraphWorkspaceStore workspaceStore;
    private final WorkspaceLockManager lockManager;
    private final RelationValidator relationValidator;
    private final PolicyService policyService;

    public WorkspaceMutationController(GraphWorkspace workspace, GraphUiSession session,
                                       JsonHttpSupport json, WorkspaceIndexStore indexStore,
                                       GraphWorkspaceStore workspaceStore) {
        this.workspace = workspace;
        this.session = session;
        this.json = json;
        this.indexStore = indexStore;
        this.workspaceStore = workspaceStore;
        this.validator = new WorkspaceValidator();
        this.lockManager = new WorkspaceLockManager();
        this.mutationService = new WorkspaceMutationService(
                workspaceStore, validator, lockManager);
        this.relationValidator = new RelationValidator();
        this.policyService = new PolicyService(workspaceStore);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        try {
            refreshWorkspace();
            // Route to appropriate handler
            if ("/api/relations".equals(path)) {
                if ("POST".equals(method)) {
                    handlePostRelation(exchange);
                } else if ("GET".equals(method)) {
                    handleListRelations(exchange);
                } else {
                    exchange.sendResponseHeaders(405, -1);
                }
            } else if ("/api/relations/review".equals(path)) {
                if ("POST".equals(method)) {
                    handleBatchRelationReview(exchange);
                } else {
                    exchange.sendResponseHeaders(405, -1);
                }
            } else if (path.startsWith("/api/relations/")) {
                String relationId = extractRelationId(path);
                if (relationId == null || relationId.isBlank()) {
                    json.writeNotFound(exchange, "relationId is required");
                    return;
                }
                if ("POST".equals(method) && (path.endsWith("/publish") || path.endsWith("/reject"))) {
                    handleRelationReview(exchange, relationId, path.endsWith("/publish"));
                } else if ("POST".equals(method) && path.endsWith("/unignore")) {
                    handleUnignoreRelation(exchange, relationId);
                } else if ("GET".equals(method)) {
                    handleGetRelation(exchange, relationId);
                } else if ("PATCH".equals(method)) {
                    handlePatchRelation(exchange, relationId);
                } else if ("DELETE".equals(method)) {
                    handleDeleteRelation(exchange, relationId);
                } else {
                    exchange.sendResponseHeaders(405, -1);
                }
            } else if (path.startsWith("/api/terms/") && "DELETE".equals(method)) {
                handleDeleteTerm(exchange, decodeId(path, "/api/terms/"));
            } else if (path.startsWith("/api/lineage/") && "DELETE".equals(method)) {
                handleDeleteLineage(exchange, decodeId(path, "/api/lineage/"));
            } else if ("/api/metrics".equals(path)) {
                if ("POST".equals(method)) {
                    handlePostMetric(exchange);
                } else if ("GET".equals(method)) {
                    handleListMetrics(exchange);
                } else {
                    exchange.sendResponseHeaders(405, -1);
                }
            } else if (path.startsWith("/api/metrics/") && path.endsWith("/sql") && "GET".equals(method)) {
                handleExpandMetric(exchange, path);
            } else if ("/api/validation/issues".equals(path)) {
                if ("GET".equals(method)) {
                    handleGetValidationIssues(exchange);
                } else {
                    exchange.sendResponseHeaders(405, -1);
                }
            } else if (path.startsWith("/api/tables/") && "PATCH".equals(method)) {
                if (path.contains("/columns/")) {
                    handlePatchColumn(exchange, path);
                } else {
                    handlePatchTable(exchange, path);
                }
            } else if ("/api/validate".equals(path) && "POST".equals(method)) {
                handlePostValidate(exchange);
            } else if ("/api/index/rebuild".equals(path) && "POST".equals(method)) {
                handlePostRebuildIndex(exchange);
            } else if ("/api/index/status".equals(path) && "GET".equals(method)) {
                handleGetIndexStatus(exchange);
            } else {
                json.writeNotFound(exchange, "API endpoint not found: " + path);
            }
        } catch (SecurityException e) {
            try { json.writeJson(exchange, 403, ApiError.unauthorized(e.getMessage())); } catch (IOException ignored) {}
        } catch (IllegalArgumentException e) {
            try { json.writeJson(exchange, 400, ApiError.badRequest(e.getMessage())); } catch (IOException ignored) {}
        } catch (Exception e) {
            if (JsonHttpSupport.isClientDisconnect(e)) {
                LOG.debug("Client disconnected: {}", path);
            } else {
                LOG.error("Graph UI mutation API error: {} {}", method, path, e);
                try {
                    json.writeError(exchange, e);
                } catch (IOException ignored) {
                    // Connection already closed
                }
            }
        }
    }

    private void refreshWorkspace() throws IOException {
        workspace = workspaceStore.load(session.getAlias());
        session.setWorkspaceRevision(workspace.getManifest().getRevision());
    }

    // --- GET /api/metrics ---

    /**
     * BI 指标列表。和关系一样不分页整份返回——指标是人工声明的口径，几十条量级，
     * 真到翻不动的规模该做的是分组而不是加 offset。
     *
     * <p>列 id 与关系 id 原样返回，不在服务端翻译成可读名：
     * {@code column:alias:schema.table.column} 切成 {@code schema.table.column} 是前端
     * 三行字符串操作，服务端替它做一遍只是多一份要同步的形态。
     */
    private void handleListMetrics(HttpExchange exchange) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("metrics", workspace.getMetrics().values().stream()
                .sorted(Comparator.comparing(MetricRecord::getName, String.CASE_INSENSITIVE_ORDER))
                .toList());
        body.put("revision", workspace.getManifest().getRevision());
        json.writeOk(exchange, body);
    }

    // --- POST /api/metrics ---

    /**
     * 建或改一条指标，按 name 幂等 upsert（与 CLI {@code schema add-metric} 同一语义）。
     *
     * <p><b>UI 写入的 actor 是 human，所以指标直接 verified</b>——CLI 写的是候选
     * （{@code GraphStatus.forActor(agent)}），这一条正是「人在 UI 里定义口径」与
     * 「Agent 从代码里猜口径」的分界。口径本来就该是人声明的（见
     * {@code MetricRecord#joinPath} 类注释里 fan trap / chasm trap 那两段）。
     *
     * <p>列引用收 {@code schema.table.column}，走 {@link GraphWorkspace#resolveColumnId}——
     * 与 CLI 同一份解析。解析不出来当场 400 并说清是哪个字段的哪个值，不静默吞成
     * null：一个拼错的维度列如果被吞掉，展开出来的 SQL 少一个 GROUP BY 列，
     * 不报错、有结果、数是错的。
     */
    @SuppressWarnings("unchecked")
    private void handlePostMetric(HttpExchange exchange) throws IOException {
        if (!validateSession(exchange)) {
            json.writeJson(exchange, 403, ApiError.unauthorized("Invalid or missing session token"));
            return;
        }
        Map<String, Object> body = json.readBody(exchange, Map.class);
        if (body == null || body.isEmpty()) {
            json.writeJson(exchange, 400, ApiError.badRequest("Request body is required"));
            return;
        }
        String name = (String) body.get("name");
        if (name == null || name.isBlank()) {
            json.writeJson(exchange, 400, ApiError.badRequest("'name' is required"));
            return;
        }
        String expression = (String) body.get("expression");
        // F4b：比率指标（numerator/denominator）没有顶层 expression，不能再用它当"有没有
        // 声明口径"的唯一判据——两者哪个都没有才是真的没法展开。
        MetricRecord.MetricComponent numerator = parseMetricComponent(body.get("numerator"), "numerator");
        MetricRecord.MetricComponent denominator = parseMetricComponent(body.get("denominator"), "denominator");
        boolean ratio = numerator != null && denominator != null;
        if ((expression == null || expression.isBlank()) && !ratio) {
            // expression 缺了展开必炸（MetricSqlExpander 第一件事就是查它），在定义时就拦住
            json.writeJson(exchange, 400, ApiError.badRequest(
                    "'expression' is required，或者改用 numerator + denominator 声明成比率指标："
                            + "没有聚合口径的 metric 展不开 SQL"));
            return;
        }
        if ((numerator != null) != (denominator != null)) {
            json.writeJson(exchange, 400, ApiError.badRequest(
                    "numerator 和 denominator 必须成对声明——只有一个不构成完整的比率口径"));
            return;
        }
        MetricRecord.Additivity additivity;
        try {
            additivity = parseAdditivity(body.get("additivity"));
        } catch (IllegalArgumentException e) {
            json.writeJson(exchange, 400, ApiError.badRequest(e.getMessage()));
            return;
        }
        long expectedRevision = requireLong(body, "expectedRevision");
        String alias = session.getAlias();

        String grainColumnId;
        List<String> dimensionIds = new ArrayList<>();
        try {
            grainColumnId = resolveMetricColumn(body.get("grainColumn"), "grainColumn");
            for (Object ref : asList(body.get("dimensions"))) {
                dimensionIds.add(resolveMetricColumn(ref, "dimensions"));
            }
        } catch (IllegalArgumentException e) {
            json.writeJson(exchange, 400, ApiError.badRequest(e.getMessage()));
            return;
        }
        List<MetricRecord.MetricJoinStep> joinSteps = new ArrayList<>();
        for (Object raw : asList(body.get("joinPath"))) {
            if (!(raw instanceof Map<?, ?> step)) {
                json.writeJson(exchange, 400, ApiError.badRequest(
                        "joinPath 的每一项要是 {relationId, joinType}"));
                return;
            }
            String relationId = String.valueOf(step.get("relationId"));
            if (findRelationById(relationId) == null) {
                json.writeJson(exchange, 400, ApiError.badRequest("关系不存在: " + relationId));
                return;
            }
            Object typeToken = step.get("joinType");
            MetricRecord.MetricJoinStep parsed = new MetricRecord.MetricJoinStep();
            parsed.setRelationId(relationId);
            try {
                parsed.setJoinType(typeToken == null ? MetricRecord.MetricJoinType.inner
                        : MetricRecord.MetricJoinType.valueOf(String.valueOf(typeToken).toLowerCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                json.writeJson(exchange, 400, ApiError.badRequest(
                        "未知 joinType: " + typeToken + "（合法值: inner, left）"));
                return;
            }
            joinSteps.add(parsed);
        }
        List<String> metricAliases = asList(body.get("aliases")).stream().map(String::valueOf).toList();
        List<String> grains = asList(body.get("grains")).stream().map(String::valueOf).toList();
        String businessName = (String) body.get("businessName");
        String filters = (String) body.get("filters");
        String reason = body.get("reason") == null ? "UI 定义指标" : String.valueOf(body.get("reason"));

        WorkspaceMutationService.MutationResult result = mutationService.mutate(alias, expectedRevision,
                GraphActor.human, reason, GraphIds.metricId(alias, name), current -> {
                    String id = GraphIds.metricId(alias, name);
                    MetricRecord metric = current.getMetrics().computeIfAbsent(id,
                            ignored -> MetricRecord.create(alias, name, GraphActor.human));
                    // 比率结构与顶层 expression/filters 互斥：声明了 numerator/denominator 就清掉
                    // 顶层 expression，避免留一份跟真正生效的口径对不上的旧值误导评审。
                    metric.setExpression(ratio ? null : expression);
                    metric.setFilters(ratio ? null : filters);
                    metric.setNumerator(numerator);
                    metric.setDenominator(denominator);
                    metric.setAdditivity(additivity);
                    if (businessName != null && !businessName.isBlank()) {
                        metric.setBusinessName(businessName);
                    }
                    metric.setAliases(new ArrayList<>(metricAliases));
                    if (grainColumnId != null || !grains.isEmpty()) {
                        MetricRecord.MetricGrain grain = new MetricRecord.MetricGrain();
                        grain.setTimeColumn(grainColumnId);
                        grain.setGrains(new ArrayList<>(grains));
                        metric.setGrain(grain);
                    } else {
                        metric.setGrain(null);
                    }
                    metric.setDimensions(new ArrayList<>(dimensionIds));
                    metric.setJoinPath(new ArrayList<>(joinSteps));
                    // 人声明的口径就是权威口径，没有「等谁发布」这一步
                    metric.setStatus(GraphStatus.verified);
                    metric.setVerified(true);
                    metric.setConfidence(1.0);
                    metric.touch(GraphActor.human);
                    return new WorkspaceMutationService.MutationOutcome(id, ChangeOperation.upsert);
                });

        if (!result.isSuccess()) {
            boolean conflict = result.getErrors().stream().anyMatch(e -> e.contains("revision mismatch"));
            json.writeJson(exchange, conflict ? 409 : 400,
                    ApiError.badRequest(String.join("; ", result.getErrors())));
            return;
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("newRevision", result.getNewRevision());
        response.put("metricId", result.getTargetId());
        response.put("changeId", result.getChangeId());
        response.put("pendingApprovalId", result.getPendingApprovalId());
        json.writeOk(exchange, response);
    }

    // --- GET /api/metrics/{name}/sql?grain=day&dimensions=a,b&timeFrom=&timeTo= ---

    /**
     * 把一条指标展开成可执行 SQL。<b>纯离线只读</b>：方言取图谱自带的
     * {@code dataSource.dbType}（导入时记下的），不连库、不落库，与 CLI
     * {@code expand-metric} 同一条路径。
     *
     * <p>展开失败（粒度不在声明范围、库类型不支持时间截断、joinPath 断了）回 400
     * 并原样带出 {@code MetricExpansionException} 的话——那句话是写给定义指标的人看的，
     * 换成通用错误信息等于把唯一有用的信息扔了。
     */
    private void handleExpandMetric(HttpExchange exchange, String path) throws IOException {
        String encoded = path.substring("/api/metrics/".length(), path.length() - "/sql".length());
        String name = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        MetricRecord metric = workspace.getMetrics().get(GraphIds.metricId(session.getAlias(), name));
        if (metric == null) {
            json.writeNotFound(exchange, "Metric not found: " + name);
            return;
        }
        Map<String, String> params = json.parseQueryParams(exchange);
        List<String> dimensionIds = new ArrayList<>();
        String dimensionsCsv = json.getParam(params, "dimensions", null);
        if (dimensionsCsv != null && !dimensionsCsv.isBlank()) {
            for (String ref : dimensionsCsv.split(",")) {
                try {
                    dimensionIds.add(resolveMetricColumn(ref.trim(), "dimensions"));
                } catch (IllegalArgumentException e) {
                    json.writeJson(exchange, 400, ApiError.badRequest(e.getMessage()));
                    return;
                }
            }
        }
        String dbType = workspace.getDataSource() != null ? workspace.getDataSource().getDbType() : null;
        String sql;
        try {
            sql = com.sqlcli.metric.MetricSqlExpander.expand(workspace, metric,
                    com.sqlcli.strategy.DatabaseStrategies.resolve(dbType),
                    new com.sqlcli.metric.MetricSqlRequest(
                            json.getParam(params, "grain", null),
                            json.getParam(params, "timeFrom", null),
                            json.getParam(params, "timeTo", null),
                            dimensionIds));
        } catch (com.sqlcli.metric.MetricExpansionException e) {
            json.writeJson(exchange, 400, ApiError.badRequest(e.getMessage()));
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("metric", metric.getName());
        body.put("sql", sql);
        json.writeOk(exchange, body);
    }

    /** 列引用 -> 列 id；解析不出来抛 IllegalArgumentException，由调用方转 400。 */
    private String resolveMetricColumn(Object ref, String field) {
        if (ref == null || String.valueOf(ref).isBlank()) {
            return null;
        }
        String id = workspace.resolveColumnId(session.getAlias(), String.valueOf(ref));
        if (id == null) {
            throw new IllegalArgumentException("列不存在（" + field + "）: " + ref
                    + "，格式应为 schema.table.column");
        }
        return id;
    }

    private static List<Object> asList(Object raw) {
        return raw instanceof List<?> list ? new ArrayList<>(list) : List.of();
    }

    /** {@code {expression, filters}} -> {@link MetricRecord.MetricComponent}；{@code null}/
     * 没有 expression 都当作"没声明这一侧"，不是抛错——numerator/denominator 各自可选，
     * 由调用方判断"是否成对"。 */
    @SuppressWarnings("unchecked")
    private static MetricRecord.MetricComponent parseMetricComponent(Object raw, String field) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Object expression = ((Map<String, Object>) map).get("expression");
        if (expression == null || String.valueOf(expression).isBlank()) {
            return null;
        }
        MetricRecord.MetricComponent component = new MetricRecord.MetricComponent();
        component.setExpression(String.valueOf(expression));
        Object filters = ((Map<String, Object>) map).get("filters");
        if (filters != null && !String.valueOf(filters).isBlank()) {
            component.setFilters(String.valueOf(filters));
        }
        return component;
    }

    /** {@code null}/空串都当作"没标注"；标注了就必须是合法枚举值，拼错了当场 400 说清楚，
     * 不要悄悄存成 null——那会让人以为标注生效了。 */
    private static MetricRecord.Additivity parseAdditivity(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            return null;
        }
        try {
            return MetricRecord.Additivity.valueOf(String.valueOf(raw).trim().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知 additivity: " + raw
                    + "（合法值: additive, semi_additive, non_additive）");
        }
    }

    // --- GET /api/relations?status=candidate,ignored ---

    /**
     * 按状态列关系。评审页的图谱标签靠它拿到「这个数据源还有哪些候选边等着评审」——
     * 图谱页只按表给关系，翻遍每张表才能凑齐候选队列，那不是一个评审入口该有的样子。
     *
     * <p>ponytail: 不分页，整份返回给前端切。候选边是人工评审的量级（几十到几百），
     * 真到了翻不动的规模，该做的是先按 schema 收窄而不是在这里加 offset。
     */
    private void handleListRelations(HttpExchange exchange) throws IOException {
        Map<String, String> params = json.parseQueryParams(exchange);
        String raw = json.getParam(params, "status", null);
        Set<String> wanted = raw == null || raw.isBlank() || "all".equals(raw)
                ? null
                : new LinkedHashSet<>(Arrays.asList(raw.split(",")));
        List<RelationWorkspaceEdge> matched = new ArrayList<>();
        for (RelationWorkspaceEdge edge : workspace.getRelations()) {
            String status = edge.getStatus() == null ? null : edge.getStatus().name();
            if (wanted == null || (status != null && wanted.contains(status))) {
                matched.add(edge);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("relations", matched);
        body.put("revision", workspace.getManifest().getRevision());
        json.writeOk(exchange, body);
    }

    // --- GET /api/relations/{relationId} ---

    private void handleGetRelation(HttpExchange exchange, String relationId) throws IOException {
        RelationWorkspaceEdge relation = findRelationById(relationId);
        if (relation == null) {
            json.writeNotFound(exchange, "Relation not found: " + relationId);
            return;
        }
        json.writeOk(exchange, relation);
    }

    // --- POST /api/relations ---

    @SuppressWarnings("unchecked")
    private void handlePostRelation(HttpExchange exchange) throws IOException {
        if (!validateSession(exchange)) {
            json.writeJson(exchange, 403, ApiError.unauthorized("Invalid or missing session token"));
            return;
        }

        Map<String, Object> body = json.readBody(exchange, Map.class);
        if (body == null || body.isEmpty()) {
            json.writeJson(exchange, 400, ApiError.badRequest("Request body is required"));
            return;
        }

        // Parse required fields
        String typeStr = requireString(body, "type");
        String from = requireString(body, "from");
        String to = requireString(body, "to");
        long expectedRevision = requireLong(body, "expectedRevision");

        RelationType type;
        try {
            type = RelationType.valueOf(typeStr);
        } catch (IllegalArgumentException e) {
            json.writeJson(exchange, 400, ApiError.badRequest("Unknown relation type: " + typeStr));
            return;
        }

        // Forbid creating foreign_key
        if (type == RelationType.foreign_key) {
            json.writeJson(exchange, 403, ApiError.unauthorized(
                    "Cannot create foreign_key relation via API; it is managed by the system"));
            return;
        }

        // Validate endpoint existence
        if (from == null || from.isBlank()) {
            json.writeJson(exchange, 400, ApiError.badRequest("'from' is required"));
            return;
        }
        if (to == null || to.isBlank()) {
            json.writeJson(exchange, 400, ApiError.badRequest("'to' is required"));
            return;
        }

        // Validate endpoint types via RelationValidator
        GraphObjectKind fromKind = workspace.resolveNodeKind(from);
        GraphObjectKind toKind = workspace.resolveNodeKind(to);
        if (fromKind == null) {
            json.writeJson(exchange, 400, ApiError.badRequest("From endpoint not found: " + from));
            return;
        }
        if (toKind == null) {
            json.writeJson(exchange, 400, ApiError.badRequest("To endpoint not found: " + to));
            return;
        }

        // Parse optional fields
        RelationCardinality cardinality = parseEnum(body, "cardinality", RelationCardinality.class);
        String joinExpression = (String) body.get("joinExpression");
        Double confidence = parseDouble(body, "confidence");
        Boolean verified = (Boolean) body.get("verified");
        String reason = (String) body.get("reason");

        // Validate confidence range
        if (confidence != null && (confidence < 0 || confidence > 1)) {
            json.writeJson(exchange, 400, ApiError.badRequest("Confidence must be between 0 and 1"));
            return;
        }

        // Validate via RelationValidator
        RelationValidator.ValidationResult vr = relationValidator.validate(
                type, fromKind, toKind, confidence != null ? confidence : type.defaultConfidence(), verified);
        if (!vr.isValid()) {
            json.writeJson(exchange, 400, ApiError.badRequest(String.join("; ", vr.getErrors())));
            return;
        }

        // Call mutation service
        WorkspaceMutationService.MutationResult result = mutationService.addRelation(
                session.getAlias(), type, from, to, cardinality, joinExpression,
                confidence, verified, expectedRevision, reason);

        if (!result.isSuccess()) {
            if (result.getErrors().stream().anyMatch(e -> e.contains("revision mismatch"))) {
                json.writeJson(exchange, 409, ApiError.badRequest(
                        "Revision conflict: " + String.join("; ", result.getErrors())));
            } else {
                json.writeJson(exchange, 400, ApiError.badRequest(String.join("; ", result.getErrors())));
            }
            return;
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("newRevision", result.getNewRevision());
        response.put("pendingApprovalId", result.getPendingApprovalId());
        response.put("changeId", result.getChangeId());
        response.put("relationId", result.getTargetId());
        json.writeOk(exchange, response);
    }

    // --- PATCH /api/relations/{relationId} ---

    @SuppressWarnings("unchecked")
    private void handlePatchRelation(HttpExchange exchange, String relationId) throws IOException {
        if (!validateSession(exchange)) {
            json.writeJson(exchange, 403, ApiError.unauthorized("Invalid or missing session token"));
            return;
        }

        Map<String, Object> body = json.readBody(exchange, Map.class);
        if (body == null || body.isEmpty()) {
            json.writeJson(exchange, 400, ApiError.badRequest("Request body is required"));
            return;
        }

        long expectedRevision = requireLong(body, "expectedRevision");
        Map<String, Object> patch = (Map<String, Object>) body.get("patch");
        if (patch == null || patch.isEmpty()) {
            json.writeJson(exchange, 400, ApiError.badRequest("'patch' is required and must not be empty"));
            return;
        }

        // Reject immutable fields
        List<String> immutable = new ArrayList<>();
        for (String field : IMMUTABLE_RELATION_FIELDS) {
            if (patch.containsKey(field)) {
                immutable.add(field);
            }
        }
        if (!immutable.isEmpty()) {
            json.writeJson(exchange, 400, ApiError.badRequest(
                    "Cannot modify immutable field(s): " + String.join(", ", immutable)
                            + "; delete and recreate the relation instead"));
            return;
        }

        // Validate confidence range if present
        Double confidence = parseDouble(patch, "confidence");
        if (confidence != null && (confidence < 0 || confidence > 1)) {
            json.writeJson(exchange, 400, ApiError.badRequest("Confidence must be between 0 and 1"));
            return;
        }

        String reason = (String) body.get("reason");

        WorkspaceMutationService.MutationResult result = mutationService.updateRelation(
                session.getAlias(), relationId, patch, expectedRevision, reason);

        if (!result.isSuccess()) {
            if (result.getErrors().stream().anyMatch(e -> e.contains("revision mismatch"))) {
                json.writeJson(exchange, 409, ApiError.badRequest(
                        "Revision conflict: " + String.join("; ", result.getErrors())));
            } else {
                json.writeJson(exchange, 400, ApiError.badRequest(String.join("; ", result.getErrors())));
            }
            return;
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("newRevision", result.getNewRevision());
        response.put("pendingApprovalId", result.getPendingApprovalId());
        response.put("changeId", result.getChangeId());
        json.writeOk(exchange, response);
    }

    // --- DELETE /api/relations/{relationId} ---

    private void handleDeleteRelation(HttpExchange exchange, String relationId) throws IOException {
        if (!validateSession(exchange)) {
            json.writeJson(exchange, 403, ApiError.unauthorized("Invalid or missing session token"));
            return;
        }

        // Check if the relation is foreign_key before calling service
        // (service also checks, but we want to return 403 specifically)
        RelationWorkspaceEdge relation = findRelationById(relationId);
        if (relation == null) {
            json.writeNotFound(exchange, "Relation not found: " + relationId);
            return;
        }
        if (relation.getType() == RelationType.foreign_key) {
            json.writeJson(exchange, 403, ApiError.unauthorized(
                    "Cannot delete foreign_key relation; it is managed by the system"));
            return;
        }

        // Parse expectedRevision from query params for DELETE
        Map<String, String> params = json.parseQueryParams(exchange);
        long expectedRevision;
        try {
            expectedRevision = Long.parseLong(params.getOrDefault("expectedRevision",
                    String.valueOf(workspace.getManifest().getRevision())));
        } catch (NumberFormatException e) {
            json.writeJson(exchange, 400, ApiError.badRequest("Invalid expectedRevision"));
            return;
        }

        String reason = params.get("reason");

        WorkspaceMutationService.MutationResult result = mutationService.deleteRelation(
                session.getAlias(), relationId, expectedRevision, reason);

        if (!result.isSuccess()) {
            if (result.getErrors().stream().anyMatch(e -> e.contains("revision mismatch"))) {
                json.writeJson(exchange, 409, ApiError.badRequest(
                        "Revision conflict: " + String.join("; ", result.getErrors())));
            } else {
                json.writeJson(exchange, 400, ApiError.badRequest(String.join("; ", result.getErrors())));
            }
            return;
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("newRevision", result.getNewRevision());
        response.put("pendingApprovalId", result.getPendingApprovalId());
        response.put("changeId", result.getChangeId());
        json.writeOk(exchange, response);
    }

    // --- POST /api/relations/{relationId}/publish | /reject ---

    @SuppressWarnings("unchecked")
    private void handleRelationReview(HttpExchange exchange, String relationId, boolean publish)
            throws IOException {
        if (!validateSession(exchange)) {
            json.writeJson(exchange, 403, ApiError.unauthorized("Invalid or missing session token"));
            return;
        }
        RelationWorkspaceEdge relation = findRelationById(relationId);
        if (relation == null) {
            json.writeNotFound(exchange, "Relation not found: " + relationId);
            return;
        }
        Map<String, Object> body = json.readBody(exchange, Map.class);
        if (body == null) body = Map.of();
        long expectedRevision = body.get("expectedRevision") instanceof Number n
                ? n.longValue() : workspace.getManifest().getRevision();
        String reason = (String) body.get("reason");
        if (reason == null || reason.isBlank()) {
            reason = publish ? "publish candidate relation" : "reject candidate relation";
        }

        WorkspaceMutationService.MutationResult result = publish
                ? mutationService.publishRelation(session.getAlias(), relationId, expectedRevision, reason)
                : mutationService.rejectRelation(session.getAlias(), relationId, expectedRevision, reason);

        if (!result.isSuccess()) {
            if (result.getErrors().stream().anyMatch(e -> e.contains("revision mismatch"))) {
                json.writeJson(exchange, 409, ApiError.badRequest(
                        "Revision conflict: " + String.join("; ", result.getErrors())));
            } else {
                json.writeJson(exchange, 400, ApiError.badRequest(String.join("; ", result.getErrors())));
            }
            return;
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("newRevision", result.getNewRevision());
        response.put("pendingApprovalId", result.getPendingApprovalId());
        response.put("changeId", result.getChangeId());
        response.put("relationId", relationId);
        json.writeOk(exchange, response);
    }

    // --- POST /api/relations/{relationId}/unignore ---

    /** 撤销「已忽略」：状态改回 candidate，重新进入待审核队列。 */
    @SuppressWarnings("unchecked")
    private void handleUnignoreRelation(HttpExchange exchange, String relationId) throws IOException {
        if (!validateSession(exchange)) {
            json.writeJson(exchange, 403, ApiError.unauthorized("Invalid or missing session token"));
            return;
        }
        RelationWorkspaceEdge relation = findRelationById(relationId);
        if (relation == null) {
            json.writeNotFound(exchange, "Relation not found: " + relationId);
            return;
        }
        Map<String, Object> body = json.readBody(exchange, Map.class);
        if (body == null) body = Map.of();
        long expectedRevision = body.get("expectedRevision") instanceof Number n
                ? n.longValue() : workspace.getManifest().getRevision();
        String reason = (String) body.get("reason");
        if (reason == null || reason.isBlank()) {
            reason = "undo rejection";
        }

        WorkspaceMutationService.MutationResult result = mutationService.unignoreRelation(
                session.getAlias(), relationId, expectedRevision, reason);

        if (!result.isSuccess()) {
            if (result.getErrors().stream().anyMatch(e -> e.contains("revision mismatch"))) {
                json.writeJson(exchange, 409, ApiError.badRequest(
                        "Revision conflict: " + String.join("; ", result.getErrors())));
            } else {
                json.writeJson(exchange, 400, ApiError.badRequest(String.join("; ", result.getErrors())));
            }
            return;
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("newRevision", result.getNewRevision());
        response.put("pendingApprovalId", result.getPendingApprovalId());
        response.put("changeId", result.getChangeId());
        response.put("relationId", relationId);
        json.writeOk(exchange, response);
    }

    // --- POST /api/relations/review （批量发布 / 拒绝候选关系） ---

    /**
     * 批量复用单条 publish/reject 的服务逻辑，服务端逐条处理并返回每条结果——
     * 部分失败时前端能看出具体哪条没过。每条成功都会 bump revision，
     * 所以每次循环重读当前 revision 而不是让前端传一个必然过期的值。
     */
    @SuppressWarnings("unchecked")
    private void handleBatchRelationReview(HttpExchange exchange) throws IOException {
        if (!validateSession(exchange)) {
            json.writeJson(exchange, 403, ApiError.unauthorized("Invalid or missing session token"));
            return;
        }
        Map<String, Object> body = json.readBody(exchange, Map.class);
        if (body == null) {
            json.writeJson(exchange, 400, ApiError.badRequest("Request body is required"));
            return;
        }
        String action = String.valueOf(body.get("action"));
        boolean publish = "publish".equals(action);
        if (!publish && !"reject".equals(action)) {
            json.writeJson(exchange, 400, ApiError.badRequest("action must be 'publish' or 'reject'"));
            return;
        }
        Object rawIds = body.get("relationIds");
        if (!(rawIds instanceof List<?> ids) || ids.isEmpty()) {
            json.writeJson(exchange, 400, ApiError.badRequest("'relationIds' is required and must not be empty"));
            return;
        }
        String reason = (String) body.get("reason");
        if (reason == null || reason.isBlank()) {
            reason = publish ? "batch publish candidate relations" : "batch reject candidate relations";
        }

        List<Map<String, Object>> results = new ArrayList<>();
        int succeeded = 0;
        long revision = workspace.getManifest().getRevision();
        for (Object rawId : ids) {
            String relationId = String.valueOf(rawId);
            WorkspaceMutationService.MutationResult result = publish
                    ? mutationService.publishRelation(session.getAlias(), relationId, revision, reason)
                    : mutationService.rejectRelation(session.getAlias(), relationId, revision, reason);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("relationId", relationId);
            item.put("success", result.isSuccess());
            if (result.isSuccess()) {
                succeeded++;
                revision = result.getNewRevision();
            } else {
                item.put("error", String.join("; ", result.getErrors()));
                // 失败不 bump revision，但也可能是并发写造成的 revision 漂移——重读一次再继续
                try {
                    revision = workspaceStore.load(session.getAlias()).getManifest().getRevision();
                } catch (IOException ignored) {
                    // 读不到就沿用手里的值，下一条自然会报同样的错
                }
            }
            results.add(item);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("results", results);
        response.put("succeeded", succeeded);
        response.put("failed", results.size() - succeeded);
        response.put("newRevision", revision);
        json.writeOk(exchange, response);
    }

    // --- GET /api/validation/issues?targetId=&severity= ---

    private void handleGetValidationIssues(HttpExchange exchange) throws IOException {
        Map<String, String> params = json.parseQueryParams(exchange);
        String targetId = params.get("targetId");
        String severityFilter = params.get("severity");

        // Reload workspace to get latest validation issues
        GraphWorkspace freshWorkspace = workspaceStore.load(session.getAlias());
        List<ValidationIssueRecord> issues = new ArrayList<>(freshWorkspace.getValidationIssues());
        issues.addAll(policyService.currentValidationIssues(session.getAlias()));

        // Filter by targetId
        if (targetId != null && !targetId.isBlank()) {
            issues = issues.stream()
                    .filter(v -> targetId.equals(v.getTargetId()))
                    .collect(Collectors.toList());
        }

        // Filter by severity
        if (severityFilter != null && !severityFilter.isBlank()) {
            ValidationSeverity severity;
            try {
                severity = ValidationSeverity.valueOf(severityFilter);
            } catch (IllegalArgumentException e) {
                json.writeJson(exchange, 400, ApiError.badRequest(
                        "Invalid severity: " + severityFilter + "; valid values: error, warning, info"));
                return;
            }
            final ValidationSeverity finalSeverity = severity;
            issues = issues.stream()
                    .filter(v -> v.getSeverity() == finalSeverity)
                    .collect(Collectors.toList());
        }

        json.writeOk(exchange, Map.of("issues", issues, "total", issues.size()));
    }

    // --- PATCH /api/tables/{tableId} ---

    private void handlePatchTable(HttpExchange exchange, String path) throws IOException {
        // Validate session token
        if (!validateSession(exchange)) {
            json.writeJson(exchange, 403, ApiError.unauthorized("Invalid or missing session token"));
            return;
        }

        // Extract tableId from path: /api/tables/{tableId}
        String encoded = path.substring("/api/tables/".length());
        String tableId;
        try {
            tableId = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        } catch (Exception e) {
            tableId = encoded;
        }

        // Parse request body
        @SuppressWarnings("unchecked")
        Map<String, Object> body = json.readBody(exchange, Map.class);
        if (body == null) {
            json.writeJson(exchange, 400, ApiError.badRequest("Request body is required"));
            return;
        }

        // Extract expectedRevision
        Object expectedRevisionObj = body.get("expectedRevision");
        if (expectedRevisionObj == null) {
            json.writeJson(exchange, 400, ApiError.badRequest("expectedRevision is required"));
            return;
        }
        long expectedRevision;
        if (expectedRevisionObj instanceof Number n) {
            expectedRevision = n.longValue();
        } else {
            try {
                expectedRevision = Long.parseLong(expectedRevisionObj.toString());
            } catch (NumberFormatException e) {
                json.writeJson(exchange, 400, ApiError.badRequest("expectedRevision must be a number"));
                return;
            }
        }

        // Extract patch
        @SuppressWarnings("unchecked")
        Map<String, Object> patch = (Map<String, Object>) body.get("patch");
        if (patch == null || patch.isEmpty()) {
            json.writeJson(exchange, 400, ApiError.badRequest("patch is required and cannot be empty"));
            return;
        }

        // Validate patch fields
        List<String> invalidFields = new ArrayList<>();
        for (String field : patch.keySet()) {
            if (!TABLE_PATCH_FIELDS.contains(field)) {
                invalidFields.add(field);
            }
        }
        if (!invalidFields.isEmpty()) {
            json.writeJson(exchange, 400, ApiError.badRequest(
                    "Invalid patch fields: " + String.join(", ", invalidFields) +
                    ". Allowed fields: " + String.join(", ", TABLE_PATCH_FIELDS)));
            return;
        }

        // Extract reason
        String reason = (String) body.get("reason");

        // Resolve tableId to actual table ID
        String resolvedTableId = resolveTableId(tableId);
        if (resolvedTableId == null) {
            json.writeNotFound(exchange, "Table not found: " + tableId);
            return;
        }

        // Call mutation service
        WorkspaceMutationService.MutationResult result = mutationService.updateTableDescription(
                session.getAlias(), resolvedTableId, patch, expectedRevision, reason);

        if (result.isSuccess()) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("newRevision", result.getNewRevision());
            response.put("pendingApprovalId", result.getPendingApprovalId());
            response.put("changeId", result.getChangeId());
            json.writeOk(exchange, response);
        } else {
            // Check for revision conflict
            if (result.getErrors().stream().anyMatch(e -> e.contains("revision mismatch"))) {
                json.writeJson(exchange, 409, ApiError.badRequest(
                        "Revision conflict: " + String.join("; ", result.getErrors())));
            } else {
                json.writeJson(exchange, 400, ApiError.badRequest(
                        String.join("; ", result.getErrors())));
            }
        }
    }

    // --- PATCH /api/tables/{tableId}/columns/{columnName} ---

    private void handlePatchColumn(HttpExchange exchange, String path) throws IOException {
        // Validate session token
        if (!validateSession(exchange)) {
            json.writeJson(exchange, 403, ApiError.unauthorized("Invalid or missing session token"));
            return;
        }

        // Extract tableId and columnName from path: /api/tables/{tableId}/columns/{columnName}
        String suffix = path.substring("/api/tables/".length());
        int columnsIdx = suffix.indexOf("/columns/");
        if (columnsIdx < 0) {
            json.writeNotFound(exchange, "Invalid path format");
            return;
        }

        String encodedTableId = suffix.substring(0, columnsIdx);
        String encodedColumnName = suffix.substring(columnsIdx + "/columns/".length());

        String tableId;
        String columnName;
        try {
            tableId = URLDecoder.decode(encodedTableId, StandardCharsets.UTF_8);
            columnName = URLDecoder.decode(encodedColumnName, StandardCharsets.UTF_8);
        } catch (Exception e) {
            tableId = encodedTableId;
            columnName = encodedColumnName;
        }

        // Parse request body
        @SuppressWarnings("unchecked")
        Map<String, Object> body = json.readBody(exchange, Map.class);
        if (body == null) {
            json.writeJson(exchange, 400, ApiError.badRequest("Request body is required"));
            return;
        }

        // Extract expectedRevision
        Object expectedRevisionObj = body.get("expectedRevision");
        if (expectedRevisionObj == null) {
            json.writeJson(exchange, 400, ApiError.badRequest("expectedRevision is required"));
            return;
        }
        long expectedRevision;
        if (expectedRevisionObj instanceof Number n) {
            expectedRevision = n.longValue();
        } else {
            try {
                expectedRevision = Long.parseLong(expectedRevisionObj.toString());
            } catch (NumberFormatException e) {
                json.writeJson(exchange, 400, ApiError.badRequest("expectedRevision must be a number"));
                return;
            }
        }

        // Extract patch
        @SuppressWarnings("unchecked")
        Map<String, Object> patch = (Map<String, Object>) body.get("patch");
        if (patch == null || patch.isEmpty()) {
            json.writeJson(exchange, 400, ApiError.badRequest("patch is required and cannot be empty"));
            return;
        }

        // Validate patch fields
        List<String> invalidFields = new ArrayList<>();
        for (String field : patch.keySet()) {
            if (!COLUMN_PATCH_FIELDS.contains(field)) {
                invalidFields.add(field);
            }
        }
        if (!invalidFields.isEmpty()) {
            json.writeJson(exchange, 400, ApiError.badRequest(
                    "Invalid patch fields: " + String.join(", ", invalidFields) +
                    ". Allowed fields: " + String.join(", ", COLUMN_PATCH_FIELDS)));
            return;
        }

        // Extract reason
        String reason = (String) body.get("reason");

        // Resolve tableId to actual table ID
        String resolvedTableId = resolveTableId(tableId);
        if (resolvedTableId == null) {
            json.writeNotFound(exchange, "Table not found: " + tableId);
            return;
        }

        // Find column (case-insensitive)
        TableWorkspaceNode table = workspace.getTables().get(resolvedTableId);
        if (table == null) {
            // Try to reload workspace
            GraphWorkspace freshWorkspace = workspaceStore.load(session.getAlias());
            table = freshWorkspace.getTables().get(resolvedTableId);
        }

        if (table == null) {
            json.writeNotFound(exchange, "Table not found: " + tableId);
            return;
        }

        ColumnWorkspaceNode column = table.findColumn(columnName);
        if (column == null) {
            json.writeNotFound(exchange, "Column not found: " + columnName + " in table " + tableId);
            return;
        }

        // Call mutation service
        WorkspaceMutationService.MutationResult result = mutationService.updateColumnDescription(
                session.getAlias(), resolvedTableId, column.getName(), patch, expectedRevision, reason);

        if (result.isSuccess()) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("newRevision", result.getNewRevision());
            response.put("pendingApprovalId", result.getPendingApprovalId());
            response.put("changeId", result.getChangeId());
            json.writeOk(exchange, response);
        } else {
            // Check for revision conflict
            if (result.getErrors().stream().anyMatch(e -> e.contains("revision mismatch"))) {
                json.writeJson(exchange, 409, ApiError.badRequest(
                        "Revision conflict: " + String.join("; ", result.getErrors())));
            } else {
                json.writeJson(exchange, 400, ApiError.badRequest(
                        String.join("; ", result.getErrors())));
            }
        }
    }

    // --- POST /api/validate ---

    private void handlePostValidate(HttpExchange exchange) throws IOException {
        // Validate session token
        if (!validateSession(exchange)) {
            json.writeJson(exchange, 403, ApiError.unauthorized("Invalid or missing session token"));
            return;
        }

        WorkspaceMutationService.MutationResult result = mutationService.mutate(session.getAlias(),
                workspace.getManifest().getRevision(), GraphActor.system, "web validation", current -> {
                    validator.validate(current);
                    return new WorkspaceMutationService.MutationOutcome(
                            current.getManifest().getId(), ChangeOperation.verify);
                });
        if (!result.isSuccess()) {
            json.writeJson(exchange, 400, ApiError.badRequest(String.join("; ", result.getErrors())));
            return;
        }
        GraphWorkspace freshWorkspace = workspaceStore.load(session.getAlias());
        workspace = freshWorkspace;

        // Count issues by severity
        int errorCount = 0;
        int warningCount = 0;
        List<Map<String, Object>> issues = new ArrayList<>();
        for (ValidationIssueRecord issue : freshWorkspace.getValidationIssues()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", issue.getId());
            item.put("severity", issue.getSeverity() != null ? issue.getSeverity().name() : null);
            item.put("code", issue.getCode());
            item.put("message", issue.getMessage());
            item.put("targetId", issue.getTargetId());
            item.put("field", issue.getField());
            issues.add(item);

            if (issue.getSeverity() == ValidationSeverity.error) {
                errorCount++;
            } else if (issue.getSeverity() == ValidationSeverity.warning) {
                warningCount++;
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("newRevision", result.getNewRevision());
        response.put("pendingApprovalId", result.getPendingApprovalId());
        response.put("issues", issues);
        response.put("errorCount", errorCount);
        response.put("warningCount", warningCount);
        json.writeOk(exchange, response);
    }

    // --- POST /api/index/rebuild ---

    private void handlePostRebuildIndex(HttpExchange exchange) throws IOException {
        // Validate session token
        if (!validateSession(exchange)) {
            json.writeJson(exchange, 403, ApiError.unauthorized("Invalid or missing session token"));
            return;
        }

        WorkspaceIndexSnapshot[] built = new WorkspaceIndexSnapshot[1];
        WorkspaceMutationService.MutationResult result = mutationService.mutate(session.getAlias(),
                workspace.getManifest().getRevision(), GraphActor.system, "web index rebuild", current -> {
                    WorkspaceIndexSnapshot snapshot = new WorkspaceIndexer().rebuild(
                            current, current.getManifest().getRevision() + 1);
                    indexStore.save(session.getAlias(), snapshot);
                    current.getManifest().setLastIndexedAt(snapshot.getBuiltAt()
                            .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime());
                    built[0] = snapshot;
                    return new WorkspaceMutationService.MutationOutcome(
                            current.getManifest().getId(), ChangeOperation.update);
                });
        if (!result.isSuccess()) {
            json.writeJson(exchange, 400, ApiError.badRequest(String.join("; ", result.getErrors())));
            return;
        }
        WorkspaceIndexSnapshot snapshot = built[0];
        workspace = workspaceStore.load(session.getAlias());

        // Build response
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ready");
        response.put("tableCount", snapshot.tableCount());
        response.put("columnCount", snapshot.columnCount());
        response.put("termCount", snapshot.termCount());
        response.put("sourceRevision", snapshot.getSourceRevision());
        json.writeOk(exchange, response);
    }

    // --- GET /api/index/status ---

    private void handleGetIndexStatus(HttpExchange exchange) throws IOException {
        // Get current workspace revision
        long currentRevision = workspaceStore.load(session.getAlias()).getManifest().getRevision();

        // Get index status
        IndexStatus status = indexStore.getIndexStatus(session.getAlias(), currentRevision);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", status.name());

        // Try to get source revision from manifest
        try {
            WorkspaceIndexManifest manifest = indexStore.loadManifest(session.getAlias());
            if (manifest != null) {
                response.put("sourceRevision", manifest.getSourceRevision());
                response.put("tableCount", manifest.getTableCount());
                response.put("columnCount", manifest.getColumnCount());
                response.put("builtAt", manifest.getBuiltAt() != null ? manifest.getBuiltAt().toString() : null);
            }
        } catch (IOException e) {
            LOG.debug("Could not load index manifest", e);
        }

        json.writeOk(exchange, response);
    }

    // --- Helper methods ---

    /**
     * Validate session token from request headers.
     */
    private boolean validateSession(HttpExchange exchange) {
        String token = exchange.getRequestHeaders().getFirst("X-Session-Token");
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        return session.validateWriteRequest(token, origin);
    }

    /**
     * 删除一条术语。术语的增改仍然只有 CLI（拍板过：Agent 批量补、人偶尔核对，
     * 不值得建一整套表单），但**删是人的判断**——「这条是垃圾」只有人能下这个结论，
     * 而在 CLI 里根本没有删术语的命令，UI 不给入口就等于删不掉。
     */
    private void handleDeleteTerm(HttpExchange exchange, String termId) throws IOException {
        if (!validateSession(exchange)) {
            json.writeJson(exchange, 403, ApiError.unauthorized("Invalid or missing session token"));
            return;
        }
        if (termId == null || termId.isBlank()) {
            json.writeNotFound(exchange, "termId is required");
            return;
        }
        Map<String, String> params = json.parseQueryParams(exchange);
        long expectedRevision;
        try {
            expectedRevision = Long.parseLong(params.getOrDefault("expectedRevision",
                    String.valueOf(workspace.getManifest().getRevision())));
        } catch (NumberFormatException e) {
            json.writeJson(exchange, 400, ApiError.badRequest("Invalid expectedRevision"));
            return;
        }
        WorkspaceMutationService.MutationResult result = mutationService.deleteTerm(
                session.getAlias(), termId, expectedRevision, params.get("reason"));
        if (!result.isSuccess()) {
            json.writeJson(exchange, 400, ApiError.badRequest(String.join("; ", result.getErrors())));
            return;
        }
        session.setWorkspaceRevision(result.getNewRevision());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("newRevision", result.getNewRevision());
        // pendingApprovalId 必须回给前端：manual 别名上术语并没有真被删掉，
        // 不提示的话人关掉页面就以为删成功了，而图谱纹丝不动
        response.put("pendingApprovalId", result.getPendingApprovalId());
        response.put("changeId", result.getChangeId());
        json.writeOk(exchange, response);
    }

    // --- DELETE /api/lineage/{lineageId} ---

    /** 血缘的 GET（/api/lineage、/api/lineage/overview）在 query 控制器，只有 DELETE 走这里。 */
    private void handleDeleteLineage(HttpExchange exchange, String lineageId) throws IOException {
        if (!validateSession(exchange)) {
            json.writeJson(exchange, 403, ApiError.unauthorized("Invalid or missing session token"));
            return;
        }
        if (lineageId == null || lineageId.isBlank()) {
            json.writeNotFound(exchange, "lineageId is required");
            return;
        }
        if (!workspace.getLineage().containsKey(lineageId)) {
            json.writeNotFound(exchange, "Lineage not found: " + lineageId);
            return;
        }
        Map<String, String> params = json.parseQueryParams(exchange);
        long expectedRevision;
        try {
            expectedRevision = Long.parseLong(params.getOrDefault("expectedRevision",
                    String.valueOf(workspace.getManifest().getRevision())));
        } catch (NumberFormatException e) {
            json.writeJson(exchange, 400, ApiError.badRequest("Invalid expectedRevision"));
            return;
        }
        WorkspaceMutationService.MutationResult result = mutationService.deleteLineage(
                session.getAlias(), lineageId, expectedRevision, GraphActor.human, params.get("reason"));
        if (!result.isSuccess()) {
            json.writeJson(exchange, 400, ApiError.badRequest(String.join("; ", result.getErrors())));
            return;
        }
        session.setWorkspaceRevision(result.getNewRevision());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("newRevision", result.getNewRevision());
        // manual 别名上并没有真删掉，只是排进了审批——不回 pendingApprovalId 人就以为删成功了
        response.put("pendingApprovalId", result.getPendingApprovalId());
        response.put("changeId", result.getChangeId());
        json.writeOk(exchange, response);
    }

    /** {@code /api/xxx/{urlencoded id}} 里那段 id。 */
    private static String decodeId(String path, String prefix) {
        String encoded = path.substring(prefix.length());
        try {
            return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return encoded;
        }
    }

    /**
     * Extract relationId from path: /api/relations/{relationId}
     */
    private String extractRelationId(String path) {
        String encoded = path.substring("/api/relations/".length());
        if (encoded.endsWith("/publish")) {
            encoded = encoded.substring(0, encoded.length() - "/publish".length());
        } else if (encoded.endsWith("/reject")) {
            encoded = encoded.substring(0, encoded.length() - "/reject".length());
        } else if (encoded.endsWith("/unignore")) {
            encoded = encoded.substring(0, encoded.length() - "/unignore".length());
        }
        try {
            return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return encoded;
        }
    }

    /**
     * Find a relation by its ID in the workspace.
     */
    private RelationWorkspaceEdge findRelationById(String relationId) {
        for (RelationWorkspaceEdge edge : workspace.getRelations()) {
            if (edge.getId().equals(relationId)) {
                return edge;
            }
        }
        return null;
    }

    /**
     * Require a string value from the body map, throwing IllegalArgumentException if missing.
     */
    private String requireString(Map<String, Object> body, String key) {
        Object val = body.get(key);
        if (val == null) {
            throw new IllegalArgumentException("'" + key + "' is required");
        }
        return val.toString();
    }

    /**
     * Require a long value from the body map, throwing IllegalArgumentException if missing or invalid.
     */
    private long requireLong(Map<String, Object> body, String key) {
        Object val = body.get(key);
        if (val == null) {
            throw new IllegalArgumentException("'" + key + "' is required");
        }
        if (val instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(val.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + key + "' must be a number");
        }
    }

    /**
     * Parse an optional double value from the body map.
     */
    private Double parseDouble(Map<String, Object> body, String key) {
        Object val = body.get(key);
        if (val == null) return null;
        if (val instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(val.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parse an optional enum value from the body map.
     */
    private <E extends Enum<E>> E parseEnum(Map<String, Object> body, String key, Class<E> enumClass) {
        Object val = body.get(key);
        if (val == null) return null;
        try {
            return Enum.valueOf(enumClass, val.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Resolve tableId to actual table ID.
     * Tries: exact ID match, qualified name match, URL-decoded qualified name.
     */
    private String resolveTableId(String tableRef) {
        // Try exact ID match
        if (workspace.getTables().containsKey(tableRef)) {
            return tableRef;
        }

        // Try qualified name lookup
        TableWorkspaceNode table = workspace.getTableByQualifiedName(tableRef);
        if (table != null) {
            return table.getId();
        }

        // Try by ID prefix search
        for (TableWorkspaceNode t : workspace.getTables().values()) {
            if (t.getId().equals(tableRef)) {
                return t.getId();
            }
        }

        return null;
    }

}
