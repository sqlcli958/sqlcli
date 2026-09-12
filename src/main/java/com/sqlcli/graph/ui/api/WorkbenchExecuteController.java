package com.sqlcli.graph.ui.api;

import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.connection.QueryExecutionOptions;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sqlcli.graph.ui.JsonHttpSupport;
import com.sqlcli.task.Precheck;
import com.sqlcli.task.SqlTaskCancelRegistry;
import com.sqlcli.task.SqlTaskModule;
import com.sqlcli.task.SqlTaskRequest;
import com.sqlcli.task.SqlTaskResult;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * POST /api/workbench/execute —— SQL 工作台的执行入口。
 *
 * <p>和 {@link SqlRerunController} 的本质区别是<b>没有只读白名单</b>：工作台就是用来
 * 敲写语句的。readonly 别名、Yearning 只读边界、UPDATE/DELETE 必须带 WHERE、审批闸门、
 * 恢复 SQL、事务边界、行数上限、SM4 透传，全部由 {@link SqlTaskModule} 内部处理——
 * 这里再写一遍就是第二份会走样的规则。
 *
 * <p><b>响应永远是 200 + 结构化 JSON。</b>被拒绝（readonly / 无 WHERE / 审批不通过）是
 * 正常业务结果，要连同预检信息一起摆给用户看，不是 HTTP 错误。只有连请求都读不懂
 * （非 POST、Origin 不对、别名不存在、SQL 为空）才走错误码。
 *
 * <p>审批开着时 {@code taskModule.execute} 会阻塞到有人在评审页裁决为止（上限 10 分钟），
 * 这个 handler 跟着挂住——线程池按 {@code ApprovalGate.MAX_WAITERS} 留了余量。
 */
public class WorkbenchExecuteController {

    private final Map<String, DatabaseConfig> aliases;
    private final String defaultAlias;
    private final String allowedOrigin;
    private final JsonHttpSupport json;
    private final SqlTaskModule taskModule;
    /** 只读：给结果列头补业务含义用；null 或图谱不存在时响应里没有 columnMeta，绝不触发导入。 */
    private final GraphWorkspaceStore workspaceStore;

