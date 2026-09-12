package com.sqlcli.graph.workspace;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkspaceManifest {
    public static final int CURRENT_MODEL_VERSION = 5;
    public static final int CURRENT_STORAGE_VERSION = 5;

    private String id;
    private GraphObjectKind kind = GraphObjectKind.manifest;
    private String alias;
    private String name;
    private int modelVersion = CURRENT_MODEL_VERSION;
    private int storageVersion = CURRENT_STORAGE_VERSION;
    private long revision = 1;
    private String defaultSourceAlias;
    private WorkspaceStats stats = new WorkspaceStats();
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastValidationAt;
    private LocalDateTime lastIndexedAt;

    public static WorkspaceManifest create(String alias) {
        WorkspaceManifest manifest = new WorkspaceManifest();
        LocalDateTime now = LocalDateTime.now();
        manifest.setId("workspace:" + alias);
        manifest.setAlias(alias);
        manifest.setName(alias);
        manifest.setDefaultSourceAlias(alias);
        manifest.setCreatedAt(now);
        manifest.setUpdatedAt(now);
        return manifest;
    }

    public void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    public void incrementRevision() {
        this.revision++;
    }
}
