package com.sqlcli.graph.workspace;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RelationEvidence {
    private String sourceType;
    private String sourceRef;
    private LocalDateTime observedAt;

    public static RelationEvidence of(String sourceType, String sourceRef) {
        RelationEvidence evidence = new RelationEvidence();
        evidence.setSourceType(sourceType);
        evidence.setSourceRef(sourceRef);
        evidence.setObservedAt(LocalDateTime.now());
        return evidence;
    }
}
