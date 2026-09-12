package com.sqlcli.recovery;

import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.config.JdbcUrlParser;
import com.sqlcli.connection.ConnectionManager;
import com.sqlcli.runstate.RecoveryArtifactRow;
import com.sqlcli.runstate.RunStateStore;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 恢复文件的受控执行器（设计见 sql-task-module-design §17）。
 *
 * <p>恢复文件是多语句（DELETE 的恢复是 N 条 INSERT），而 {@code SqlTaskModule}
 * 一次调用一个独立事务且拒绝多语句——逐条走 Module 会把回滚做成 N 个互相独立的
 * 事务，中途失败就停在"既非执行前也非执行后"的第三种状态。所以恢复执行走这条
 * 平行路径：<b>单连接单事务，全部成功才 commit，任一失败整体 rollback</b>。
 * 审批（kind=recovery，调用方负责）与审计（sql_execution，source=recovery）都不绕过。
 */
public class RecoveryExecutor {

    private final ConnectionManager connectionManager;
    private final RunStateStore runState;

    public RecoveryExecutor(ConnectionManager connectionManager, RunStateStore runState) {
        this.connectionManager = connectionManager;
        this.runState = runState;
    }

    /** 解析好的恢复脚本：来源说明、逐条语句、回滚段原文（给审批 detail 用）。 */
    public record RecoveryScript(String source, List<String> statements, String rollbackText) {
    }

    /** 回滚要写库，readonly 别名连审批都不该建。 */
    public static void requireWritable(DatabaseConfig config) {
        if (Boolean.TRUE.equals(config.getReadonly())) {
            throw new IllegalArgumentException(
                    "别名 " + config.getAliasName() + " 配置为只读，不能执行回滚");
        }
    }

    /**
     * 取出这条执行记录的回滚脚本并切成语句。校验：登记过、内容可读、回滚段非空，
     * 任何一条不满足都以中文说明拒绝。
     *
     * <p>脚本一律在运行库里，没有第二个来源。
     */
    public RecoveryScript loadScript(long executionId) throws Exception {
        RecoveryArtifactRow artifact = runState.findRecovery(executionId);
        if (artifact == null) {
            throw new IllegalArgumentException("执行记录 #" + executionId + " 没有登记回滚脚本");
        }
        String source = "运行库 #" + artifact.id();
        if (!artifact.hasScript()) {
            throw new IllegalArgumentException("回滚脚本内容为空：" + source);
        }
        String rollbackText = artifact.rollbackSql().trim();
        List<String> statements = splitStatements(rollbackText);
        if (statements.isEmpty()) {
            throw new IllegalArgumentException("没有可执行的回滚语句：" + source);
        }
        return new RecoveryScript(source, statements, rollbackText);
    }

    /**
     * 单连接单事务执行整个脚本。
     *
     * <p>审计规则（设计 §17.4）：全部成功提交后，每条语句落一行 success + 一行整体
     * success；第 N 条失败时只落那条 failed + 一行整体 failed——前 N-1 条已随事务
     * 回滚、没有生效，给它们记 success 会让审计声称发生了没发生的事。
     *
     * @return 成功执行（并已提交）的语句数
     */
    public int execute(DatabaseConfig config, long executionId, RecoveryScript script) throws Exception {
        requireWritable(config);
        List<String> statements = script.statements();
        List<long[]> timings = new ArrayList<>(); // {startedAt, elapsedMs} 按语句序
        int index = 0;
        String current = null;
        long overallStarted = System.currentTimeMillis();
        long overallNanos = System.nanoTime();
        Connection conn = null;
        try {
            conn = connectionManager.getConnection(config);
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                for (String sql : statements) {
                    current = sql;
                    index++;
                    long startedAt = System.currentTimeMillis();
                    long t0 = System.nanoTime();
                    stmt.executeUpdate(sql);
                    timings.add(new long[]{startedAt, (System.nanoTime() - t0) / 1_000_000});
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                String summary = JdbcUrlParser.redactSecrets(String.valueOf(e.getMessage()));
                recordStatement(config, current, "failed", summary, executionId);
                recordOverall(config, executionId, statements.size(), "failed",
                        "第 " + index + " 条回滚语句执行失败，已整体回滚：" + summary,
                        overallStarted, overallNanos);
                throw new IllegalStateException(
                        "第 " + index + " 条回滚语句执行失败，已整体回滚：" + summary, e);
            } finally {
                try {
                    conn.setAutoCommit(true); // 连接回池前复原
                } catch (Exception ignored) {
                    // 复原失败不掩盖真正的执行结果
                }
            }
        } catch (IllegalStateException rethrown) {
            throw rethrown;
        } catch (Exception e) {
            // 连不上库：一条语句都没生效，只落整体 failed。
            String summary = JdbcUrlParser.redactSecrets(String.valueOf(e.getMessage()));
            recordOverall(config, executionId, statements.size(), "failed",
                    "回滚未能执行：" + summary, overallStarted, overallNanos);
            throw new IllegalStateException("回滚未能执行：" + summary, e);
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (Exception ignored) {
                    // commit 已持久化，close 失败不改变结果
                }
            }
        }
        for (int i = 0; i < statements.size(); i++) {
            long[] timing = timings.get(i);
            recordStatementAt(config, statements.get(i), "success", null, executionId, timing[0], timing[1]);
        }
        recordOverall(config, executionId, statements.size(), "success", null, overallStarted, overallNanos);
        return statements.size();
    }

    private void recordStatement(DatabaseConfig config, String sql, String status, String error, long rerunOf) {
        if (sql == null) return;
        recordStatementAt(config, sql, status, error, rerunOf, System.currentTimeMillis(), 0);
    }

    private void recordStatementAt(DatabaseConfig config, String sql, String status, String error,
                                   long rerunOf, long startedAt, long elapsedMs) {
        runState.recordExecution(config.getAliasName(), firstWord(sql), sql, status, error, null,
                elapsedMs, startedAt, "recovery", rerunOf, config.getDefaultSchema());
    }

    private void recordOverall(DatabaseConfig config, long executionId, int statementCount, String status,
                               String error, long startedAt, long startedNanos) {
        runState.recordExecution(config.getAliasName(), "ROLLBACK",
                "-- 回滚执行记录 #" + executionId + "：共 " + statementCount + " 条语句",
                status, error, null, (System.nanoTime() - startedNanos) / 1_000_000,
                startedAt, "recovery", executionId, config.getDefaultSchema());
    }

    private static String firstWord(String sql) {
        String trimmed = sql.trim();
        int end = trimmed.indexOf(' ');
        return (end < 0 ? trimmed : trimmed.substring(0, end)).toUpperCase(Locale.ROOT);
    }

    /**
     * 引号感知的语句切分：{@code ;} 只有在单引号字符串外才是语句边界。
     * 不能按行切——恢复的值可能带换行；也不能裸按分号切——值可能带分号。
     * {@code ''} 是转义的单引号，正好由"下一个引号翻转状态"自然处理。
     */
    static List<String> splitStatements(String text) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
            }
            if (c == ';' && !inQuote) {
                addIfNotBlank(statements, current);
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        addIfNotBlank(statements, current);
        return statements;
    }

    private static void addIfNotBlank(List<String> statements, StringBuilder current) {
        String sql = current.toString().trim();
        if (!sql.isEmpty() && !sql.startsWith("--")) {
            statements.add(sql);
        }
    }
}
