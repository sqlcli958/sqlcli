package com.sqlcli.runstate;

/**
 * One row of {@code sql_execution}.
 *
 * <p>{@code sql} is the masked text (used in lists); {@code rawSql} is the statement as written,
 * what a re-run replays and what the history detail shows. 老库里写语句没存原文，那里是 null。
 *
 * @param targetSchema 语句命中的 schema，历史筛选用；老记录和解析不出来的语句是 null
 */
public record SqlExecutionRow(
        long id,
        String alias,
        String sqlType,
        String sql,
        String rawSql,
        String status,
        String errorSummary,
        Long affectedRows,
        long elapsedMs,
        long startedAt,
        String source,
        Long rerunOf,
        String targetSchema) {
}
