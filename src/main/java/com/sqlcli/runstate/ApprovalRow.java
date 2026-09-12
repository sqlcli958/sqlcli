package com.sqlcli.runstate;

/**
 * One row of {@code approval_request}.
 *
 * <p>{@code summary} 是脱敏后的一句话，任何时候都能显示；{@code detail} 是原文
 * （SQL 全文或图谱变更描述），审批人有权看到，所以不脱敏。
 *
 * <p>{@code kind}：query / update / graph（对应别名上的三个审批开关），以及
 * recovery（回滚执行，无开关、永远审批）。
 * {@code status}：pending / approved / rejected / expired。
 *
 * <p>{@code taskRunId} 是这次审批背后的写任务；null 表示没有（读操作、图谱变更）。
 * 详情页的预检块和时间线全靠它去找 {@code task_event}。
 *
 * <p>{@code targetId} 是审批针对的对象 id（图谱关系 {@code relation:...}、
 * 执行记录 {@code execution:...}）；详情端点用它把图谱候选的 diff / 证据 / 校验拼出来。
 *
 * <p>{@code payload} 只有 kind=graph 才有：图谱走「先落 db、批准后才写图谱」，
 * 待批准的变更本身（改哪个对象、before/after）就存在这里，批准时靠它重放。
 * 其余 kind 为 null，老记录也为 null（当时还没这一列）。
 *
 * <p>{@code batchId} / {@code seq}：条目是必需单位，批次是可选分组。batchId 为 null
 * 就是一条独立条目（升级前的记录全是这样，天然兼容）；SQL 批次靠 seq 定执行顺序。
 *
 * <p>{@code sessionId} / {@code agentId}：溯源挂在条目上，批次不重复存
 * （一批内的条目同属一次会话）。取不到就是 null，不生成占位 id。
 */
public record ApprovalRow(
        long id,
        String alias,
        String kind,
        String summary,
        String detail,
        String status,
        String reason,
        long createdAt,
        Long decidedAt,
        Long taskRunId,
        String targetId,
        String payload,
        Long batchId,
        Integer seq,
        String sessionId,
        String agentId,
        /** 批次里的 SQL 条目跑完之后指向的那条执行记录；其余一律 null */
        Long executionId,
        /**
         * 关联写任务的终态（success / rejected / failed / running），没有 taskRunId 就是 null。
         *
         * <p>不是这条审批的状态，是批准之后**实际发生了什么**。两者必须分开：query / update
         * 类的审批批准时什么都不做，执行在另一个进程里，结果从不写回本行——只看
         * {@code status} 的话，一条批准后执行失败的 SQL 在列表里和成功的长得一模一样。
         */
        String taskStatus) {

    /** 未提交的批次条目：已经写进库、但还没进任何队列，也不会落地。 */
    public static final String STATUS_DRAFT = "draft";

    /** 等着人裁决。 */
    public static final String STATUS_PENDING = "pending";

    /**
     * 作废：等待超时 / 被中断（SQL 那条阻塞的路），或**被同一对象的新提交取代**（图谱那条）。
     *
     * <p>两种成因共用一个状态是有意的：对提交方和审批人来说结论一样——这条没生效、
     * 也不用再管它。区别写在 {@code reason} 里，UI 上都显示「已作废」。
     */
    public static final String STATUS_EXPIRED = "expired";

    public boolean isDraft() {
        return STATUS_DRAFT.equals(status);
    }
}
