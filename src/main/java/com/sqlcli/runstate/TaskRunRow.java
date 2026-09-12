package com.sqlcli.runstate;

/**
 * One row of {@code task_run}。
 *
 * <p>{@code status}：running / success / rejected / failed。
 */
public record TaskRunRow(
        long id,
        String taskType,
        String actor,
        String alias,
        String status,
        long createdAt,
        long updatedAt) {
}
