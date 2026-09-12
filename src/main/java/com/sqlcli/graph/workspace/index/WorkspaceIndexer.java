package com.sqlcli.graph.workspace.index;

import com.sqlcli.graph.workspace.ColumnWorkspaceNode;
import com.sqlcli.graph.workspace.GraphStatus;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.MetricRecord;
import com.sqlcli.graph.workspace.TableWorkspaceNode;

import java.time.Instant;

public class WorkspaceIndexer {

    /**
     * 重建搜索索引（兼容旧调用，不设置 sourceRevision）。
     */
    public WorkspaceIndexSnapshot rebuild(GraphWorkspace workspace) {
        return rebuild(workspace, workspace.getManifest().getRevision());
    }

    /**
     * 重建搜索索引，并设置 sourceRevision 到 manifest 中。
     *
     * @param workspace         工作区
     * @param workspaceRevision 当前 workspace 的 revision
     * @return 索引快照
     */
    public WorkspaceIndexSnapshot rebuild(GraphWorkspace workspace, long workspaceRevision) {
        WorkspaceIndexSnapshot snapshot = new WorkspaceIndexSnapshot();
        snapshot.setAlias(workspace.getManifest().getAlias());
        snapshot.setBuiltAt(Instant.now());
        snapshot.setSourceRevision(workspaceRevision);

        String alias = workspace.getManifest().getAlias();
        for (TableWorkspaceNode table : workspace.getTables().values()) {
            WorkspaceIndexDocument tableDoc = WorkspaceIndexDocument.tableDoc(
                    table.getId(), table.getSchema(), table.getName(), table.getComment(), table.getDescription());
            tableDoc.setBusinessName(table.getBusinessName());
            tableDoc.setTags(table.getTags());
            tableDoc.setCandidate(table.getStatus() == GraphStatus.candidate);
            tableDoc.setBoost(table.getBoost());
            tableDoc.setSystem(table.isSystem());
            snapshot.getDocuments().add(tableDoc);
            for (ColumnWorkspaceNode column : table.getColumns()) {
                String columnId = column.computeId(alias, table.getSchema(), table.getName());
                WorkspaceIndexDocument columnDoc = WorkspaceIndexDocument.columnDoc(
                        columnId, table.getSchema(), table.getName(), column.getName(),
                        column.getComment(), column.getDescription());
                columnDoc.setBusinessName(column.getBusinessName());
                columnDoc.setSemanticType(column.getSemanticType() == null
                        ? null : column.getSemanticType().name());
                columnDoc.setRedundantOf(column.getRedundantOf());
                // 内联列没有自己的 status，跟随所属表
                columnDoc.setCandidate(tableDoc.isCandidate());
                columnDoc.setBoost(column.getBoost());
                columnDoc.setSystem(table.isSystem());
                snapshot.getDocuments().add(columnDoc);
            }
        }
        workspace.getTerms().values().forEach(term -> {
            WorkspaceIndexDocument doc = WorkspaceIndexDocument.namedDoc(
                    term.getId(), "term", term.getName(), term.getDisplayName(), term.getDescription());
            doc.setAliases(term.getAliases());
            doc.setNegativeAliases(term.getNegativeAliases());
            doc.setCandidate(term.getStatus() == GraphStatus.candidate);
            snapshot.getDocuments().add(doc);
        });
        // metric 按同一套模式索引：name/businessName/aliases 命中是它有没有用的前提
        workspace.getMetrics().values().forEach(metric -> {
            WorkspaceIndexDocument doc = WorkspaceIndexDocument.namedDoc(
                    metric.getId(), "metric", metric.getName(), metric.getBusinessName(), null);
            doc.setBusinessName(metric.getBusinessName());
            doc.setAliases(metric.getAliases());
            doc.setCandidate(metric.getStatus() == GraphStatus.candidate);
            snapshot.getDocuments().add(doc);
        });
        return snapshot;
    }
}
