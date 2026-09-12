package com.sqlcli.graph.ui.api;

import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.config.AliasConfigStore;
import com.sqlcli.config.AliasConfigValidator;
import com.sqlcli.config.JdbcUrlParser;
import com.sqlcli.connection.ConnectionManager;
import com.sqlcli.connection.TableRowCounter;
import com.sqlcli.graph.ui.GraphUiSession;
import com.sqlcli.graph.ui.JsonHttpSupport;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import com.sqlcli.graph.workspace.ImportJob;
import com.sqlcli.graph.workspace.ImportOptions;
import com.sqlcli.graph.workspace.WorkspaceImportService;
import com.sqlcli.graph.workspace.WorkspaceMetadataProvider;
import com.sqlcli.graph.workspace.WorkspaceManifest;
import com.sqlcli.graph.workspace.index.WorkspaceIndexStore;
import com.sqlcli.runstate.GraphChangeRow;
import com.sqlcli.runstate.RunStateStore;
import com.sqlcli.secret.SecretResolver;
import com.sqlcli.strategy.WorkspaceMetadataProviderFactory;
import com.sqlcli.approval.ApprovalGate;
import com.sqlcli.task.SqlTaskModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/** Routes one local UI server to the configured graph workspaces. */
public class GraphUiApiRouter implements HttpHandler {
    private final Map<String, DatabaseConfig> aliases;
    private final String defaultAlias;
    private final String allowedOrigin;
    private final JsonHttpSupport json;
    private final WorkspaceIndexStore indexStore;
    private final GraphWorkspaceStore workspaceStore;
    private final Map<String, GraphUiSession> sessions = new ConcurrentHashMap<>();
    private final RunStateStore runState = new RunStateStore();
    private final ConnectionManager connectionManager = new ConnectionManager();
    private final ApprovalGate approvalGate = new ApprovalGate();
    /**
     * 全项目唯一的 SQL 执行入口，UI 侧建一次共享：5 个 Stage + 2 个 Backend 全部无状态，
     * 每个控制器各持一份只是白白分配对象。历史重放和工作台用的是同一个实例，
     * 因此审批、行数上限、SM4、审计的行为不可能在两条路径上走样。
     */
    private final SqlTaskModule taskModule = new SqlTaskModule(connectionManager, runState, approvalGate);
    /** 恢复文件的受控执行路径（设计文档 §17），不经过 taskModule——理由见 RecoveryExecutor。 */
    private final com.sqlcli.recovery.RecoveryExecutor recoveryExecutor =
            new com.sqlcli.recovery.RecoveryExecutor(connectionManager, runState);
    /** SQL 批次：单连接单事务，理由同上——逐条走 taskModule 是 N 个独立事务。 */
    private final com.sqlcli.task.SqlBatchExecutor sqlBatchExecutor =
            new com.sqlcli.task.SqlBatchExecutor(connectionManager, runState);
    private final SqlRerunController sqlRerunController;
    private final WorkbenchExecuteController workbenchExecuteController;
    private final FileBrowseController fileBrowseController;
    /** 路由表，见 {@link #buildRoutes()}——命中顺序原样保留自重构前的 if/else 链。 */
    private final List<Route> routes;

    /** 兼容旧调用点：都是测试里绑在 127.0.0.1 上起的服务器，回环闸门恒为 true。 */
    public GraphUiApiRouter(Map<String, DatabaseConfig> aliases, String defaultAlias,
                            String allowedOrigin, JsonHttpSupport json,
                            WorkspaceIndexStore indexStore, GraphWorkspaceStore workspaceStore) {
        this(aliases, defaultAlias, allowedOrigin, json, indexStore, workspaceStore, true);
    }

