package com.sqlcli.graph.workspace.index;

import com.fasterxml.jackson.annotation.JsonInclude;


import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 内存中的搜索索引快照。落盘时由 WorkspaceIndexStore 拆分为 manifest 和文档分片。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkspaceIndexSnapshot {
    private String alias;
    private int version = 3;
    private Instant builtAt;
    private long sourceRevision;
    private List<WorkspaceIndexDocument> documents = new ArrayList<>();

    public String getAlias() { return alias; }
    public void setAlias(String alias) { this.alias = alias; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public Instant getBuiltAt() { return builtAt; }
    public void setBuiltAt(Instant builtAt) { this.builtAt = builtAt; }

    public long getSourceRevision() { return sourceRevision; }
    public void setSourceRevision(long sourceRevision) { this.sourceRevision = sourceRevision; }

    public List<WorkspaceIndexDocument> getDocuments() { return documents; }
    public void setDocuments(List<WorkspaceIndexDocument> documents) { this.documents = documents; }

    public int tableCount() {
        return (int) documents.stream().filter(d -> "table".equals(d.getType())).count();
    }

    public int columnCount() {
        return (int) documents.stream().filter(d -> "column".equals(d.getType())).count();
    }

    public int termCount() {
        return (int) documents.stream().filter(d -> "term".equals(d.getType())).count();
    }
}