    public WorkbenchExecuteController(Map<String, DatabaseConfig> aliases, String defaultAlias,
                                      String allowedOrigin, JsonHttpSupport json, SqlTaskModule taskModule,
                                      GraphWorkspaceStore workspaceStore) {
        this.aliases = aliases;
        this.defaultAlias = defaultAlias;
        this.allowedOrigin = allowedOrigin;
        this.json = json;
        this.taskModule = taskModule;
        this.workspaceStore = workspaceStore;
    }

    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        try {
            if (!json.requireAllowedOrigin(exchange, allowedOrigin)) return;
            String alias = json.getParam(json.parseQueryParams(exchange), "alias", defaultAlias);
            if (alias == null || alias.isBlank()) {
                throw new IllegalArgumentException("alias is required");
            }
            DatabaseConfig config = aliases.get(alias);
            if (config == null) {
                json.writeNotFound(exchange, "Unknown alias: " + alias);
                return;
            }
            ExecuteRequest request = json.readBody(exchange, ExecuteRequest.class);
            String sql = request == null || request.sql == null ? null : request.sql.trim();
            if (sql == null || sql.isEmpty()) {
                throw new IllegalArgumentException("sql is required");
            }
            boolean dryRun = request.dryRun != null && request.dryRun;

            SqlTaskResult result = taskModule.execute(new SqlTaskRequest(config, sql,
                    QueryExecutionOptions.forAlias(config, "json", Set.of(), false),
                    SqlTaskRequest.Origin.ui_workbench, null, null, null, dryRun, request.cancelToken));
            Map<String, Object> body = toBody(result);
            body.put("columnMeta", columnMeta(alias, sql, result));
            json.writeOk(exchange, body);
        } catch (Exception e) {
            json.writeError(exchange, e);
        }
    }

    /**
     * POST /api/workbench/cancel —— 按执行请求带上的 cancelToken 中止那次执行。
     *
     * <p>{@code cancelled=false} 不是错误：执行已经结束（token 已注销）时就该这么答，
     * 前端据此不把结果标成「已中止」。中止只对已注册进 {@link SqlTaskCancelRegistry}
     * 的阶段生效：JDBC 语句执行中（调 {@code Statement.cancel()}）和审批等待中
     * （打断等待，审批置 expired）；建连接那几秒没有可中止的对象。
     */
    public void handleCancel(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        try {
            if (!json.requireAllowedOrigin(exchange, allowedOrigin)) return;
            CancelRequest request = json.readBody(exchange, CancelRequest.class);
            String token = request == null || request.cancelToken == null ? null : request.cancelToken.trim();
            if (token == null || token.isEmpty()) {
                throw new IllegalArgumentException("cancelToken is required");
            }
            json.writeOk(exchange, Map.of("cancelled", SqlTaskCancelRegistry.cancel(token)));
        } catch (Exception e) {
            json.writeError(exchange, e);
        }
    }

    /**
     * 多结果集分标签故意不做：{@link SqlTaskModule} 强制单语句（GuardStage 拒绝多语句），
     * 一次执行最多一个结果集，标签页永远只有一个。
     */
    static Map<String, Object> toBody(SqlTaskResult result) {
        List<Map<String, Object>> columns = result.columns().stream().map(c -> {
            Map<String, Object> column = new LinkedHashMap<>();
            column.put("name", c.name());
            column.put("type", c.jdbcTypeName());
            return column;
        }).toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", result.status().name());
        body.put("sqlType", result.sqlType());
        body.put("columns", columns);
        body.put("rows", result.rows());
        body.put("rowCount", result.rows().size());
        body.put("truncated", result.truncated());
        body.put("elapsedMs", result.elapsedMs());
        body.put("affectedRows", result.affectedRows());
        body.put("recoveryId", result.recoveryId());
        body.put("taskId", result.taskId());
        body.put("precheck", precheck(result.precheck()));
        body.put("errorSummary", result.errorSummary());
        // advisory 级规则违规、规则检查为什么没跑：不改状态，但工作台要显示出来
        body.put("notices", result.notices());
        return body;
    }

    /**
     * 结果列头的业务含义补充。规则见 {@link WorkbenchColumnMeta}：单一表 + 裸列名精确匹配，
     * 对不上就 null——UI 保持裸列名。图谱只读，任何异常（含图谱损坏）都静默降级为不标注。
     */
    private Map<String, Map<String, Object>> columnMeta(String alias, String sql, SqlTaskResult result) {
        if (workspaceStore == null || result.status() != SqlTaskResult.Status.SUCCEEDED
                || !"SELECT".equals(result.sqlType()) || result.columns().isEmpty()) {
            return null;
        }
        try {
            if (!workspaceStore.exists(alias)) {
                return null;
            }
            Map<String, Map<String, Object>> meta = WorkbenchColumnMeta.resolve(sql,
                    workspaceStore.load(alias),
                    result.columns().stream().map(SqlTaskResult.Column::name).toList());
            return meta.isEmpty() ? null : meta;
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> precheck(Precheck precheck) {
        if (precheck == null) {
            return null;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("table", precheck.table());
        body.put("recoverySupported", precheck.recoverySupported());
        body.put("primaryKey", precheck.primaryKey());
        body.put("estimatedRows", precheck.estimatedRows());
        body.put("skippedReason", precheck.skippedReason());
        return body;
    }

    public static final class ExecuteRequest {
        public String sql;
        /** true 时只跑到预检为止：拿影响行数和可恢复性画确认面板，不碰数据库写路径。 */
        public Boolean dryRun;
        /** 客户端生成的中止令牌；带了才可被 POST /api/workbench/cancel 中止。 */
        public String cancelToken;
    }

    public static final class CancelRequest {
        public String cancelToken;
    }
}
