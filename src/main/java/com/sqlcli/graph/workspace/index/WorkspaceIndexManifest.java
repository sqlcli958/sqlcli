package com.sqlcli.graph.workspace.index;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkspaceIndexManifest {
    private String alias;
    private int version = 3;
    private Instant builtAt;
    private int documentCount;
    private int tableCount;
    private int columnCount;
    private int termCount;
    private int maxShardBytes;
    private long sourceRevision;  // 构建时的 workspace revision
    private List<String> shards = new ArrayList<>();

    public String getAlias() { return alias; }
    public void setAlias(String alias) { this.alias = alias; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Instant getBuiltAt() { return builtAt; }
    public void setBuiltAt(Instant builtAt) { this.builtAt = builtAt; }
    public int getDocumentCount() { return documentCount; }
    public void setDocumentCount(int documentCount) { this.documentCount = documentCount; }
    public int getTableCount() { return tableCount; }
    public void setTableCount(int tableCount) { this.tableCount = tableCount; }
    public int getColumnCount() { return columnCount; }
    public void setColumnCount(int columnCount) { this.columnCount = columnCount; }
    public int getTermCount() { return termCount; }
    public void setTermCount(int termCount) { this.termCount = termCount; }
    public int getMaxShardBytes() { return maxShardBytes; }
    public void setMaxShardBytes(int maxShardBytes) { this.maxShardBytes = maxShardBytes; }
    public long getSourceRevision() { return sourceRevision; }
    public void setSourceRevision(long sourceRevision) { this.sourceRevision = sourceRevision; }
    public List<String> getShards() { return shards; }
    public void setShards(List<String> shards) { this.shards = shards; }
}
