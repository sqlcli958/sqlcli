package com.sqlcli.graph.ui.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sqlcli.graph.workspace.ColumnWorkspaceNode;
import com.sqlcli.graph.workspace.RelationWorkspaceEdge;
import com.sqlcli.graph.workspace.TableIndexMetadata;
import com.sqlcli.graph.workspace.ValidationIssueRecord;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TableDetailDto {
    private TableInfo table;
    private List<ColumnWorkspaceNode> columns;
    private List<RelationWorkspaceEdge> inEdges;
    private List<RelationWorkspaceEdge> outEdges;
    private List<ValidationIssueRecord> validationIssues;
    private List<ChangeInfo> recentChanges;
    /** 列名 -> 该列的直接上下游血缘（一跳）。只收有血缘的列，无血缘的列不出现在 map 里。 */
    private Map<String, ColumnLineage> lineage;

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TableInfo {
        private String id;
        private String schema;
        private String name;
        private String qualifiedName;
        private String businessName;
        private String comment;
        private String description;
        private String tableType;
        private List<String> tags;
        private List<String> primaryKey;
        /** 数据库索引，导入时从 JDBC metadata 抽取。前端靠它 + primaryKey 区分主键/唯一/普通。 */
        private List<TableIndexMetadata> indexes;
        private Long rowEstimate;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ChangeInfo {
        private String id;
        private String operation;
        private String targetId;
        private String actor;
        private String timestamp;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ColumnLineage {
        private List<LineageEndpoint> upstream = new ArrayList<>();
        private List<LineageEndpoint> downstream = new ArrayList<>();
    }

    /** 血缘对端：另一张表的一个列，加上产生它的表达式/来源（如果有）。 */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LineageEndpoint {
        private String schema;
        private String table;
        private String column;
        private String expression;
        private String through;
        private String lineageKind;
    }
}
