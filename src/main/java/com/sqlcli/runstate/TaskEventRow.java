package com.sqlcli.runstate;

/**
 * One row of {@code task_event}：任务时间线上的一格。
 *
 * <p>{@code eventType} 是 Stage 名（submitted / guard / precheck / approval / cipher /
 * dialect / executed）。{@code payload} 是 JSON 文本或 null——只有 precheck 和 executed 带。
 */
public record TaskEventRow(
        long id,
        String eventType,
        String payload,
        long createdAt) {
}
