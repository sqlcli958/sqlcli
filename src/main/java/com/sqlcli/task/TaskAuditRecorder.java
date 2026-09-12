package com.sqlcli.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcli.runstate.RunStateStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全部落在 try/finally 里，成功、拒绝、失败都记——链的顺序保证不了这一点，
 * 只有 finally 能保证。
 */
final class TaskAuditRecorder {

    private final RunStateStore runState;
    private final ObjectMapper mapper = new ObjectMapper();

    TaskAuditRecorder(RunStateStore runState) {
        this.runState = runState;
    }

    /** 写操作才建 task_run；读操作只落 sql_execution，没有 taskId 可查。 */
    void begin(SqlTaskContext ctx) {
        if (!SqlTaskUtil.isWriteOperation(ctx.sqlType)) {
            return;
        }
        String actor = switch (ctx.request.origin()) {
            case cli -> "cli";
            case ui_rerun, ui_workbench -> "web";
        };
        ctx.taskRunId = runState.createTaskRun(ctx.config.getAliasName(), "sql_write", actor, sha256(ctx.request.sql()));
        event(ctx, "submitted");
    }

    /**
     * precheck 阶段附带 {@link Precheck} 的 JSON、executed 附带执行结果——审批详情页
     * 要展示预估影响行数、恢复能力、实际影响行数，全靠这两个 payload，
     * 不然算出来的东西过了这个方法就没了。
     */
    void event(SqlTaskContext ctx, String stageName) {
        if (ctx.taskRunId <= 0) return;
        runState.recordTaskEvent(ctx.taskRunId, stageName, payloadFor(ctx, stageName));
    }

    /**
     * 拒绝 / 失败也要在时间线上留一格并带上原因。没有它，{@code task status} 只看得到
     * 「审批放行 → 方言处理 → 状态 rejected」，为什么被拒得去翻 sql_execution。
     */
    void terminal(SqlTaskContext ctx, String eventType, String reason) {
        if (ctx.taskRunId <= 0) return;
        runState.recordTaskEvent(ctx.taskRunId, eventType, toJson(Map.of("reason", reason == null ? "" : reason)));
    }

    private String payloadFor(SqlTaskContext ctx, String stageName) {
        return switch (stageName) {
            case "precheck" -> ctx.precheck == null ? null : toJson(ctx.precheck);
            case "executed" -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("affectedRows", ctx.affectedRows);
                payload.put("recoveryId", ctx.recoveryId > 0 ? ctx.recoveryId : null);
                payload.put("truncated", ctx.resultTruncated);
                yield toJson(payload);
            }
            default -> null;
        };
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    /** 终态记录：sql_execution 永远写一行；task_run（如果有）置终态。 */
    void finish(SqlTaskContext ctx, SqlTaskResult result) {
        String status = switch (result.status()) {
            case SUCCEEDED -> "success";
            case REJECTED -> "rejected";
            case FAILED -> "failed";
        };
        long executionId = runState.recordExecution(
                ctx.config.getAliasName(), ctx.sqlType, ctx.request.sql(), status,
                result.errorSummary(), result.affectedRows() == null ? null : result.affectedRows().longValue(),
                ctx.elapsedMs(), ctx.startedAtMillis,
                originSource(ctx.request.origin()), ctx.request.rerunOf(), ctx.config.getDefaultSchema());
        if (result.recoveryId() != null) {
            runState.attachRecoveryArtifact(result.recoveryId(), executionId);
        }
        if (ctx.taskRunId > 0) {
            runState.finishTaskRun(ctx.taskRunId, status);
        }
    }

    private String originSource(SqlTaskRequest.Origin origin) {
        return switch (origin) {
            case cli -> "normal";
            case ui_rerun -> "ui_rerun";
            case ui_workbench -> "ui_workbench";
        };
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }
}
