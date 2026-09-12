package com.sqlcli.task;

import com.sqlcli.approval.ApprovalGate;

/**
 * 别名开了对应审批开关时，阻塞等人在 Web UI 的评审页放行。
 * 写操作看「更新审批」，其余（SELECT/SHOW/EXPLAIN…）看「查询审批」。
 *
 * <p>{@code dryRun} 请求不进这个 Stage 的阻塞——dry-run 本来就是「不执行、只想看预检」，
 * 强迫它等审批没有意义。
 */
final class ApprovalStage implements SqlTaskStage {

    private final ApprovalGate approvalGate;

    ApprovalStage(ApprovalGate approvalGate) {
        this.approvalGate = approvalGate;
    }

    @Override
    public String name() {
        return "approval";
    }

    @Override
    public void before(SqlTaskContext ctx) throws SqlTaskRejected {
        if (ctx.request.dryRun()) {
            return;
        }
        ApprovalGate.Kind kind = SqlTaskUtil.isWriteOperation(ctx.sqlType)
                ? ApprovalGate.Kind.UPDATE
                : ApprovalGate.Kind.QUERY;
        if (!ApprovalGate.isEnabled(ctx.config, kind)) {
            return;
        }
        // 审批等待期间还没有 Statement，可中止的是「等」本身：注册等待线程的 interrupt，
        // ApprovalGate 的 sleep 被打断后会把这条审批置 expired 并抛 ApprovalDenied → REJECTED。
        String cancelToken = ctx.request.cancelToken();
        Thread waiter = Thread.currentThread();
        SqlTaskCancelRegistry.register(cancelToken, waiter::interrupt);
        try {
            // 读操作没有 task_run（TaskAuditRecorder.begin 只给写操作建），传 null。
            approvalGate.awaitApproval(ctx.config.getAliasName(), kind, summarize(ctx), ctx.sql,
                    ctx.taskRunId > 0 ? ctx.taskRunId : null);
        } catch (ApprovalGate.ApprovalDeniedException e) {
            throw new SqlTaskRejected(e.getMessage());
        } finally {
            SqlTaskCancelRegistry.unregister(cancelToken);
            // 清掉可能残留的中断标志：这是 HTTP 线程池里的线程，带着标志回池会毒害下一个请求。
            // ponytail: cancel() 在 unregister 之后才跑 interrupt 的窗口极窄且只多一个无害标志，
            // 不为它加同步。
            Thread.interrupted();
        }
    }

    private String summarize(SqlTaskContext ctx) {
        String head = ctx.sqlType + " · ";
        if (ctx.precheck != null && ctx.precheck.estimatedRows() != null) {
            head += "约 " + ctx.precheck.estimatedRows() + " 行 · "
                    + (ctx.precheck.recoverySupported() ? "可恢复" : "不可恢复") + " · ";
        }
        return head + ApprovalGate.summarize(ctx.sql);
    }
}
