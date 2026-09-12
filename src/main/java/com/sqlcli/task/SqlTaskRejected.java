package com.sqlcli.task;

/**
 * 受检异常，让「拒绝执行」在编译期就和「执行失败」分开——调用点漏接不了。
 * driver 捕获它，转成 {@link SqlTaskResult.Status#REJECTED}，仍然走审计。
 */
final class SqlTaskRejected extends Exception {
    SqlTaskRejected(String message) {
        super(message);
    }
}
