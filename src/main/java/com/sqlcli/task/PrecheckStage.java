package com.sqlcli.task;

import com.sqlcli.approval.ApprovalGate;
import com.sqlcli.config.JdbcUrlParser;
import com.sqlcli.connection.ConnectionManager;
import com.sqlcli.parser.ParsedSql;
import com.sqlcli.parser.SqlParseException;
import com.sqlcli.parser.SqlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * 写操作（UPDATE/DELETE）的预检：目标表、能不能生成恢复 SQL、预估影响行数。
 * 给审批人和 dry-run 调用方看，不影响是否放行。
 *
 * <p>只在真正需要时才碰数据库——命中审批开关，或调用方要 dry-run 结果——
 * 普通直执行路径零额外开销。
 *
 * <p><b>连接纪律</b>：动态部分自己借连接、查完立即还，绝不带着连接进下一个 Stage。
 * 下一个 Stage 是审批，最长阻塞 10 分钟，{@link ApprovalGate#MAX_WAITERS} 个等待者
 * 各占一个池连接会把连接池抽干，别的查询一个都进不来。时序必须是：
 * 借连接 → COUNT → 还连接 → 阻塞等审批 → 重新借连接执行。
 */
final class PrecheckStage implements SqlTaskStage {
    private static final Logger log = LoggerFactory.getLogger(PrecheckStage.class);

    private final SqlParser sqlParser;
    private final ConnectionManager connectionManager;

    PrecheckStage(SqlParser sqlParser, ConnectionManager connectionManager) {
        this.sqlParser = sqlParser;
        this.connectionManager = connectionManager;
    }

    @Override
    public String name() {
        return "precheck";
    }

    @Override
    public void before(SqlTaskContext ctx) {
        if (!"UPDATE".equals(ctx.sqlType) && !"DELETE".equals(ctx.sqlType)) {
            return;
        }

        ParsedSql parsed;
        try {
            parsed = sqlParser.parse(ctx.sql);
        } catch (SqlParseException e) {
            ctx.precheck = Precheck.skipped(null, "无法解析: " + e.getMessage());
            return;
        }
        if (parsed.isComplex()) {
            ctx.precheck = Precheck.skipped(parsed.getTableName(), parsed.getComplexityReason());
            return;
        }

        boolean needsDynamicCheck = ctx.request.dryRun()
                || ApprovalGate.isEnabled(ctx.config, ApprovalGate.Kind.UPDATE);
        if (!needsDynamicCheck) {
            ctx.precheck = null;
            return;
        }

        ctx.precheck = runDynamicPrecheck(ctx, parsed);
    }

    private Precheck runDynamicPrecheck(SqlTaskContext ctx, ParsedSql parsed) {
        try (Connection conn = connectionManager.getConnection(ctx.config)) {
            List<String> primaryKey = RecoverySupport.primaryKeyColumns(conn, parsed.getTableName(), ctx.config);
            boolean recoverySupported = ctx.strategy.capabilities().isSupportsRecoverySql()
                    && !primaryKey.isEmpty();
            Long estimatedRows = parsed.hasWhereClause() ? countMatching(conn, parsed) : null;
            return new Precheck(parsed.getTableName(), recoverySupported, primaryKey, estimatedRows, null);
        } catch (Exception e) {
            log.debug("precheck failed for {}", parsed.getTableName(), e);
            return Precheck.skipped(parsed.getTableName(),
                    "预检查询失败: " + JdbcUrlParser.redactSecrets(e.getMessage()));
        }
    }

    private Long countMatching(Connection conn, ParsedSql parsed) throws SQLException {
        String countSql = "SELECT COUNT(*) FROM " + parsed.getTableName() + " WHERE " + parsed.getWhereClause();
        try (PreparedStatement ps = conn.prepareStatement(countSql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : null;
        }
    }
}
