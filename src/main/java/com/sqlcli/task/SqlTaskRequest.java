package com.sqlcli.task;

import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.connection.QueryExecutionOptions;

/**
 * 一次 SQL 任务的输入。CLI、Web UI 的历史重放、将来的 SQL 工作台都构造同一种请求，
 * 交给 {@link SqlTaskModule#execute} 处理——这是全项目唯一的 SQL 执行入口。
 *
 * @param origin              谁发起的，写进 {@code sql_execution.source}
 * @param rerunOf             重放时关联的原始执行记录 id；不是重放传 null
 * @param maxRowsOverride     CLI {@code --max-rows}；null 表示走别名/内置默认值
 * @param timeoutSecondsOverride 同上，超时版本
 * @param dryRun              true 时只跑到预检为止，不接触数据库、不进审批阻塞——
 *                             用于「先看看要不要批准」的场景，v1 只有 {@link Precheck} 会被填充
 * @param cancelToken         客户端生成的中止令牌；非空时执行链会把「怎么中止」注册进
 *                             {@link SqlTaskCancelRegistry}，POST /api/workbench/cancel 按它中止。
 *                             null 表示这次执行不可被远程中止（CLI 用 Ctrl+C 就够）
 */
public record SqlTaskRequest(
        DatabaseConfig config,
        String sql,
        QueryExecutionOptions options,
        Origin origin,
        Long rerunOf,
        Integer maxRowsOverride,
        Integer timeoutSecondsOverride,
        boolean dryRun,
        String cancelToken
) {
    /** 旧 8 参形态：绝大多数调用方不需要中止令牌。 */
    public SqlTaskRequest(DatabaseConfig config, String sql, QueryExecutionOptions options, Origin origin,
                          Long rerunOf, Integer maxRowsOverride, Integer timeoutSecondsOverride, boolean dryRun) {
        this(config, sql, options, origin, rerunOf, maxRowsOverride, timeoutSecondsOverride, dryRun, null);
    }

    public enum Origin {
        cli, ui_rerun, ui_workbench
    }

    /** 最常见的形态：CLI 或工作台直接发起，不是重放，不覆盖限额，不是 dry run。 */
    public static SqlTaskRequest of(DatabaseConfig config, String sql, QueryExecutionOptions options, Origin origin) {
        return new SqlTaskRequest(config, sql, options, origin, null, null, null, false);
    }
}
