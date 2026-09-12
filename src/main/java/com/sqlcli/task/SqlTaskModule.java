package com.sqlcli.task;

import com.sqlcli.approval.ApprovalGate;
import com.sqlcli.config.JdbcUrlParser;
import com.sqlcli.connection.ConnectionManager;
import com.sqlcli.connection.SqlInterceptor;
import com.sqlcli.graph.workspace.GraphQueryHints;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sqlcli.parser.SqlParser;
import com.sqlcli.parser.SqlStatementAnalyzer;
import com.sqlcli.recovery.RecoveryBuilder;
import com.sqlcli.runstate.RunStateStore;
import com.sqlcli.sql.Sm4SqlCipherParser;
import com.sqlcli.strategy.DatabaseStrategies;
import com.sqlcli.strategy.DatabaseStrategy;
import com.sqlcli.yearning.YearningQueryExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 全项目唯一的 SQL 执行入口。一次调用 = 一次任务：预检 → 审批 → 改写 → 执行 → 审计，
 * 返回结构化结果。渲染成 csv/json/table 是调用方的事。
 *
 * <p>详细设计：{@code docs/sql-task-module-design.zh-CN.md}。
 */
public final class SqlTaskModule {
    private static final Logger log = LoggerFactory.getLogger(SqlTaskModule.class);

    private final SqlStatementAnalyzer analyzer;
    private final List<SqlTaskStage> stages;
    private final SqlBackend jdbcBackend;
    private final SqlBackend yearningBackend;
    private final TaskAuditRecorder audit;

    public SqlTaskModule() {
        this(new ConnectionManager(), new RunStateStore(), new ApprovalGate());
    }

    public SqlTaskModule(ConnectionManager connectionManager, RunStateStore runState, ApprovalGate approvalGate) {
        this(connectionManager, runState, approvalGate, new GraphWorkspaceStore());
    }

    public SqlTaskModule(ConnectionManager connectionManager, RunStateStore runState, ApprovalGate approvalGate,
                         GraphWorkspaceStore workspaceStore) {
        this.analyzer = new SqlStatementAnalyzer();
        SqlParser sqlParser = new SqlParser();
        this.stages = List.of(
                new GuardStage(analyzer, sqlParser),
                // DDL 门在审批之前：违反结构规则的 DDL 不该占审批人的时间
                new PolicyStage(workspaceStore),
                new PrecheckStage(sqlParser, connectionManager),
                new ApprovalStage(approvalGate),
                new CipherStage(new Sm4SqlCipherParser()),
                new DialectStage(new SqlInterceptor(), analyzer)
        );
        this.jdbcBackend = new JdbcBackend(connectionManager, sqlParser, new RecoveryBuilder(), runState);
        this.yearningBackend = new YearningBackend(new YearningQueryExecutor());
        this.audit = new TaskAuditRecorder(runState);
    }

    public SqlTaskResult execute(SqlTaskRequest request) {
        DatabaseStrategy strategy = DatabaseStrategies.resolve(request.config());
        EffectiveLimits limits = EffectiveLimits.resolve(request);
        SqlTaskContext ctx = new SqlTaskContext(request, strategy, limits, backendFor(request));
        // 审计需要知道是不是写操作才能决定要不要建 task_run，所以类型检测提到这里做一次；
        // GuardStage 沿用这个结果，不再重复解析。
        ctx.sqlType = analyzer.detectSqlType(ctx.sql);

        audit.begin(ctx);
        SqlTaskResult result = null;
        int completedStages = 0;
        try {
            for (SqlTaskStage stage : stages) {
                stage.before(ctx);
                completedStages++;
                audit.event(ctx, stage.name());
            }
            if (!request.dryRun()) {
                ctx.backend.execute(ctx);
                // 时间线上「执行完成」这一格：没有它，审批人看到的最后一步是 dialect，
                // 分不出「批准之后真跑了」和「批准之后挂了」。
                audit.event(ctx, "executed");
                for (int i = completedStages - 1; i >= 0; i--) {
                    stages.get(i).after(ctx);
                }
            }
            result = buildSucceeded(ctx);
        } catch (SqlTaskRejected e) {
            result = buildRejected(ctx, e.getMessage());
            audit.terminal(ctx, "rejected", result.errorSummary());
        } catch (Exception e) {
            log.error("SQL task failed ({}): {}", e.getClass().getSimpleName(),
                    JdbcUrlParser.redactSecrets(e.getMessage()));
            result = buildFailed(ctx, e);
            audit.terminal(ctx, "failed", result.errorSummary());
        } finally {
            audit.finish(ctx, result != null ? result : buildFailed(ctx, new IllegalStateException("unreachable")));
        }
        return result;
    }

