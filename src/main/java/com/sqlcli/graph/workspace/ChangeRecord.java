package com.sqlcli.graph.workspace;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChangeRecord {
    private String id;
    private GraphObjectKind kind = GraphObjectKind.change;
    private int version = 1;
    private String sourceAlias;
    private GraphActor actor;
    private ChangeOperation operation;
    private String targetId;
    private String beforeHash;
    private String afterHash;
    private String reason;
    private LocalDateTime createdAt;

    public static ChangeRecord create(String sourceAlias, ChangeOperation operation, String targetId, GraphActor actor) {
        ChangeRecord record = new ChangeRecord();
        record.setId(GraphIds.changeId(sourceAlias));
        record.setSourceAlias(sourceAlias);
        record.setOperation(operation);
        record.setTargetId(targetId);
        record.setActor(actor);
        record.setCreatedAt(LocalDateTime.now());
        return record;
    }
}