    public GraphUiApiRouter(Map<String, DatabaseConfig> aliases, String defaultAlias,
                            String allowedOrigin, JsonHttpSupport json,
                            WorkspaceIndexStore indexStore, GraphWorkspaceStore workspaceStore,
                            boolean loopbackOnly) {
        this.aliases = new LinkedHashMap<>(aliases);
        this.defaultAlias = defaultAlias;
        this.allowedOrigin = allowedOrigin;
        this.json = json;
        this.indexStore = indexStore;
        this.workspaceStore = workspaceStore;
        this.sqlRerunController = new SqlRerunController(this.aliases, defaultAlias, allowedOrigin, json, taskModule);
        this.workbenchExecuteController = new WorkbenchExecuteController(
                this.aliases, defaultAlias, allowedOrigin, json, taskModule, workspaceStore);
        this.fileBrowseController = new FileBrowseController(loopbackOnly, json);
        this.routes = buildRoutes();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        for (Route route : routes) {
            if (!route.matcher().test(path)) {
                continue;
            }
            if (route.method() != null && !route.method().equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            route.handler().handle(exchange, path);
            return;
        }
        // 表里没有一行命中——这些接口都要先解出 alias/workspace/session，
        // 不是纯粹的「路径 → handler」映射，留在表外单独处理。
        dispatchWorkspaceScoped(exchange, path);
    }

    /**
     * 路由表：按顺序逐条匹配，命中即分发、不再往下比。
     *
     * <p>顺序原样保留自重构前的 if/else 链，**没有重新排过**——尤其是
     * "/api/aliases/*​/import"、"/api/aliases/sqlite/check-path" 这类具体路径
     * 必须排在通配的 "/api/aliases/" 前缀之前，否则会被那条更宽的规则先吞掉。
     * 加一个新端点只需要在这里加一行，不用再往 if/else 里插队。
     */
    private List<Route> buildRoutes() {
        List<Route> list = new ArrayList<>();
        list.add(Route.exact("/api/executions", "GET", (exchange, path) -> handleExecutions(exchange)));
        list.add(new Route(path -> path.startsWith("/api/executions/") && path.endsWith("/recovery"),
                "GET", this::handleExecutionRecovery));
        list.add(new Route(path -> path.startsWith("/api/executions/") && path.endsWith("/rollback"),
                "POST", this::handleExecutionRollback));
        list.add(new Route(path -> path.equals("/api/approvals") || path.startsWith("/api/approvals/")
                || path.startsWith("/api/batches/"),
                null, (exchange, path) ->
                        new ApprovalController(allowedOrigin, json, runState, workspaceStore,
                                new com.sqlcli.graph.ui.service.ApprovalEffects(
                                        workspaceStore, aliases, recoveryExecutor, runState,
                                        sqlBatchExecutor))
                                .handle(exchange)));
        list.add(Route.exact("/api/graph-changes", "GET", (exchange, path) -> handleGraphChanges(exchange)));
        list.add(Route.exact("/api/eval/evaluations", "GET",
                (exchange, path) -> handleEvaluations(exchange)));
        list.add(Route.exact("/api/eval/run", "POST", (exchange, path) -> handleRunEval(exchange)));
        list.add(new Route(path -> path.startsWith("/api/eval/evaluations/")
                && path.endsWith("/findings"), "GET", this::handleEvaluationFindings));
        // GET/POST 各走各的 handler，方法判断留在 handler 内部，路由表不额外限定 method。
        list.add(Route.exact("/api/aliases", null, (exchange, path) -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                handleAliases(exchange);
            } else if ("POST".equals(exchange.getRequestMethod())) {
                handleAddAlias(exchange);
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }));
        list.add(Route.exact("/api/sql/execute", null, (exchange, path) -> sqlRerunController.handle(exchange)));
        list.add(Route.exact("/api/workbench/execute", null,
                (exchange, path) -> workbenchExecuteController.handle(exchange)));
        list.add(Route.exact("/api/workbench/cancel", null,
                (exchange, path) -> workbenchExecuteController.handleCancel(exchange)));
        // handleImport 自己判断方法（非 POST 时 405），此处不重复限定。
        list.add(new Route(path -> path.startsWith("/api/aliases/") && path.endsWith("/import"),
                null, this::handleImport));
        list.add(Route.exact("/api/aliases/sqlite/check-path", "POST",
                (exchange, path) -> handleSqlitePathCheck(exchange)));
        list.add(Route.exact("/api/fs/list", "GET", (exchange, path) -> fileBrowseController.handle(exchange)));
        list.add(Route.exact("/api/tables/row-count", null, (exchange, path) -> handleRowCount(exchange)));
        list.add(Route.exact("/api/schemas/catalog", null, (exchange, path) -> handleSchemaCatalog(exchange)));
        list.add(new Route(path -> path.equals("/api/drivers") || path.startsWith("/api/drivers/"),
                null, (exchange, path) -> new DriverAdminController(allowedOrigin, json).handle(exchange)));
        // 通配兜底，必须排在上面所有更具体的 "/api/aliases/..." 规则之后。
        list.add(new Route(path -> path.startsWith("/api/aliases/"),
                null, (exchange, path) -> new AliasAdminController(aliases, allowedOrigin, json).handle(exchange)));
        return list;
    }

    /** 一条路由：路径匹配规则 + 可选的强制 HTTP 方法（null 表示方法判断交给 handler 自己）。 */
    private record Route(Predicate<String> matcher, String method, RouteHandler handler) {
        static Route exact(String path, String method, RouteHandler handler) {
            return new Route(path::equals, method, handler);
        }
    }

    @FunctionalInterface
    private interface RouteHandler {
        void handle(HttpExchange exchange, String path) throws IOException;
    }