    /** 唯一一处知道"哪个别名走哪个后端"的地方。第三个后端出现时只改这里。 */
    private SqlBackend backendFor(SqlTaskRequest request) {
        return SqlTaskUtil.isYearning(request.config()) ? yearningBackend : jdbcBackend;
    }

    private SqlTaskResult buildSucceeded(SqlTaskContext ctx) {
        return SqlTaskResult.builder()
                .sqlType(ctx.sqlType)
                .executedSql(ctx.sql)
                .taskId(ctx.taskRunId > 0 ? String.valueOf(ctx.taskRunId) : null)
                .columns(ctx.resultColumns)
                .rows(ctx.resultRows)
                .truncated(ctx.resultTruncated)
                .elapsedMs(ctx.elapsedMs())
                .affectedRows(ctx.affectedRows)
                .recoveryId(ctx.recoveryId > 0 ? ctx.recoveryId : null)
                .precheck(ctx.precheck)
                .notices(ctx.notices)
                .succeeded();
    }

    private SqlTaskResult buildRejected(SqlTaskContext ctx, String reason) {
        return SqlTaskResult.builder()
                .sqlType(ctx.sqlType)
                .executedSql(ctx.sql)
                .taskId(ctx.taskRunId > 0 ? String.valueOf(ctx.taskRunId) : null)
                .elapsedMs(ctx.elapsedMs())
                .precheck(ctx.precheck)
                .notices(ctx.notices)
                .rejected(JdbcUrlParser.redactSecrets(reason));
    }

    private SqlTaskResult buildFailed(SqlTaskContext ctx, Exception e) {
        String summary = JdbcUrlParser.redactSecrets(e.getMessage());
        Throwable root = rootCause(e);
        String rootMessage = root == null || root == e || root.getMessage() == null
                ? null
                : root.getClass().getSimpleName() + ": " + JdbcUrlParser.redactSecrets(root.getMessage());
        String hint = graphHint(ctx, root != null && root.getMessage() != null ? root.getMessage() : summary);
        if (hint != null) {
            summary = summary + "\n" + hint;
        }
        return SqlTaskResult.builder()
                .sqlType(ctx.sqlType)
                .executedSql(ctx.sql)
                .taskId(ctx.taskRunId > 0 ? String.valueOf(ctx.taskRunId) : null)
                .elapsedMs(ctx.elapsedMs())
                .precheck(ctx.precheck)
                .failed(summary, rootMessage);
    }

    /**
     * 报错提到的表/列在图谱里做模糊纠错，Agent 拿着候选直接重试。
     * 先用消息正则判断是不是「标识符不存在」类，是才加载图谱——连接类失败零开销。
     */
    private String graphHint(SqlTaskContext ctx, String message) {
        if (!GraphQueryHints.looksLikeUnknownIdentifier(message)) {
            return null;
        }
        try {
            GraphWorkspaceStore store = new GraphWorkspaceStore();
            String aliasName = ctx.config.getAliasName();
            if (!store.exists(aliasName)) {
                return null;
            }
            return GraphQueryHints.suggestForError(message, ctx.sql, store.load(aliasName));
        } catch (Exception ignored) {
            return null;
        }
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
