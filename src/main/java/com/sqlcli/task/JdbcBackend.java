package com.sqlcli.task;

import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.connection.ConnectionManager;
import com.sqlcli.parser.ParsedSql;
import com.sqlcli.parser.SqlParseException;
import com.sqlcli.parser.SqlParser;
import com.sqlcli.recovery.RecoveryBuilder;
import com.sqlcli.recovery.RecoveryResult;
import com.sqlcli.runstate.RunStateStore;
import com.sqlcli.strategy.DatabaseCapabilities;
import com.sqlcli.strategy.SqlExecutionPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC 执行后端。路由 switch 与 policy 门控故意不拆开——ClickHouse 的 ALTER mutation
 * 分支证明"允不允许"和"走哪条执行分支"是耦合的，拆开只会变成两个必须同步维护的 switch。
 *
 * <p>读写用两个不同的 {@link Statement}：读的那个设 maxRows（用于探测截断），
 * 写的那个不设——UPDATE/DELETE 要在同一个 Statement 上先读原始行再执行，
 * 设了 maxRows 会静默截断恢复备份（改 5000 行只备份 1000 行，回滚时才发现少了 4000 行）。
 */
final class JdbcBackend implements SqlBackend {
    private static final Logger log = LoggerFactory.getLogger(JdbcBackend.class);
    private static final int MAX_TEXT_CHARS = 4096;

    private final ConnectionManager connectionManager;
    private final SqlParser sqlParser;
    private final RecoveryBuilder recoveryBuilder;
    private final RunStateStore runState;

    JdbcBackend(ConnectionManager connectionManager, SqlParser sqlParser, RecoveryBuilder recoveryBuilder,
                RunStateStore runState) {
        this.connectionManager = connectionManager;
        this.sqlParser = sqlParser;
        this.recoveryBuilder = recoveryBuilder;
        this.runState = runState;
    }

    @Override
    public boolean supportsWrites() {
        return true;
    }

    @Override
    public void execute(SqlTaskContext ctx) throws Exception {
        String cancelToken = ctx.request.cancelToken();
        try (Connection conn = connectionManager.getConnection(ctx.config)) {
            if (!SqlTaskUtil.isWriteOperation(ctx.sqlType)) {
                try (Statement stmt = conn.createStatement()) {
                    if (ctx.limits.timeoutSeconds() > 0) stmt.setQueryTimeout(ctx.limits.timeoutSeconds());
                    if (ctx.limits.maxRows() > 0) stmt.setMaxRows(ctx.limits.maxRows() + 1);
                    registerCancel(cancelToken, stmt);
                    try {
                        readInto(ctx, stmt);
                    } finally {
                        SqlTaskCancelRegistry.unregister(cancelToken);
                    }
                }
                return;
            }
            try (Statement stmt = conn.createStatement()) {
                if (ctx.limits.timeoutSeconds() > 0) stmt.setQueryTimeout(ctx.limits.timeoutSeconds());
                registerCancel(cancelToken, stmt);
                try {
                    routeWrite(ctx, conn, stmt);
                } finally {
                    SqlTaskCancelRegistry.unregister(cancelToken);
                }
            }
        }
    }

    /**
     * 把 {@code stmt.cancel()} 挂进注册表，POST /api/workbench/cancel 按 token 调它。
     * 被 cancel 的语句在驱动侧抛 SQLException（如 MySQL 的 "Query execution was interrupted"），
     * 写路径顺着 executeWithRecovery 的 catch 回滚——中止不会留下半截事务。
     */
    private void registerCancel(String token, Statement stmt) {
        SqlTaskCancelRegistry.register(token, () -> {
            try {
                stmt.cancel();
            } catch (SQLException e) {
                log.debug("Statement.cancel failed: {}", e.getMessage());
            }
        });
    }

    // ------------------------------------------------------------- 读路径