    /**
     * 路由表兜底：这些接口都挂在某个别名的图谱工作区下，先解析
     * alias → workspace → session，再按路径分给 policy / mutation / query 三个
     * 控制器之一。依赖上一步解析出的状态，所以没有并进路由表。
     */
    private void dispatchWorkspaceScoped(HttpExchange exchange, String path) throws IOException {
        String alias = json.getParam(json.parseQueryParams(exchange), "alias", defaultAlias);
        if (alias == null || alias.isBlank()) {
            json.writeError(exchange, new IllegalArgumentException("alias is required"));
            return;
        }
        if (!aliases.containsKey(alias)) {
            json.writeNotFound(exchange, "Unknown alias: " + alias);
            return;
        }
        if (!workspaceStore.exists(alias)) {
            json.writeNotFound(exchange, "Graph workspace not found for alias: " + alias
                    + ". Run: sql-cli " + alias + " schema import --from-db");
            return;
        }

        GraphWorkspace workspace = workspaceStore.load(alias);
        GraphUiSession session = sessions.computeIfAbsent(alias, this::newSession);
        session.setWorkspaceRevision(workspace.getManifest().getRevision());
        if (path.startsWith("/api/policy/rules")) {
            new PolicyRuleController(session, json, workspaceStore).handle(exchange);
            return;
        }
        WorkspaceMutationController mutation = new WorkspaceMutationController(
                workspace, session, json, indexStore, workspaceStore);
        // 术语的 GET 在 query 控制器，只有 DELETE 走 mutation——
        // 把 /api/terms 整个前缀搬进 MUTATION_PREFIXES 会让 GET /api/terms 变成 404
        if (isMutationPath(path)
                || (path.startsWith("/api/terms/") && "DELETE".equals(exchange.getRequestMethod()))
                || (path.startsWith("/api/lineage/") && "DELETE".equals(exchange.getRequestMethod()))) {
            mutation.handle(exchange);
            return;
        }
        WorkspaceQueryController query = new WorkspaceQueryController(
                workspace, session, json, indexStore, workspaceStore);
        query.setMutationController(mutation);
        query.handle(exchange);
    }

    private GraphUiSession newSession(String alias) {
        // readonly = 数据库只读，随会话下发给前端做展示与工作台提前禁用；
        // 图谱写请求不受它影响（语义见 GraphUiSession.validateWriteRequest）。
        DatabaseConfig config = aliases.get(alias);
        boolean dbReadOnly = config != null && Boolean.TRUE.equals(config.getReadonly());
        GraphUiSession session = new GraphUiSession(alias, dbReadOnly);
        session.setAllowedOrigin(allowedOrigin);
        return session;
    }

    /**
     * 图谱变更流水：每次图谱更新一条，带上改了什么（payload 的 before/after）、
     * 谁改的、revision 从几到几、是哪条审批放行的。
     *
     * <pre>GET /api/graph-changes?alias=demo&amp;targetId=relation:...&amp;limit=20&amp;offset=0</pre>
     *
     * <p>{@code targetId} 用来看单个对象的变更历史——「这个字段的描述是谁什么时候改的」
     * 这类问题，翻整条流水找不现实。
     */
    private void handleGraphChanges(HttpExchange exchange) throws IOException {
        Map<String, String> params = json.parseQueryParams(exchange);
        String alias = json.getParam(params, "alias", null);
        if (alias != null && ("all".equalsIgnoreCase(alias) || alias.isBlank())) alias = null;
        String targetId = json.getParam(params, "targetId", null);
        int limit = json.getIntParam(params, "limit", 20);
        int offset = json.getIntParam(params, "offset", 0);
        List<Map<String, Object>> items = new ArrayList<>();
        for (GraphChangeRow row : runState.listGraphChanges(alias, targetId, limit, offset)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.id());
            item.put("alias", row.alias());
            item.put("operation", row.operation());
            item.put("targetId", row.targetId());
            item.put("actor", row.actor());
            item.put("revisionBefore", row.revisionBefore());
            item.put("revisionAfter", row.revisionAfter());
            item.put("createdAt", row.createdAt());
            item.put("approvalId", row.approvalId());
            item.put("reason", row.reason());
            item.put("payload", parseJsonOrNull(row.payload()));
            items.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("changes", items);
        body.put("total", runState.countGraphChanges(alias, targetId));
        json.writeOk(exchange, body);
    }

    /**
     * 图谱评估列表，最近的在前。
     *
     * <pre>GET /api/eval/evaluations?alias=demo&amp;limit=20</pre>
     *
     * <p><b>产出走 {@link #handleRunEval}，两个入口一份实现</b>：原来这里写着「只读，
     * 没有 POST run」，理由是「加一个按钮等于开出第二条产出路径，结果迟早对不上」。
     * 那条担心针对的是两套实现——现在 CLI 和这个端点都调
     * {@link com.sqlcli.graph.eval.GraphEvalRunner#runAndSave}，同一个评估器、同一张表、
     * 同一个 source，算不出两个结果，所以按钮可以有。
     */
    private void handleEvaluations(HttpExchange exchange) throws IOException {
        Map<String, String> params = json.parseQueryParams(exchange);
        String alias = json.getParam(params, "alias", null);
        if (alias != null && ("all".equalsIgnoreCase(alias) || alias.isBlank())) alias = null;
        int limit = json.getIntParam(params, "limit", 20);
        List<Map<String, Object>> items = new ArrayList<>();
        for (RunStateStore.GraphEvaluationRow row : runState.listGraphEvaluations(alias, limit)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.id());
            item.put("alias", row.alias());
            item.put("source", row.source());
            item.put("status", row.status());
            item.put("revision", row.revision());
            item.put("startedAt", row.startedAt());
            item.put("elapsedMs", row.elapsedMs());
            item.put("errorCount", row.errorCount());
            item.put("warningCount", row.warningCount());
            item.put("metrics", parseJsonOrNull(row.metricsJson()));
            items.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("evaluations", items);
        json.writeOk(exchange, body);
    }

