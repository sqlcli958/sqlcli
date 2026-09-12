package com.sqlcli.task;

import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.config.JdbcUrlParser;
import com.sqlcli.connection.ConnectionManager;
import com.sqlcli.parser.ParsedSql;
import com.sqlcli.parser.SqlParseException;
import com.sqlcli.parser.SqlParser;
import com.sqlcli.parser.SqlStatementAnalyzer;
import com.sqlcli.recovery.RecoveryBuilder;
import com.sqlcli.recovery.RecoveryExecutor;
import com.sqlcli.recovery.RecoveryResult;
import com.sqlcli.runstate.ApprovalRow;
import com.sqlcli.runstate.RunStateStore;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 一批 SQL 条目**单连接单事务**落地：按 seq 执行，任一条失败整批 rollback。
 *
 * <p>为什么不逐条走 {@link SqlTaskModule}：那是一条语句一个独立事务，第 7 条失败时
 * 前 6 条已经提交了——「多条语句改一半」正是批次要消掉的那件事。这条路径跟
 * {@link RecoveryExecutor} 是同一个形状（也是它为回滚脚本存在的理由），审批和审计都不绕过。
 *
 * <h2>回滚脚本：按批一份，语句逆序</h2>
 * 第 N 条的原始行只有在前 N-1 条都跑完之后才是对的，所以恢复段必须逐条现读、
 * 边跑边攒，最后整批倒过来存一份——回滚时倒着执行才回得到起点。
 *
 * <p>存的时机是 commit **之前**。这和「先入库再执行」不矛盾：整批在一个事务里，
 * commit 之前没有任何东西是持久的，进程死在中途数据库自己会回滚，没有需要恢复的状态。
 * 入库失败就中止、不 commit。
 *
 * <h2>DDL</h2>
 * 允许进批次（建表 + 初始化现实里就是一批，硬拆更容易出错），但批次在 submit 时
 * 被标成不可回滚——MySQL 的 DDL 隐式提交，rollback 对它无效，假装能回滚更危险。
 */
public class SqlBatchExecutor {

    private final ConnectionManager connectionManager;
    private final RunStateStore runState;
    private final SqlParser sqlParser = new SqlParser();
    private final SqlStatementAnalyzer analyzer = new SqlStatementAnalyzer();
    private final RecoveryBuilder recoveryBuilder = new RecoveryBuilder();

    public SqlBatchExecutor(ConnectionManager connectionManager, RunStateStore runState) {
        this.connectionManager = connectionManager;
        this.runState = runState;
    }

    /** 一条条目的结局。整批失败时 {@code executionId} 为 null——它没有生效过。 */
    public record ItemResult(long approvalId, boolean executed, Long executionId, String error) {
    }

    /** @param recoveryId 整批的回滚脚本；没有可回滚内容（纯 DDL / 纯 SELECT）时为 null */
    public record BatchResult(List<ItemResult> items, Long recoveryId) {
    }

