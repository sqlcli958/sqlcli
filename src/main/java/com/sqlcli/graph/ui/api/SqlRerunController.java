package com.sqlcli.graph.ui.api;

import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.connection.QueryExecutionOptions;
import com.sqlcli.graph.ui.JsonHttpSupport;
import com.sqlcli.parser.SqlStatementAnalyzer;
import com.sqlcli.task.SqlTaskModule;
import com.sqlcli.task.SqlTaskRequest;
import com.sqlcli.task.SqlTaskResult;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * POST /api/sql/execute —— 运行记录里的历史 SQL 重放。
 *
 * <p>只读白名单是这个端点特有的业务规则（重放不该意外跑出写操作），走
 * {@link SqlTaskModule} 之前先在这里挡掉；SM4 解密、执行历史留痕、方言预处理、
 * 行数上限、超时都交给 Module——这是这条路径第一次拥有它们，之前是自己开连接、
 * 自己读 ResultSet 的独立实现，缺 SM4 解密（密文原样返回）也不写 sql_execution。
 */
public class SqlRerunController {

    /** 只读语句白名单，取值来自 {@link SqlStatementAnalyzer#detectSqlType}。 */
    private static final Set<String> READ_ONLY_TYPES =
            Set.of("SELECT", "WITH", "SHOW", "DESC", "DESCRIBE", "EXPLAIN");

    private final Map<String, DatabaseConfig> aliases;
    private final String defaultAlias;
    private final String allowedOrigin;
    private final JsonHttpSupport json;
    private final SqlStatementAnalyzer analyzer = new SqlStatementAnalyzer();
    private final SqlTaskModule taskModule;

    public SqlRerunController(Map<String, DatabaseConfig> aliases, String defaultAlias,
                              String allowedOrigin, JsonHttpSupport json, SqlTaskModule taskModule) {
        this.aliases = aliases;
        this.defaultAlias = defaultAlias;
        this.allowedOrigin = allowedOrigin;
        this.json = json;
        this.taskModule = taskModule;
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
            Long rerunOf = request == null ? null : request.executionId;
            String validated = validate(sql);

            QueryExecutionOptions options = QueryExecutionOptions.forAlias(config, "json", Set.of(), false);
            SqlTaskRequest taskRequest = new SqlTaskRequest(config, validated, options,
                    SqlTaskRequest.Origin.ui_rerun, rerunOf, null, null, false);
            SqlTaskResult result = taskModule.execute(taskRequest);

            if (result.status() == SqlTaskResult.Status.SUCCEEDED) {
                json.writeOk(exchange, toBody(result));
            } else {
                json.writeError(exchange, new RuntimeException(result.errorSummary()));
            }
        } catch (Exception e) {
            json.writeError(exchange, e);
        }
    }

    /** 拒绝空语句、多语句和写语句；别名的 readonly 配置在这里天然满足——白名单本身就是只读的。 */
    String validate(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("sql is required");
        }
        if (sql.contains("<redacted>")) {
            throw new IllegalArgumentException("这条记录只保存了脱敏 SQL，字面量已被替换为 '<redacted>'，无法重放");
        }
        if (analyzer.splitStatements(sql).size() > 1) {
            throw new IllegalArgumentException("只允许执行单条语句");
        }
        String type = analyzer.detectSqlType(sql).toUpperCase(Locale.ROOT);
        if (!READ_ONLY_TYPES.contains(type)) {
            throw new IllegalArgumentException("只允许执行只读语句（SELECT/WITH/SHOW/DESC/DESCRIBE/EXPLAIN），"
                    + "当前语句类型：" + type);
        }
        return sql;
    }

    private Map<String, Object> toBody(SqlTaskResult result) {
        List<Map<String, Object>> columns = result.columns().stream().map(c -> {
            Map<String, Object> column = new LinkedHashMap<>();
            column.put("name", c.name());
            column.put("type", c.jdbcTypeName());
            return column;
        }).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("columns", columns);
        body.put("rows", result.rows());
        body.put("rowCount", result.rows().size());
        body.put("elapsedMs", result.elapsedMs());
        body.put("truncated", result.truncated());
        return body;
    }

    public static final class ExecuteRequest {
        public String sql;
        /** 重放的原始执行记录 id，用于把新记录和它关联起来。 */
        public Long executionId;
    }
}
