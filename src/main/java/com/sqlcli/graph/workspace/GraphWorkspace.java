package com.sqlcli.graph.workspace;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class GraphWorkspace {
    private WorkspaceManifest manifest;
    private DataSourceNode dataSource;
    private Map<String, SchemaWorkspaceNode> schemas = new LinkedHashMap<>();
    private Map<String, TableWorkspaceNode> tables = new LinkedHashMap<>();
    private Map<String, TermWorkspaceNode> terms = new LinkedHashMap<>();
    /** 字段级血缘，与 terms 平级的第三个集合；不进 relations（见 dev-checklist P3 决策）。 */
    private Map<String, LineageRecord> lineage = new LinkedHashMap<>();
    /** BI 语义层的指标定义，与 terms / lineage 平级的第四个集合；不进 relations（理由见 MetricRecord）。 */
    private Map<String, MetricRecord> metrics = new LinkedHashMap<>();
    private List<RelationWorkspaceEdge> relations = new ArrayList<>();
    private List<ChangeRecord> changes = new ArrayList<>();
    private List<ValidationIssueRecord> validationIssues = new ArrayList<>();

    public static GraphWorkspace create(String alias, String dbType) {
        GraphWorkspace workspace = new GraphWorkspace();
        workspace.setManifest(WorkspaceManifest.create(alias));
        workspace.setDataSource(DataSourceNode.create(alias, dbType, GraphActor.system));
        return workspace;
    }

    public TableWorkspaceNode getTableByQualifiedName(String fullName) {
        String normalized = fullName.toLowerCase();
        for (TableWorkspaceNode table : tables.values()) {
            if (table.getQualifiedName() != null && table.getQualifiedName().equalsIgnoreCase(normalized)) {
                return table;
            }
            if (table.getName() != null && table.getName().equalsIgnoreCase(fullName)) {
                return table;
            }
        }
        return null;
    }

    public ColumnWorkspaceNode findColumnById(String columnId) {
        if (columnId == null || !columnId.startsWith("column:")) {
            return null;
        }
        // Parse column:sap:SAP.JS_JSDZB.PK_JSDZB_H -> alias, schema.table.column
        String[] parts = columnId.split(":", 3);
        if (parts.length < 3 || manifest == null || !parts[1].equals(manifest.getAlias())) {
            return null;
        }
        String qualifiedName = parts[2]; // SAP.JS_JSDZB.PK_JSDZB_H
        int firstDot = qualifiedName.indexOf('.');
        if (firstDot < 0) {
            return null;
        }
        int lastDot = qualifiedName.lastIndexOf('.');
        if (lastDot <= firstDot) {
            return null;
        }
        String schemaAndTable = qualifiedName.substring(0, lastDot); // SAP.JS_JSDZB
        String columnName = qualifiedName.substring(lastDot + 1); // PK_JSDZB_H
        TableWorkspaceNode table = getTableByQualifiedName(schemaAndTable);
        if (table == null) {
            return null;
        }
        return table.findColumn(columnName);
    }

    /**
     * 把人写的列引用解析成列 id。接受 {@code column:alias:schema.table.column}（原样校验存在性）
     * 或 {@code schema.table.column}；解析不出来返回 null，由调用方决定报什么错。
     *
     * <p>CLI（{@code schema edit} / {@code add-metric} 的 {@code --dimensions} 等）和
     * Web UI 的 metric 表单共用这一份——两边各写一份迟早在"表名带点号怎么切"这类
     * 细节上分叉。
     */
    public String resolveColumnId(String sourceAlias, String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        if (ref.startsWith("column:")) {
            return findColumnById(ref) != null ? ref : null;
        }
        int lastDot = ref.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == ref.length() - 1) {
            return null;
        }
        TableWorkspaceNode table = getTableByQualifiedName(ref.substring(0, lastDot));
        if (table == null) {
            return null;
        }
        ColumnWorkspaceNode column = table.findColumn(ref.substring(lastDot + 1));
        return column == null ? null : column.computeId(sourceAlias, table.getSchema(), table.getName());
    }

    public GraphObjectKind resolveNodeKind(String nodeId) {
        if (nodeId == null) {
            return null;
        }
        if (schemas.containsKey(nodeId)) {
            return GraphObjectKind.schema;
        }
        if (tables.containsKey(nodeId)) {
            return GraphObjectKind.table;
        }
        if (terms.containsKey(nodeId)) {
            return GraphObjectKind.term;
        }
        if (metrics.containsKey(nodeId)) {
            return GraphObjectKind.metric;
        }
        return findColumnById(nodeId) == null ? null : GraphObjectKind.column;
    }

    public int getColumnCount() {
        int count = 0;
        for (TableWorkspaceNode table : tables.values()) {
            count += table.getColumns().size();
        }
        return count;
    }
}
