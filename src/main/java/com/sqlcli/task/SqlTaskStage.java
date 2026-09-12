package com.sqlcli.task;

/**
 * 一个能独立摘出来的横切关注点：类型检测、只读保护、审批、SM4 改写、方言预处理……
 *
 * <p>拆不开的东西（写操作的事务边界、policy 门控 + 执行路由）不做成 Stage，
 * 留在 {@link SqlBackend} 内部——责任链只适合表达能独立摘出来的关注点，
 * 硬拆会把一个 switch 变成两个必须同步维护的 switch。
 */
interface SqlTaskStage {

    /** 进 task_event 的 payload，也用于日志。 */
    String name();

    /** 检查或改写 {@link SqlTaskContext}。拒绝执行时抛 {@link SqlTaskRejected}。 */
    void before(SqlTaskContext ctx) throws SqlTaskRejected;

    /** 结果加工，只在执行成功后、按 before 的反序调用。默认不做任何事。 */
    default void after(SqlTaskContext ctx) {
    }
}