    /**
     * 跑一次图谱评估。
     *
     * <pre>POST /api/eval/run?alias=demo</pre>
     *
     * <p>不另写评估逻辑——调
     * {@link com.sqlcli.graph.eval.GraphEvalRunner#runAndSave}，跟
     * {@code sql-cli <alias> schema eval} 是同一段代码。评估是纯计算不连库，
     * 一份图谱毫秒级跑完，所以同步返回，不走任务队列。
     */
    private void handleRunEval(HttpExchange exchange) throws IOException {
        Map<String, String> params = json.parseQueryParams(exchange);
        String alias = json.getParam(params, "alias", null);
        if (alias == null || alias.isBlank()) {
            json.writeNotFound(exchange, "alias is required");
            return;
        }
        GraphWorkspace workspace = workspaceStore.load(alias);
        if (workspace == null || workspace.getManifest() == null) {
            json.writeNotFound(exchange, "图谱工作区不存在：" + alias);
            return;
        }
        com.sqlcli.graph.eval.GraphEvalRunner.Result result =
                com.sqlcli.graph.eval.GraphEvalRunner.runAndSave(alias, workspace);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", result.evaluationId());
        body.put("alias", alias);
        body.put("revision", result.revision());
        body.put("status", result.evaluation().status());
        body.put("errorCount", result.evaluation().errorCount());
        body.put("warningCount", result.evaluation().warningCount());
        body.put("elapsedMs", result.elapsedMs());
        json.writeOk(exchange, body);
    }