    /**
     * @param items 按 seq 排好、状态为 pending 的条目（被单独否掉的不要传进来）
     * @throws IllegalStateException 任一条失败——整批已 rollback，一条都没生效
     */
    public BatchResult execute(DatabaseConfig config, long batchId, List<ApprovalRow> items)
            throws Exception {
        if (items == null || items.isEmpty()) {
            throw new IllegalStateException("批次 #" + batchId + " 里没有可执行的条目");
        }
        RecoveryExecutor.requireWritable(config);
        List<String> rollbackSegments = new ArrayList<>();
        List<String> backupSegments = new ArrayList<>();
        List<ItemResult> results = new ArrayList<>();
        List<long[]> timings = new ArrayList<>(); // {startedAt, elapsedMs, affectedRows}
        List<String> types = new ArrayList<>();
        long overallStarted = System.currentTimeMillis();
        int index = 0;
        Connection conn = null;
        Long recoveryId = null;
        try {
            conn = connectionManager.getConnection(config);
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                for (ApprovalRow item : items) {
                    index++;
                    String sql = item.detail();
                    if (sql == null || sql.isBlank()) {
                        throw new IllegalStateException("条目 #" + item.id() + " 没有语句原文");
                    }
                    String type = analyzer.detectSqlType(sql);
                    types.add(type);
                    collectRecovery(config, conn, stmt, sql, type, rollbackSegments, backupSegments);
                    long startedAt = System.currentTimeMillis();
                    long t0 = System.nanoTime();
                    long affected = stmt.execute(sql) ? -1 : stmt.getUpdateCount();
                    timings.add(new long[]{startedAt, (System.nanoTime() - t0) / 1_000_000, affected});
                }
                // 逆序：回滚要从最后一条开始撤，顺着来会撞上还没撤掉的后续改动
                if (!rollbackSegments.isEmpty()) {
                    List<String> reversed = new ArrayList<>(rollbackSegments);
                    java.util.Collections.reverse(reversed);
                    recoveryId = runState.saveRecoveryScript(String.join("\n", reversed),
                            String.join("\n", backupSegments));
                    if (recoveryId <= 0) {
                        throw new IllegalStateException("批次回滚脚本无法写入运行库，已整体回滚，"
                                + "一条都没有生效。检查 ~/.sql-cli/sqlcli.db 是否可写。");
                    }
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                String summary = JdbcUrlParser.redactSecrets(String.valueOf(e.getMessage()));
                // 只给失败的那条记审计：前面几条已随事务回滚、没有生效，
                // 给它们记 success 会让审计声称发生了没发生的事。
                ApprovalRow failed = items.get(Math.min(index, items.size()) - 1);
                runState.recordExecution(config.getAliasName(),
                        analyzer.detectSqlType(String.valueOf(failed.detail())), failed.detail(),
                        "failed", summary, null, System.currentTimeMillis() - overallStarted,
                        overallStarted, "batch", null, config.getDefaultSchema());
                throw new IllegalStateException("批次 #" + batchId + " 第 " + index
                        + " 条执行失败，已整体回滚，一条都没有生效：" + summary, e);
            } finally {
                try {
                    conn.setAutoCommit(true); // 连接回池前复原
                } catch (Exception ignored) {
                    // 复原失败不掩盖真正的执行结果
                }
            }
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (Exception ignored) {
                    // commit 已持久化，close 失败不改变结果
                }
            }
        }
        for (int i = 0; i < items.size(); i++) {
            ApprovalRow item = items.get(i);
            long[] timing = timings.get(i);
            long executionId = runState.recordExecution(config.getAliasName(), types.get(i),
                    item.detail(), "success", null, timing[2] < 0 ? null : timing[2], timing[1],
                    timing[0], "batch", null, config.getDefaultSchema());
            if (executionId > 0) runState.attachExecution(item.id(), executionId);
            results.add(new ItemResult(item.id(), true, executionId > 0 ? executionId : null, null));
        }
        if (recoveryId != null && recoveryId > 0 && !results.isEmpty()) {
            // 回滚脚本是整批一份，挂到第一条的执行记录上——执行记录页从那里进得去
            Long first = results.get(0).executionId();
            if (first != null) runState.attachRecoveryArtifact(recoveryId, first);
        }
        return new BatchResult(results, recoveryId);
    }

    /**
     * UPDATE / DELETE 在执行**之前**读一遍将被改动的原始行，攒出这条语句的恢复段。
     *
     * <p>读不出恢复段的一律拒绝整批，跟单条路径同一个判据：UPDATE 没有可靠主键、
     * SQL 复杂到解析不了，都是「改完就回不去」，不能因为它在批次里就放宽。
     * DDL 和读语句没有恢复段，跳过。
     */
    private void collectRecovery(DatabaseConfig config, Connection conn, Statement stmt, String sql,
                                 String type, List<String> rollback, List<String> backup)
            throws Exception {
        if (!"UPDATE".equals(type) && !"DELETE".equals(type)) return;
        ParsedSql parsedSql;
        try {
            parsedSql = sqlParser.parse(sql);
        } catch (SqlParseException e) {
            throw new IllegalStateException("无法为这条语句生成恢复 SQL（解析失败）：" + e.getMessage());
        }
        if (parsedSql.isComplex()) {
            throw new IllegalStateException("无法为这条语句生成恢复 SQL："
                    + parsedSql.getComplexityReason());
        }
        List<String> primaryKeyColumns = "UPDATE".equals(type)
                ? RecoverySupport.primaryKeyColumns(conn, parsedSql.getTableName(), config)
                : List.of();
        if ("UPDATE".equals(type) && primaryKeyColumns.isEmpty()) {
            throw new IllegalStateException("UPDATE 被拒绝：目标表没有可靠主键，无法生成恢复 SQL");
        }
        RecoveryResult result;
        try (ResultSet rs = stmt.executeQuery(parsedSql.buildQuerySql())) {
            result = recoveryBuilder.build(parsedSql, rs, config.getAliasName(), primaryKeyColumns);
        }
        if (!result.rollbackText().isBlank()) {
            rollback.add(result.rollbackText());
            backup.add(result.backupText());
        }
    }
}
