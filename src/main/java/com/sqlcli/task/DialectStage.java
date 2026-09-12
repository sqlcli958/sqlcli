package com.sqlcli.task;

import com.sqlcli.connection.SqlInterceptor;
import com.sqlcli.parser.SqlStatementAnalyzer;

/**
 * 方言预处理（去分号、ClickHouse SHOW 不加 LIMIT 等），然后重新检测语句类型——
 * SM4 改写或方言预处理都可能改变 SQL 结构。
 *
 * <p>审批用改写前检出的类型（{@link GuardStage} 检出的那个），路由用这里重检的类型。
 * 这个不对称是现状延续，不是新引入的：没有证据它造成过问题，改了反而要重新验证
 * 审批分类是否还对。
 */
final class DialectStage implements SqlTaskStage {

    private final SqlInterceptor interceptor;
    private final SqlStatementAnalyzer analyzer;

    DialectStage(SqlInterceptor interceptor, SqlStatementAnalyzer analyzer) {
        this.interceptor = interceptor;
        this.analyzer = analyzer;
    }

    @Override
    public String name() {
        return "dialect";
    }

    @Override
    public void before(SqlTaskContext ctx) {
        ctx.sql = interceptor.preprocess(ctx.config, ctx.sql);
        ctx.sqlType = analyzer.detectSqlType(ctx.sql);
    }
}