    /**
     * 一次评估的 finding，分页。
     *
     * <pre>GET /api/eval/evaluations/{id}/findings?page=1&amp;pageSize=50</pre>
     *
     * <p>分页不是为了好看：一次评估上千条 finding 全塞进一个响应，就是 policy 那 1487 条
     * 没人读完的翻版。
     */
    private void handleEvaluationFindings(HttpExchange exchange, String path) throws IOException {
        String id = path.substring("/api/eval/evaluations/".length(),
                path.length() - "/findings".length());
        if (id.isBlank()) {
            json.writeNotFound(exchange, "evaluation id is required");
            return;
        }
        Map<String, String> params = json.parseQueryParams(exchange);
        int page = Math.max(1, json.getIntParam(params, "page", 1));
        int pageSize = Math.max(1, json.getIntParam(params, "pageSize", 50));
        List<Map<String, Object>> items = new ArrayList<>();
        for (RunStateStore.GraphFindingRow row
                : runState.listGraphFindings(id, (page - 1) * pageSize, pageSize)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("seq", row.seq());
            item.put("probe", row.probe());
            item.put("severity", row.severity());
            item.put("targetId", row.targetId());
            item.put("message", row.message());
            item.put("remediation", row.remediation());
            items.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("findings", items);
        body.put("total", runState.countGraphFindings(id));
        body.put("page", page);
        body.put("pageSize", pageSize);
        json.writeOk(exchange, body);
    }

    /** payload 是 JSON 文本，内联成对象返回——前端不该再解一次字符串。解不动就当没有。 */
    private Object parseJsonOrNull(String payload) {
        if (payload == null || payload.isBlank()) return null;
        try {
            return json.getObjectMapper().readValue(payload, Map.class);
        } catch (IOException e) {
            return null;
        }
    }

    /** 落在 WorkspaceMutationController 的前缀——加一个新前缀只需要在这里加一项。 */
    private static final List<String> MUTATION_PREFIXES = List.of(
            "/api/validate", "/api/index", "/api/relations", "/api/validation", "/api/metrics");

    private boolean isMutationPath(String path) {
        return MUTATION_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private void handleAliases(HttpExchange exchange) throws IOException {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, DatabaseConfig> entry : aliases.entrySet()) {
            String alias = entry.getKey();
            DatabaseConfig config = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", alias);
            item.put("dbType", config.getType());
            item.put("description", config.getDescription());
            item.put("readOnly", Boolean.TRUE.equals(config.getReadonly()));
            item.put("approveQuery", Boolean.TRUE.equals(config.getApproveQuery()));
            item.put("approveUpdate", Boolean.TRUE.equals(config.getApproveUpdate()));
            item.put("approveGraph", com.sqlcli.approval.ApprovalGate.graphMode(config)
                    == com.sqlcli.approval.ApprovalGate.GraphMode.manual);
            boolean available = false;
            try {
                available = workspaceStore.exists(alias);
                if (available) {
                    GraphWorkspace workspace = workspaceStore.load(alias);
                    WorkspaceManifest manifest = workspace.getManifest();
                    item.put("tables", manifest.getStats().getTables());
                    item.put("relations", manifest.getStats().getRelations());
                    item.put("updatedAt", manifest.getUpdatedAt());
                }
            } catch (IOException | RuntimeException ignored) {
                // A damaged workspace must not hide the other configured aliases.
                available = false;
            }
            item.put("graphAvailable", available);
            result.add(item);
        }
        result.sort(Comparator.comparing(item -> String.valueOf(item.get("name")), String.CASE_INSENSITIVE_ORDER));
        json.writeOk(exchange, Map.of("aliases", result));
    }

    private void handleExecutions(HttpExchange exchange) throws IOException {
        String alias = json.getParam(json.parseQueryParams(exchange), "alias", defaultAlias);
        if (alias == null || alias.isBlank()) {
            json.writeError(exchange, new IllegalArgumentException("alias is required"));
            return;
        }
        if (!aliases.containsKey(alias)) {
            json.writeNotFound(exchange, "Unknown alias: " + alias);
            return;
        }
        Map<String, String> params = json.parseQueryParams(exchange);
        String type = json.getParam(params, "type", null);
        String status = json.getParam(params, "status", null);
        Long after = longParam(params, "startedAfter");
        Long before = longParam(params, "startedBefore");
        String schema = json.getParam(params, "schema", null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("records", runState.listExecutions(alias, type, status, after, before, schema,
                json.getIntParam(params, "limit", 50), json.getIntParam(params, "offset", 0)));
        // total 让前端能显示总页数；条件和列表用的是同一份 WHERE，不会出现「翻到第 5 页是空的」。
        body.put("total", runState.countExecutions(alias, type, status, after, before, schema));
        // 筛选器的 schema 选项跟着列表一起给：单独一个端点只为下拉框凑选项不值当。
        body.put("schemas", runState.listExecutionSchemas(alias));
        json.writeOk(exchange, body);
    }

    /** 缺失或不是数字都当没传——筛选参数写错不该让整个历史列表 400。 */
    private static Long longParam(Map<String, String> params, String name) {
        String value = params.get(name);
        if (value == null || value.isBlank()) return null;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * GET /api/executions/{id}/recovery —— 写操作的回滚 SQL 与执行前的原始行。
     *
     * <p>两份内容都存在 {@code recovery_artifact} 里，直接读出来即可。
     */
    private void handleExecutionRecovery(HttpExchange exchange, String path) throws IOException {
        long executionId;
        try {
            executionId = Long.parseLong(path.split("/")[3]);
        } catch (RuntimeException e) {
            json.writeError(exchange, new IllegalArgumentException("无效的执行记录 id"));
            return;
        }
        com.sqlcli.runstate.RecoveryArtifactRow artifact = runState.findRecovery(executionId);
        if (artifact == null) {
            json.writeNotFound(exchange, "这条记录没有回滚脚本");
            return;
        }
        java.util.Map<String, Object> body = new LinkedHashMap<>();
        body.put("executionId", executionId);
        if (!artifact.hasScript()) {
            json.writeNotFound(exchange, "这条记录的回滚脚本内容为空");
            return;
        }
        body.put("source", "运行库 #" + artifact.id());
        body.put("backup", artifact.backupText() == null ? "" : artifact.backupText());
        body.put("rollback", artifact.rollbackSql());
        json.writeOk(exchange, body);
    }

    /**
     * POST /api/executions/{id}/rollback —— 真正执行恢复文件（设计文档 §17）。
     *
     * <p>流程：readonly 拒绝 → 校验并解析恢复文件 → 排一条 kind=recovery 待审批（无条件）
     * → 立刻返回。真正的执行发生在有人在审批中心批准的那一刻（{@code ApprovalEffects}），
     * 单连接单事务逐条跑。
     *
     * <p>原来是挂住等裁决：一个 HTTP 线程耗在人的反应时间上，而且人得先在执行记录里点回滚、
     * 再切到待审批批准自己刚点的东西，中间那个按钮一直转。回滚是一件「等着我决定的事」，
     * 它该跟其它待办排在同一个队列里，也该在那里被执行。
     */
    private void handleExecutionRollback(HttpExchange exchange, String path) throws IOException {
        try {
            if (!json.requireAllowedOrigin(exchange, allowedOrigin)) return;
            long executionId;
            try {
                executionId = Long.parseLong(path.split("/")[3]);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("无效的执行记录 id");
            }
            com.sqlcli.runstate.SqlExecutionRow execution = runState.findExecution(executionId);
            if (execution == null) {
                json.writeNotFound(exchange, "执行记录 #" + executionId + " 不存在");
                return;
            }
            DatabaseConfig config = aliases.get(execution.alias());
            if (config == null) {
                json.writeNotFound(exchange, "Unknown alias: " + execution.alias());
                return;
            }
            com.sqlcli.recovery.RecoveryExecutor.requireWritable(config);
            com.sqlcli.recovery.RecoveryExecutor.RecoveryScript script =
                    recoveryExecutor.loadScript(executionId);
            long approvalId = approvalGate.request(execution.alias(), ApprovalGate.Kind.RECOVERY,
                    "回滚执行记录 #" + executionId + " · " + script.statements().size() + " 条语句",
                    script.rollbackText(), null, "execution:" + executionId);
            json.writeOk(exchange, Map.of(
                    "executionId", executionId,
                    "statements", script.statements().size(),
                    "approvalId", approvalId,
                    "status", "pending"));
        } catch (Exception e) {
            json.writeError(exchange, e);
        }
    }

    /**
     * sqlite 保存前的路径预检——纯文件系统检查，不需要别名已存在，也不走 JDBC 连接。
     *
     * <p>{@code sqlite-jdbc} 在文件不存在时会静默新建一个空库，真开一次 JDBC 连接测不出
     * 「这个路径下原本没有库」这件事，用户会在保存后看到「连上了但一张表都没有」才发现
     * 拼错了路径。先看一眼文件系统，能在保存这一刻就说清楚。
     */
    private void handleSqlitePathCheck(HttpExchange exchange) throws IOException {
        try {
            if (!json.requireAllowedOrigin(exchange, allowedOrigin)) return;
            Map<?, ?> body = json.readBody(exchange, Map.class);
            Object rawPath = body == null ? null : body.get("path");
            String path = rawPath == null ? "" : String.valueOf(rawPath).trim();
            if (path.isEmpty()) throw new IllegalArgumentException("path is required");
            java.nio.file.Path file = java.nio.file.Path.of(path);
            boolean exists = java.nio.file.Files.isRegularFile(file);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("exists", exists);
            result.put("path", file.toAbsolutePath().toString());
            result.put("message", exists
                    ? "文件已存在"
                    : "此路径下没有库文件，保存后 SQLite 会在这里新建一个空库");
            json.writeOk(exchange, result);
        } catch (Exception e) {
            json.writeError(exchange, e);
        }
    }

    private void handleAddAlias(HttpExchange exchange) throws IOException {
        AliasCreateRequest request;
        SecretWrite storedSecret = null;
        try {
            if (!json.requireAllowedOrigin(exchange, allowedOrigin)) return;
            request = json.readBody(exchange, AliasCreateRequest.class);
            if (request == null) throw new IllegalArgumentException("request body is required");
            String name = clean(request.name);
            validateAliasName(name);
            if (aliases.containsKey(name)) throw new IllegalArgumentException("Alias already exists: " + name);

            DatabaseConfig config = new DatabaseConfig();
            config.setType(clean(request.dbType));
            config.setDriverRef(clean(request.driverRef));
            config.setJdbcUrl(clean(request.jdbcUrl));
            config.setHost(clean(request.host));
            config.setPort(request.port == null ? 0 : request.port);
            config.setDatabase(clean(request.database));
            config.setServiceName(clean(request.serviceName));
            config.setSid(clean(request.sid));
            config.setUsername(clean(request.username));
            // sqlite 没有账号密码，默认 keyring:<name> 只对需要密码的库类型有意义——
            // 强行给它一个 secretRef 会让 AliasConfigStore 里多出一个永远用不到的密钥引用。
            config.setSecretRef("sqlite".equalsIgnoreCase(config.getType())
                    ? clean(request.secretRef)
                    : defaultSecretRef(request.secretRef, name));
            config.setDescription(clean(request.description));
            config.setDefaultSchema(clean(request.defaultSchema));
            config.setAccessMode(clean(request.accessMode));
            config.setYearningHost(clean(request.yearningHost));
            config.setYearningIdc(clean(request.yearningIdc));
            config.setYearningDatabase(clean(request.yearningDatabase));
            config.setReadonly(request.readonly);
            config.setSm4Key(clean(request.sm4Key));
            config.setSm4PrivateTag(clean(request.sm4PrivateTag));
            config.setSm4Version(clean(request.sm4Version));
            config.setDecryptColumns(csv(request.decryptColumns));
            // sqlite 文件不存在时策略层默认拒绝连接（防止拼错路径被静默新建一张空库）；
            // 用户在保存前的路径预检里明确选了「新建」，才把这个开关持久化下来——
            // 不然只在这一次保存里问过，下一次真正连接（测试、查询）又会被同一个检查挡住。
            if (Boolean.TRUE.equals(request.sqliteCreateIfMissing)) {
                config.getParams().put("createIfMissing", "true");
            }
            AliasConfigValidator.inferDbType(config);
            AliasConfigValidator.validate(config);
            storedSecret = storeSecret(config.getSecretRef(), request.secretValue,
                    Boolean.TRUE.equals(request.overwriteSecret));
            new AliasConfigStore().saveAlias(name, config);
            config.setAliasName(name);
            // 用解析后的那份进内存，不是这份裸配置——理由见 refreshLiveConfig
            AliasAdminController.refreshLiveConfig(aliases, name);
            json.writeJson(exchange, 201, Map.of("name", name, "status", "created"));
        } catch (Exception e) {
            if (storedSecret != null) rollbackSecret(storedSecret);
            json.writeError(exchange, e);
        }
    }

    private SecretWrite storeSecret(String secretRef, String secretValue, boolean overwrite) {
        if (secretValue == null || secretValue.isBlank() || secretRef.startsWith("env:")) return null;
        String name = secretRef.substring(secretRef.indexOf(':') + 1);
        SecretResolver resolver = new SecretResolver();
        if (name.isBlank()) throw new IllegalArgumentException("secretRef name is required");
        if (secretRef.startsWith("keyring:")) {
            String previous = null;
            if (resolver.keyringSecretExists(name)) {
                if (!overwrite) {
                    throw new IllegalArgumentException("Secret already exists: " + secretRef
                            + "; send overwriteSecret=true to replace it");
                }
                try {
                    previous = resolver.resolveSecret(secretRef);
                } catch (RuntimeException ignored) {
                    // 旧值读不回来时无法回滚，仍然允许覆盖，否则用户会被卡死在一个坏密码上
                }
            }
            resolver.storeKeyringSecret(name, secretValue);
            return new SecretWrite(secretRef, previous);
        }
        if (secretRef.startsWith("encrypted:")) {
            if (resolver.encryptedSecretExists(name)) {
                // 读回旧值需要主密码，可能触发交互式输入，服务进程里不做覆盖
                throw new IllegalArgumentException("Secret already exists: " + secretRef
                        + "; replace it with 'sql-cli <alias> secret set'");
            }
            resolver.storeEncryptedSecret(name, secretValue);
            return new SecretWrite(secretRef, null);
        }
        throw new IllegalArgumentException("Unsupported secretRef: " + secretRef);
    }

    /** 新建密码时回滚为删除；覆盖已有密码时回滚为还原旧值，避免把别人的密码删掉。 */
    private void rollbackSecret(SecretWrite write) {
        if (write.previousValue == null) {
            deleteSecret(write.secretRef);
            return;
        }
        try {
            String name = write.secretRef.substring(write.secretRef.indexOf(':') + 1);
            new SecretResolver().storeKeyringSecret(name, write.previousValue);
        } catch (RuntimeException ignored) {
            // Preserve the original save failure.
        }
    }

    private record SecretWrite(String secretRef, String previousValue) {
    }

    private void deleteSecret(String secretRef) {
        String name = secretRef.substring(secretRef.indexOf(':') + 1);
        SecretResolver resolver = new SecretResolver();
        try {
            if (secretRef.startsWith("keyring:")) resolver.deleteKeyringSecret(name);
            if (secretRef.startsWith("encrypted:")) resolver.deleteEncryptedSecret(name);
        } catch (RuntimeException ignored) {
            // Preserve the original save failure.
        }
    }

    private String defaultSecretRef(String value, String name) {
        String ref = clean(value);
        return ref == null ? "keyring:" + name : ref;
    }

    private List<String> csv(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim).filter(item -> !item.isBlank()).toList();
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void validateAliasName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("Alias name must start with a letter or digit and contain only letters, digits, '.', '_' or '-'");
        }
    }

    public static final class AliasCreateRequest {
        public String name;
        public String dbType;
        public String driverRef;
        public String jdbcUrl;
        public String host;
        public Integer port;
        public String database;
        public String serviceName;
        public String sid;
        public String username;
        public String secretRef;
        public String secretValue;
        public Boolean overwriteSecret;
        public String description;
        /** 默认 schema：SQL 不写前缀时用它，留空沿用 URL 里的 */
        public String defaultSchema;
        public String accessMode;
        public String yearningHost;
        public String yearningIdc;
        public String yearningDatabase;
        public Boolean readonly;
        public String sm4Key;
        public String sm4PrivateTag;
        public String sm4Version;
        public String decryptColumns;
        /** 仅 sqlite：路径不存在时是否允许连接时新建空库，映射到 params.createIfMissing。 */
        public Boolean sqliteCreateIfMissing;
    }

    /**
     * 数据源里的全部 schema，标出哪些已经进了图谱。
     *
     * 与 /api/schemas 的区别：那个只列图谱里已有的，这个连数据库问「还有什么可以导」。
     * 左侧面板要同时显示两者才能让人按 schema 逐个导入。
     */
    private void handleSchemaCatalog(HttpExchange exchange) throws IOException {
        Map<String, String> params = json.parseQueryParams(exchange);
        String alias = json.getParam(params, "alias", defaultAlias);
        DatabaseConfig config = alias == null ? null : aliases.get(alias);
        if (config == null) {
            json.writeNotFound(exchange, "Unknown alias: " + alias);
            return;
        }

        // 图谱里已有的 schema 及其表数量
        Map<String, Integer> imported = new LinkedHashMap<>();
        if (workspaceStore.exists(alias)) {
            GraphWorkspace workspace = workspaceStore.load(alias);
            for (TableWorkspaceNode table : workspace.getTables().values()) {
                if (table.getSchema() != null) {
                    imported.merge(table.getSchema().toLowerCase(Locale.ROOT), 1, Integer::sum);
                }
            }
            for (var schemaNode : workspace.getSchemas().values()) {
                if (schemaNode.getName() != null) {
                    imported.putIfAbsent(schemaNode.getName().toLowerCase(Locale.ROOT), 0);
                }
            }
        }

        try (Connection connection = new ConnectionManager().getConnection(config);
             WorkspaceMetadataProvider provider = WorkspaceMetadataProviderFactory.create(connection, config)) {
            List<Map<String, Object>> result = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (String name : provider.discoverSchemas()) {
                String key = name.toLowerCase(Locale.ROOT);
                if (!seen.add(key)) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", name);
                item.put("system", provider.isSystemSchema(name));
                item.put("imported", imported.containsKey(key));
                item.put("tableCount", imported.getOrDefault(key, 0));
                result.add(item);
            }
            // 图谱里有但数据库已经没了的 schema 也要露出来，否则用户看不到残留
            for (Map.Entry<String, Integer> entry : imported.entrySet()) {
                if (seen.add(entry.getKey())) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", entry.getKey());
                    item.put("system", false);
                    item.put("imported", true);
                    item.put("tableCount", entry.getValue());
                    item.put("missingInDatabase", true);
                    result.add(item);
                }
            }
            json.writeOk(exchange, result);
        } catch (Exception e) {
            json.writeError(exchange, new RuntimeException("Schema catalog failed: "
                    + JdbcUrlParser.redactSecrets(e.getMessage()), e));
        }
    }

