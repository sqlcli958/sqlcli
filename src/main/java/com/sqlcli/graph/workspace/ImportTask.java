package com.sqlcli.graph.workspace;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ImportTask {
    private String taskId;
    private String jobId;
    private ImportTaskType type;
    private String schema;
    private String table;
    private ImportTaskStatus status = ImportTaskStatus.pending;
    private int attempt;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    public static ImportTask create(String jobId, ImportTaskType type, String schema, String table) {
        ImportTask task = new ImportTask();
        task.setJobId(jobId);
        task.setTaskId(jobId + ":" + type.name() + ":" + schema + "." + table);
        task.setType(type);
        task.setSchema(schema);
        task.setTable(table);
        return task;
    }
}
