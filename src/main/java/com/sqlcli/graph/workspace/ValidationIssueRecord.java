package com.sqlcli.graph.workspace;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ValidationIssueRecord {
    private String id;
    private GraphObjectKind kind = GraphObjectKind.validation_issue;
    private int version = 1;
    private String sourceAlias;
    private ValidationSeverity severity;
    private String code;
    private String message;
    private String targetId;
    private String field;
    private String producer;       // 产生问题的模块，如 "validator", "import", "ui"
    private String runId;          // 验证运行 ID，用于区分不同批次
    private ValidationIssueStatus status = ValidationIssueStatus.open;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ValidationIssueRecord create(String sourceAlias, ValidationSeverity severity, String code,
            String message, String targetId, String field) {
        ValidationIssueRecord issue = new ValidationIssueRecord();
        LocalDateTime now = LocalDateTime.now();
        issue.setId(GraphIds.validationIssueId(sourceAlias));
        issue.setSourceAlias(sourceAlias);
        issue.setSeverity(severity);
        issue.setCode(code);
        issue.setMessage(message);
        issue.setTargetId(targetId);
        issue.setField(field);
        issue.setCreatedAt(now);
        issue.setUpdatedAt(now);
        return issue;
    }
}
