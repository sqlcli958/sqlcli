package com.sqlcli.runstate;

/**
 * One row of {@code graph_change_log} —— 一次图谱更新的可追溯记录。
 *
 * <p>{@code payload} 是 {@code GraphChangePayload} 的 JSON：改的哪个对象、before/after
 * 长什么样。原来这张表只记「谁在哪个 revision 改了哪个 id」，「改成了什么」查不到；
 * 带上 payload 之后每条记录自己就能回答这个问题。老记录没有这一列，为 null。
 *
 * <p>{@code approvalId} 非空表示这次更新是被那条审批放行的；为空表示别名没开图谱审批、
 * 变更直接生效。
 */
public record GraphChangeRow(
        long id,
        String alias,
        String operation,
        String targetId,
        String actor,
        long revisionBefore,
        long revisionAfter,
        long createdAt,
        String payload,
        Long approvalId,
        String reason) {
}