    /**
     * 实时行数。
     *
     * 走 COUNT(*) 而不是 information_schema 的统计值：这个接口是用户点击才触发的，
     * 一次只查一张表，给个估算数反而会误导。大表会慢，所以不主动调用、也不缓存。
     */
    private void handleRowCount(HttpExchange exchange) throws IOException {
        // 命中后会顺手把行数写回图谱（persistRowEstimate），是写路径，和本文件其他写端点一样要挡跨站请求。
        if (!json.requireAllowedOrigin(exchange, allowedOrigin)) return;
        Map<String, String> params = json.parseQueryParams(exchange);
        String alias = json.getParam(params, "alias", defaultAlias);
        String schema = json.getParam(params, "schema", null);
        String table = json.getParam(params, "table", null);
        if (alias == null || schema == null || table == null
                || schema.isBlank() || table.isBlank()) {
            json.writeError(exchange, new IllegalArgumentException("alias, schema and table are required"));
            return;
        }
        DatabaseConfig config = aliases.get(alias);
        if (config == null) {
            json.writeNotFound(exchange, "Unknown alias: " + alias);
            return;
        }
        try {
            TableRowCounter.qualifiedName(config.getType(), schema, table);
        } catch (IllegalArgumentException e) {
            json.writeError(exchange, e);
            return;
        }
        try (Connection connection = new ConnectionManager().getConnection(config)) {
            long started = System.nanoTime();
            long count = TableRowCounter.count(connection, config.getType(), schema, table);
            // 与 CLI --refresh-row-count 对齐：查完顺手写回 rowEstimate（system 身份、
            // 只改这个字段不动 status）。图谱不存在或表不在图谱里就只报数。
            boolean persisted = persistRowEstimate(alias, schema, table, count);
            json.writeJson(exchange, 200, Map.of(
                    "schema", schema,
                    "table", table,
                    "rowCount", count,
                    "persisted", persisted,
                    "elapsedMs", (System.nanoTime() - started) / 1_000_000));
        } catch (Exception e) {
            json.writeError(exchange, new RuntimeException("Row count failed: "
                    + JdbcUrlParser.redactSecrets(e.getMessage()), e));
        }
    }

