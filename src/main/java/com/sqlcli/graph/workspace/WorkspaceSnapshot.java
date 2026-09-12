package com.sqlcli.graph.workspace;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class WorkspaceSnapshot {
    private WorkspaceManifest manifest;
    private DataSourceNode dataSource;
    private List<SchemaWorkspaceNode> schemas = new ArrayList<>();
    private List<TableWorkspaceNode> tables = new ArrayList<>();
    private List<TermWorkspaceNode> terms = new ArrayList<>();
    private List<LineageRecord> lineage = new ArrayList<>();
    private List<MetricRecord> metrics = new ArrayList<>();
    private List<RelationWorkspaceEdge> relations = new ArrayList<>();
    private List<ChangeRecord> changes = new ArrayList<>();
    private List<ValidationIssueRecord> validationIssues = new ArrayList<>();

    public static WorkspaceSnapshot fromWorkspace(GraphWorkspace workspace) {
        WorkspaceSnapshot snapshot = new WorkspaceSnapshot();
        snapshot.setManifest(workspace.getManifest());
        snapshot.setDataSource(workspace.getDataSource());
        snapshot.getSchemas().addAll(workspace.getSchemas().values());
        snapshot.getTables().addAll(workspace.getTables().values());
        snapshot.getTerms().addAll(workspace.getTerms().values());
        snapshot.getLineage().addAll(workspace.getLineage().values());
        snapshot.getMetrics().addAll(workspace.getMetrics().values());
        snapshot.getRelations().addAll(workspace.getRelations());
        snapshot.getChanges().addAll(workspace.getChanges());
        snapshot.getValidationIssues().addAll(workspace.getValidationIssues());
        return snapshot;
    }

    public GraphWorkspace toWorkspace() {
        GraphWorkspace workspace = new GraphWorkspace();
        workspace.setManifest(manifest);
        workspace.setDataSource(dataSource);
        for (SchemaWorkspaceNode node : schemas) {
            workspace.getSchemas().put(node.getId(), node);
        }
        for (TableWorkspaceNode node : tables) {
            workspace.getTables().put(node.getId(), node);
        }
        for (TermWorkspaceNode node : terms) {
            workspace.getTerms().put(node.getId(), node);
        }
        for (LineageRecord record : lineage) {
            workspace.getLineage().put(record.getId(), record);
        }
        for (MetricRecord record : metrics) {
            workspace.getMetrics().put(record.getId(), record);
        }
        workspace.getRelations().addAll(relations);
        workspace.getChanges().addAll(changes);
        workspace.getValidationIssues().addAll(validationIssues);
        return workspace;
    }
}
