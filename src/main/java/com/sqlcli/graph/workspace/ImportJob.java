package com.sqlcli.graph.workspace;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class ImportJob {
    private String jobId;
    private String alias;
    private String mode;
    private ImportJobStatus status = ImportJobStatus.pending;
    private String databaseType;
    private LocalDateTime startedAt;
    private LocalDateTime updatedAt;
    private LocalDateTime finishedAt;
    private ImportPhase currentPhase = ImportPhase.discover_schemas;
    private String message;
    private ImportStats stats = new ImportStats();
    private ImportCheckpoint checkpoint = new ImportCheckpoint();
    private ImportOptions options = new ImportOptions();
    private List<String> discoveredSchemas = new ArrayList<>();

    public static ImportJob create(String alias, String mode, String databaseType, ImportOptions options) {
        ImportJob job = new ImportJob();
        LocalDateTime now = LocalDateTime.now();
        job.setJobId("import:" + alias + ":" + now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
        job.setAlias(alias);
        job.setMode(mode);
        job.setDatabaseType(databaseType);
        job.setStartedAt(now);
        job.setUpdatedAt(now);
        job.setOptions(options);
        return job;
    }

    public void touch() {
        this.updatedAt = LocalDateTime.now();
    }
}