    private void readInto(SqlTaskContext ctx, Statement stmt) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(ctx.sql)) {
            ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();
            List<SqlTaskResult.Column> columns = new ArrayList<>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                columns.add(new SqlTaskResult.Column(meta.getColumnLabel(i), meta.getColumnTypeName(i)));
            }
            List<List<Object>> rows = new ArrayList<>();
            boolean truncated = false;
            int limit = ctx.limits.maxRows();
            while (rs.next()) {
                if (limit > 0 && rows.size() >= limit) {
                    truncated = true;
                    break;
                }
                List<Object> row = new ArrayList<>(columnCount);
                for (int i = 1; i <= columnCount; i++) {
                    row.add(cell(rs, i));
                }
                rows.add(row);
            }
            ctx.resultColumns = columns;
            ctx.resultRows = rows;
            ctx.resultTruncated = truncated;
        }
    }

    /** JDBC 值 → JSON/展示友好值：NULL 保持 null，时间转 ISO 字符串，二进制变占位符，
     *  大整数/高精度小数转字符串避免超出 JS 安全整数范围时静默丢精度。 */
    private Object cell(ResultSet rs, int index) throws SQLException {
        Object value = rs.getObject(index);
        if (value == null || rs.wasNull()) return null;
        if (value instanceof byte[] || value instanceof Blob) return "<binary>";
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime().toString();
        if (value instanceof java.sql.Date date) return date.toLocalDate().toString();
        if (value instanceof java.sql.Time time) return time.toLocalTime().toString();
        if (value instanceof Temporal) return value.toString();
        if (value instanceof Clob clob) {
            long length = Math.min(clob.length(), MAX_TEXT_CHARS);
            return clob.getSubString(1, (int) length);
        }
        if (value instanceof BigDecimal decimal) return decimal.toPlainString();
        if (value instanceof BigInteger || value instanceof Long) return value.toString();
        if (value instanceof Number || value instanceof Boolean || value instanceof String) return value;
        return String.valueOf(value);
    }

    // ------------------------------------------------------------- 写路径

    private void routeWrite(SqlTaskContext ctx, Connection conn, Statement stmt) throws Exception {
        SqlExecutionPolicy policy = ctx.strategy.executionPolicy();
        DatabaseCapabilities capabilities = ctx.strategy.capabilities();
        String type = ctx.config.getType();
        switch (ctx.sqlType) {
            case "INSERT" -> {
                if (!policy.isAllowInsert()) throw new SqlTaskRejected("INSERT is not supported for " + type);
                executeUpdate(ctx, stmt, capabilities);
            }
            case "UPDATE" -> {
                if (!policy.isAllowStandardUpdate()) throw new SqlTaskRejected(policy.getUnsupportedUpdateMessage());
                executeWithRecovery(ctx, conn, stmt, capabilities);
            }
            case "DELETE" -> {
                if (!policy.isAllowStandardDelete()) throw new SqlTaskRejected(policy.getUnsupportedDeleteMessage());
                executeWithRecovery(ctx, conn, stmt, capabilities);
            }
            case "MERGE" -> {
                if (!policy.isAllowInsert()) throw new SqlTaskRejected("MERGE is not supported for " + type);
                executeUpdate(ctx, stmt, capabilities);
            }
            case "ALTER" -> {
                // ClickHouse mutation（ALTER TABLE ... UPDATE/DELETE）不走 DDL 权限检查，
                // 它本质是一次数据变更，不是结构变更。
                if (policy.isAllowAlterMutation() && !policy.isGenerateRecoverySql()) {
                    executeDdl(ctx, stmt);
                } else {
                    if (!policy.isAllowDdl()) throw new SqlTaskRejected("DDL operations are not supported for " + type);
                    executeDdl(ctx, stmt);
                }
            }
            case "CREATE", "DROP", "TRUNCATE", "GRANT", "REVOKE" -> {
                if (!policy.isAllowDdl()) throw new SqlTaskRejected("DDL operations are not supported for " + type);
                executeDdl(ctx, stmt);
            }
            default -> throw new IllegalStateException("unexpected write sqlType: " + ctx.sqlType);
        }
    }

    private void executeUpdate(SqlTaskContext ctx, Statement stmt, DatabaseCapabilities capabilities) throws SQLException {
        int affected = stmt.executeUpdate(ctx.sql);
        ctx.affectedRows = SqlTaskUtil.reliableAffectedRows(capabilities, affected);
    }

    private void executeDdl(SqlTaskContext ctx, Statement stmt) throws SQLException {
        stmt.execute(ctx.sql);
    }

    /**
     * 事务边界：同连接重读原始行 → 恢复脚本先入库（失败即中止，不执行写）→ 事务内执行 →
     * 提交；执行失败则回滚，读到一半的状态不留。
     *
     * <p>「先入库再执行」这个顺序是安全性本身，不是实现细节：反过来写，进程在两步之间
     * 挂掉就留下一次改完且无从回滚的写。恢复脚本存的是运行库（SQLite），不是文件。
     */
    private void executeWithRecovery(SqlTaskContext ctx, Connection conn, Statement stmt,
                                     DatabaseCapabilities capabilities) throws Exception {
        DatabaseConfig config = ctx.config;
        if (!capabilities.isSupportsRecoverySql()) {
            throw new SqlTaskRejected("数据库类型 " + config.getType()
                    + " 不支持恢复 SQL（无事务或回滚语义），已拒绝执行需要恢复保护的 UPDATE/DELETE。");
        }
        ParsedSql parsedSql;
        try {
            parsedSql = sqlParser.parse(ctx.sql);
        } catch (SqlParseException e) {
            throw new SqlTaskRejected("Failed to parse SQL for recovery: " + e.getMessage()
                    + "\nThe SQL may be too complex.");
        }
        if (parsedSql.isComplex()) {
            throw new SqlTaskRejected("Complex SQL is not supported for recovery SQL generation: "
                    + parsedSql.getComplexityReason());
        }

        List<String> primaryKeyColumns = "UPDATE".equals(ctx.sqlType)
                ? RecoverySupport.primaryKeyColumns(conn, parsedSql.getTableName(), config)
                : List.of();
        if ("UPDATE".equals(ctx.sqlType) && primaryKeyColumns.isEmpty()) {
            throw new SqlTaskRejected("UPDATE rejected: target table has no reliable primary key for recovery");
        }

        conn.setAutoCommit(false);
        try {
            String querySql = parsedSql.buildQuerySql();
            log.debug("Querying original data: {}", querySql);
            ResultSet rs = stmt.executeQuery(querySql);
            RecoveryResult recoveryResult = recoveryBuilder.build(parsedSql, rs, config.getAliasName(), primaryKeyColumns);

            // 入库失败必须中止整个操作——这时还没执行写，catch 块的 rollback 无事可回，
            // 只是统一走同一条路径，不需要在这里特殊处理。
            // 一行都没匹配上时没有恢复脚本可存，也就没什么可回滚的，照常执行。
            long artifactId = 0;
            if (!recoveryResult.rollbackText().isBlank()) {
                artifactId = runState.saveRecoveryScript(recoveryResult.rollbackText(),
                        recoveryResult.backupText());
                if (artifactId <= 0) {
                    throw new IllegalStateException("回滚脚本无法写入运行库，已中止执行。"
                            + "检查 ~/.sql-cli/sqlcli.db 是否可写。");
                }
            }

            int affected = stmt.executeUpdate(ctx.sql);
            conn.commit();

            ctx.affectedRows = SqlTaskUtil.reliableAffectedRows(capabilities, affected);
            ctx.recoveryId = artifactId;
        } catch (Exception e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }
}
