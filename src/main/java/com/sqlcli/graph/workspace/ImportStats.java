package com.sqlcli.graph.workspace;

import lombok.Data;

@Data
public class ImportStats {
    private int totalTasks;
    private int completedTasks;
    private int failedTasks;
    private int totalTables;
    private int completedTables;
    private int failedTables;
    private boolean foreignKeysSkipped = false;
}
