package com.sqlcli.graph.policy;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PolicyViolation {
    private String id;
    private String evaluationId;
    private String fingerprint;
    private String ruleId;
    private String targetId;
    private String field;
    private PolicySeverity severity;
    private PolicyEnforcement enforcement;
    private String message;
    private String remediation;
    private String sourceRef;
    private String changeType;
    private PolicyViolationStatus status = PolicyViolationStatus.open;
    private String waiverId;
    private LocalDateTime observedAt;
}
