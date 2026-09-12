package com.sqlcli.graph.policy;

import com.sqlcli.graph.workspace.GraphActor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RuleWaiver {
    private String id;
    private String sourceAlias;
    private String ruleSetId;
    private String ruleId;
    private String targetId;
    private String reason;
    private GraphActor createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private RuleWaiverStatus status = RuleWaiverStatus.active;
    private GraphActor revokedBy;
    private LocalDateTime revokedAt;
    private String revokeReason;
    private LocalDateTime updatedAt;

    public boolean isActiveAt(LocalDateTime now) {
        return status == RuleWaiverStatus.active && expiresAt != null && expiresAt.isAfter(now);
    }
}
