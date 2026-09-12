package com.sqlcli.task;

import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.strategy.DatabaseStrategy;

import java.util.List;

/**
 * 一次任务执行期间在 Stage 和 Backend 之间传递的可变状态。不出模块边界。
 *
 * <p>结果字段（columns/rows/...）由 Backend 写入、CipherStage.after 读写（解密）、
 * driver 在最后一步读出组装成 {@link SqlTaskResult}——都是直接字段而不是经过
 * {@code SqlTaskResult.Builder}，因为 after 阶段需要"读出上一步写的行、原地改一遍"，
 * 一个只进不出的 Builder 做不到这件事。
 */
final class SqlTaskContext {
    final SqlTaskRequest request;
    final DatabaseConfig config;
    final DatabaseStrategy strategy;
    final EffectiveLimits limits;
    /**
     * 这次任务用哪个后端，在进 Stage 循环之前就定下来。
     *
     * <p>放进 context 而不是让 GuardStage 自己判断「是不是 Yearning」：后端支不支持写
     * 是后端自己的属性，Guard 只要问 {@link SqlBackend#supportsWrites()}。
     * 否则加第三个后端要同时改 backendFor 和 GuardStage 两处。
     */
    final SqlBackend backend;
    final long startedAtMillis;
    final long startedNanos;

    /** 当前 SQL 文本；CipherStage / DialectStage 会改写它。 */
    String sql;
    /** 当前检出的语句类型；DialectStage 改写后会重检并覆盖。 */
    String sqlType;
    /** 写操作才有；由 TaskAuditRecorder 在 begin 时填充，0 表示落库失败。 */
    long taskRunId;
    Precheck precheck;

    List<SqlTaskResult.Column> resultColumns = List.of();
    List<List<Object>> resultRows = List.of();
    boolean resultTruncated;
    Integer affectedRows;
    /** recovery_artifact 的 id；0 表示这次写没有可回滚的脚本。 */
    long recoveryId;
    /** 不挡执行但要让调用方看见的话：advisory 级的规则违规、规则检查为什么没跑。 */
    final List<String> notices = new java.util.ArrayList<>();

    SqlTaskContext(SqlTaskRequest request, DatabaseStrategy strategy, EffectiveLimits limits, SqlBackend backend) {
        this.request = request;
        this.config = request.config();
        this.strategy = strategy;
        this.limits = limits;
        this.backend = backend;
        this.sql = request.sql();
        this.startedAtMillis = System.currentTimeMillis();
        this.startedNanos = System.nanoTime();
    }

    long elapsedMs() {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
