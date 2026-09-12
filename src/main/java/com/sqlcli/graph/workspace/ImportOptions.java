package com.sqlcli.graph.workspace;

import lombok.Data;

@Data
public class ImportOptions {
    private String schemaFilter;
    private String tableFilter;
    private int batchSize = 20;
    private boolean merge;
    private boolean forceOverwrite;
}
