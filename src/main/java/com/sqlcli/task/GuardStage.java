package com.sqlcli.task;

import com.sqlcli.parser.SqlParseException;
import com.sqlcli.parser.SqlParser;
import com.sqlcli.parser.SqlStatementAnalyzer;
import com.sqlcli.strategy.SqlExecutionPolicy;

import java.util.List;

/**
 * 纯静态检查，不碰数据库：类型检测、只读保护、多语句、UPDATE/DELETE 必须有 WHERE、
 * Yearning 后端不支持写。
 */
final class GuardStage implements SqlTaskStage {

    private final SqlStatementAnalyzer analyzer;
    private final SqlParser sqlParser;

    GuardStage(SqlStatementAnalyzer analyzer, SqlParser sqlParser) {
        this.analyzer = analyzer;
        this.sqlParser = sqlParser;
    }

    @Override
    public String name() {
        return "guard";
    }

    @Override
    public void before(SqlTaskContext ctx) throws SqlTaskRejected {
        // 类型已经在 SqlTaskModule.execute() 检测过一次（审计要靠它判断建不建 task_run），
        // 这里不重复解析同一段 SQL。
        SqlExecutionPolicy policy = ctx.strategy.executionPolicy();

        if (policy.isRequireReadonlyGuard()
                && SqlTaskUtil.isWriteOperation(ctx.sqlType) && SqlTaskUtil.isReadonlyAlias(ctx.config)) {
            throw new SqlTaskRejected("Alias '" + ctx.config.getAliasName()
                    + "' is configured as readonly. Write operations are not allowed.");
        }

        List<String> statements = analyzer.splitStatements(ctx.sql);
        if (statements.size() > 1 && !policy.isAllowMultipleStatements()) {
            throw new SqlTaskRejected("Multiple statements are not supported for "
                    + ctx.config.getType() + ". Please execute one statement at a time.");
        }

        if ("SHOW".equals(ctx.sqlType) && !policy.isAllowShow()) {
            throw new SqlTaskRejected("SHOW statements are not supported for " + ctx.config.getType() + ".");
        }

        // 问后端而不是判断「是不是 Yearning」：支不支持写是后端自己的属性。
        if (SqlTaskUtil.isWriteOperation(ctx.sqlType) && !ctx.backend.supportsWrites()) {
            throw new SqlTaskRejected(
                    ctx.config.getAccessMode() + " access mode only supports read-only queries. SQL type: "
                            + ctx.sqlType);
        }

        requireWhereForStandardDml(ctx, policy);
    }

    /**
     * UPDATE/DELETE 必须带 WHERE，但只在该数据库把这条语句当「标准 DML」执行时检查——
     * ClickHouse 这类不支持标准 UPDATE 的库会在 Backend 里给出「不支持」的提示，
     * 这里检查 WHERE 反而会用错误的理由挡住它，让人以为加个 WHERE 就能过。
     */
    private void requireWhereForStandardDml(SqlTaskContext ctx, SqlExecutionPolicy policy) throws SqlTaskRejected {
        boolean standardDml = "UPDATE".equals(ctx.sqlType) && policy.isAllowStandardUpdate()
                || "DELETE".equals(ctx.sqlType) && policy.isAllowStandardDelete();
        if (!standardDml) {
            return;
        }
        try {
            sqlParser.requireWhereClause(ctx.sql);
        } catch (SqlParseException e) {
            throw new SqlTaskRejected(e.getMessage());
        }
    }
}