    private boolean persistRowEstimate(String alias, String schema, String table, long count) {
        try {
            if (!workspaceStore.exists(alias)) {
                return false;
            }
            GraphWorkspace workspace = workspaceStore.load(alias);
            TableWorkspaceNode node = workspace.getTableByQualifiedName(schema + "." + table);
            if (node == null) {
                return false;
            }
            com.sqlcli.graph.ui.service.WorkspaceMutationService mutation =
                    new com.sqlcli.graph.ui.service.WorkspaceMutationService(workspaceStore,
                            new com.sqlcli.graph.workspace.WorkspaceValidator(),
                            new com.sqlcli.graph.ui.service.WorkspaceLockManager());
            var result = mutation.mutate(alias, workspace.getManifest().getRevision(),
                    com.sqlcli.graph.workspace.GraphActor.system, "ui row-count", current -> {
                        TableWorkspaceNode target = current.getTables().get(node.getId());
                        if (target == null) {
                            throw new IllegalArgumentException("table not found: " + node.getId());
                        }
                        // 不 touch：记账动作不认领 updatedBy/updatedAt，见 BaseGraphObject#touch
                        target.setRowEstimate(count);
                        return new com.sqlcli.graph.ui.service.WorkspaceMutationService.MutationOutcome(
                                node.getId(), com.sqlcli.graph.workspace.ChangeOperation.update);
                    });
            return result.isSuccess();
        } catch (Exception e) {
            return false;
        }
    }

