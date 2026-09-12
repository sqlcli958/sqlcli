package com.sqlcli.task;

import com.sqlcli.config.DatabaseConfig;

/**
 * 行数与超时的最终取值。优先级：请求覆盖 &gt; 别名配置 &gt; 内置默认值。0 表示不限制。
 *
 * <p>不是 Stage：它不拒绝、没有 after，是一次纯配置运算，做成 Stage 只是为了凑对称。
 */
record EffectiveLimits(int maxRows, int timeoutSeconds) {

    private static final int DEFAULT_MAX_ROWS = 1000;
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;

    static EffectiveLimits resolve(SqlTaskRequest request) {
        DatabaseConfig config = request.config();
        int maxRows = request.maxRowsOverride() != null
                ? request.maxRowsOverride()
                : positiveOr(config.getDefaultQueryLimit(), DEFAULT_MAX_ROWS);
        int timeout = request.timeoutSecondsOverride() != null
                ? request.timeoutSecondsOverride()
                : positiveOr(config.getQueryTimeoutSeconds(), DEFAULT_TIMEOUT_SECONDS);
        return new EffectiveLimits(Math.max(maxRows, 0), Math.max(timeout, 0));
    }

    /** 别名配置为 0 就是用户明确要「不限制」，不该被内置默认值覆盖；负数才当作未配置处理。 */
    private static int positiveOr(int configured, int fallback) {
        return configured < 0 ? fallback : configured;
    }
}
