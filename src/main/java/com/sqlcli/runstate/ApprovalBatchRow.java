package com.sqlcli.runstate;

/**
 * One row of {@code approval_batch}：一次业务意图下的一组条目。
 *
 * <p>条目是必需单位，批次是可选分组——一条查询、一次 {@code schema edit} 不建批次。
 * 批次存在的理由只有一个：<b>人在队列里裁决的是「梳理报事域状态字段」这件事，
 * 不是 40 个对象</b>。所以 {@code intent} 必填，写「维护图谱」的 60 条批次等于没有 intent。
 *
 * <p>{@code kind}：{@code graph} 或 {@code sql}，决定批准时怎么落地——
 * 图谱走一次 load/apply/save，SQL 走单连接单事务。
 *
 * <p>{@code recoverable} 只对 SQL 批次有意义：批次里有 DDL 时为 false。
 * MySQL 的 DDL 隐式提交，rollback 对它无效，标成可回滚就是骗人。
 *
 * <p>{@code status}：draft（未提交，不落地）/ pending / applied / partial / rejected / failed。
 */
public record ApprovalBatchRow(
        long id,
        String alias,
        String kind,
        String intent,
        String status,
        boolean recoverable,
        long createdAt,
        Long submittedAt,
        Long decidedAt,
        String decidedBy,
        String reason) {

    public static final String KIND_GRAPH = "graph";
    public static final String KIND_SQL = "sql";

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_APPLIED = "applied";
    public static final String STATUS_PARTIAL = "partial";
    public static final String STATUS_REJECTED = "rejected";
    public static final String STATUS_FAILED = "failed";

    /**
     * 整批被同一对象的新提交取代。
     *
     * <p>不复用 {@code rejected}：没有人拒绝过它，在审批记录里显示「已拒绝」会让人以为
     * 自己否过一批其实没见过的东西。
     */
    public static final String STATUS_SUPERSEDED = "superseded";
}
