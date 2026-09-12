package com.sqlcli.task;

import java.util.List;

/**
 * 一次 SQL 任务的结果。渲染成 csv/json/table 是调用方的事——这里只有结构化数据。
 *
 * <p>三态而不是异常：拒绝（readonly / 多语句 / 审批被拒 / policy 不允许）和失败
 * （连不上库、SQL 语法错）都以返回值表达，调用方不用各写一套 try/catch。
 * 编程错误（NPE 之类）仍然照常抛，那是 bug 不是结果。
 */
public final class SqlTaskResult {

    public enum Status { SUCCEEDED, REJECTED, FAILED }

    /** @param jdbcTypeName Yearning 结果没有列类型，此时为 null */
    public record Column(String name, String jdbcTypeName) {
    }

    private final Status status;
    private final String sqlType;
    private final String executedSql;
    private final String taskId;
    private final List<Column> columns;
    private final List<List<Object>> rows;
    private final boolean truncated;
    private final long elapsedMs;
    private final Integer affectedRows;
    private final Long recoveryId;
    private final Precheck precheck;
    private final String errorSummary;
    private final String rootCause;
    /** 不改变状态但要让调用方看见的话：advisory 级规则违规、规则检查为什么没跑。 */
    private final List<String> notices;

    private SqlTaskResult(Builder b) {
        this.status = b.status;
        this.sqlType = b.sqlType;
        this.executedSql = b.executedSql;
        this.taskId = b.taskId;
        this.columns = b.columns;
        this.rows = b.rows;
        this.truncated = b.truncated;
        this.elapsedMs = b.elapsedMs;
        this.affectedRows = b.affectedRows;
        this.recoveryId = b.recoveryId;
        this.precheck = b.precheck;
        this.errorSummary = b.errorSummary;
        this.rootCause = b.rootCause;
        this.notices = b.notices;
    }

    public Status status() { return status; }
    public String sqlType() { return sqlType; }
    public String executedSql() { return executedSql; }
    public String taskId() { return taskId; }
    public List<Column> columns() { return columns; }
    public List<List<Object>> rows() { return rows; }
    public boolean truncated() { return truncated; }
    public long elapsedMs() { return elapsedMs; }
    public Integer affectedRows() { return affectedRows; }
    /** recovery_artifact 的 id；null 表示这次写没有可回滚的脚本。 */
    public Long recoveryId() { return recoveryId; }
    public Precheck precheck() { return precheck; }
    public String errorSummary() { return errorSummary; }
    public String rootCause() { return rootCause; }
    public List<String> notices() { return notices; }

    public List<String> columnNames() {
        return columns.stream().map(Column::name).toList();
    }

    static Builder builder() {
        return new Builder();
    }

    static final class Builder {
        private Status status;
        private String sqlType;
        private String executedSql;
        private String taskId;
        private List<Column> columns = List.of();
        private List<List<Object>> rows = List.of();
        private boolean truncated;
        private long elapsedMs;
        private Integer affectedRows;
        private Long recoveryId;
        private Precheck precheck;
        private String errorSummary;
        private String rootCause;
        private List<String> notices = List.of();

        Builder notices(List<String> v) { this.notices = List.copyOf(v); return this; }
        Builder sqlType(String v) { this.sqlType = v; return this; }
        Builder executedSql(String v) { this.executedSql = v; return this; }
        Builder taskId(String v) { this.taskId = v; return this; }
        Builder columns(List<Column> v) { this.columns = v; return this; }
        Builder rows(List<List<Object>> v) { this.rows = v; return this; }
        Builder truncated(boolean v) { this.truncated = v; return this; }
        Builder elapsedMs(long v) { this.elapsedMs = v; return this; }
        Builder affectedRows(Integer v) { this.affectedRows = v; return this; }
        Builder recoveryId(Long v) { this.recoveryId = v; return this; }
        Builder precheck(Precheck v) { this.precheck = v; return this; }

        SqlTaskResult succeeded() {
            this.status = Status.SUCCEEDED;
            return new SqlTaskResult(this);
        }

        SqlTaskResult rejected(String reason) {
            this.status = Status.REJECTED;
            this.errorSummary = reason;
            return new SqlTaskResult(this);
        }

        SqlTaskResult failed(String summary, String rootCause) {
            this.status = Status.FAILED;
            this.errorSummary = summary;
            this.rootCause = rootCause;
            return new SqlTaskResult(this);
        }
    }
}
