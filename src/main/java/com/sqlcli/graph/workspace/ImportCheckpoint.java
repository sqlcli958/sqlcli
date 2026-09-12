package com.sqlcli.graph.workspace;

import lombok.Data;

@Data
public class ImportCheckpoint {
    private ImportPhase phase = ImportPhase.discover_schemas;
    private String schemaCursor;
    private String tableCursor;
    private String lastCompletedTaskId;
    private int completedTaskCount;
    private int failedTaskCount;
    private int batchNo;
}
