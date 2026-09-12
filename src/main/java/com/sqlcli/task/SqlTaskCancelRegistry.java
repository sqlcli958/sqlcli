package com.sqlcli.task;

import java.util.concurrent.ConcurrentHashMap;

/**
 * cancelToken → 「怎么中止这次执行」的注册表。设计文档 §1.3 给中止留的那条缝，
 * 这里就是那个 {@code Map<token, Statement>}——只是值放宽成 {@link Runnable}：
 * JDBC 执行阶段注册的是 {@code stmt.cancel()}，审批等待阶段注册的是等待线程的
 * {@code interrupt()}，两个阶段先后接力，同一个 token 永远只挂着当前能中止的那件事。
 *
 * <p>token 由前端生成随机串随执行请求带上，不是 taskRunId——读操作没有 task_run，
 * 而 SELECT 恰恰是最需要中止的（跑飞的大查询）。
 *
 * <p>静态表：注册方（{@code SqlTaskModule} 的执行链）和中止方（HTTP 端点）在
 * 同一个进程里但不共享实例，一张进程级的表是它们唯一的会合点。
 */
public final class SqlTaskCancelRegistry {

    private static final ConcurrentHashMap<String, Runnable> ACTIONS = new ConcurrentHashMap<>();

    private SqlTaskCancelRegistry() {
    }

    static void register(String token, Runnable action) {
        if (token != null && !token.isBlank()) {
            ACTIONS.put(token, action);
        }
    }

    static void unregister(String token) {
        if (token != null) {
            ACTIONS.remove(token);
        }
    }

    /**
     * 按 token 中止。返回 false 表示没有正在执行的这次任务（已结束或 token 不对），
     * 调用方据此告诉用户「没什么可中止的」。
     */
    public static boolean cancel(String token) {
        Runnable action = token == null ? null : ACTIONS.remove(token);
        if (action == null) {
            return false;
        }
        action.run();
        return true;
    }
}