    private void handleImport(HttpExchange exchange, String path) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        String alias = path.substring("/api/aliases/".length(), path.length() - "/import".length());
        DatabaseConfig config = aliases.get(alias);
        if (config == null) {
            json.writeNotFound(exchange, "Unknown alias: " + alias);
            return;
        }
        if (!json.requireAllowedOrigin(exchange, allowedOrigin)) return;
        try (Connection connection = new ConnectionManager().getConnection(config);
             WorkspaceMetadataProvider provider = WorkspaceMetadataProviderFactory.create(connection, config)) {
            // 带 schema/table 参数就是单表刷新，不带则整库导入。
            // 两者都走 merge，人工与 Agent 维护的内容不会被覆盖。
            Map<String, String> params = json.parseQueryParams(exchange);
            ImportOptions options = new ImportOptions();
            String schema = json.getParam(params, "schema", null);
            String table = json.getParam(params, "table", null);
            if (schema != null && !schema.isBlank()) {
                options.setSchemaFilter(schema);
            }
            if (table != null && !table.isBlank()) {
                options.setTableFilter(table);
            }
            ImportJob job = new WorkspaceImportService().start(alias, provider, options);
            json.writeJson(exchange, 201, Map.of(
                    "alias", alias,
                    "status", job.getStatus().name(),
                    "message", job.getMessage()));
        } catch (Exception e) {
            json.writeError(exchange, new RuntimeException("Graph import failed: "
                    + JdbcUrlParser.redactSecrets(e.getMessage()), e));
        }
    }
}
