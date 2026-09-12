package com.sqlcli.graph.workspace.index;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索索引文档，每个表或列一条记录。
 * 只保留搜索实际需要的字段，不做冗余存储。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkspaceIndexDocument {
    private String id;
    private String type;
    private String schema;
    private String table;
    private String column;
    private String name;
    private String qualifiedName;
    private String displayName;
    private String businessName;
    /** 数据库注释镜像，只由导入写。 */
    private String comment;
    /** 人/Agent 写的业务描述，索引和检索都要能命中，与 comment 分字段展示。 */
    private String description;
    private String semanticType;
    /** 权威来源列 id，非冗余列为 null；旧索引没有该字段即为 null。 */
    private String redundantOf;
    /** 候选对象（agent 写入等人发布）。默认 false，旧索引没有该字段即为 false。 */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean candidate;
    /** 人工搜索权重，null = 1.0；旧索引没有该字段即为默认。 */
    private Double boost;
    /** 系统表（及其列），搜索时降权。 */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean system;
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> aliases = new ArrayList<>();
    /** 术语专属：查询恰好等于其中一条时，这份文档整个不参与匹配——挡跨域撞词。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> negativeAliases = new ArrayList<>();
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> tags = new ArrayList<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getSchema() { return schema; }
    public void setSchema(String schema) { this.schema = schema; }

    public String getTable() { return table; }
    public void setTable(String table) { this.table = table; }

    public String getColumn() { return column; }
    public void setColumn(String column) { this.column = column; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getQualifiedName() { return qualifiedName; }
    public void setQualifiedName(String qualifiedName) { this.qualifiedName = qualifiedName; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getBusinessName() { return businessName; }
    public void setBusinessName(String businessName) { this.businessName = businessName; }
    public String getSemanticType() { return semanticType; }
    public void setSemanticType(String semanticType) { this.semanticType = semanticType; }
    public String getRedundantOf() { return redundantOf; }
    public void setRedundantOf(String redundantOf) { this.redundantOf = redundantOf; }
    public boolean isCandidate() { return candidate; }
    public void setCandidate(boolean candidate) { this.candidate = candidate; }
    public Double getBoost() { return boost; }
    public void setBoost(Double boost) { this.boost = boost; }
    public boolean isSystem() { return system; }
    public void setSystem(boolean system) { this.system = system; }
    public List<String> getAliases() { return aliases; }
    public void setAliases(List<String> aliases) { this.aliases = aliases; }
    public List<String> getNegativeAliases() { return negativeAliases; }
    public void setNegativeAliases(List<String> negativeAliases) { this.negativeAliases = negativeAliases; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public static WorkspaceIndexDocument tableDoc(String id, String schema, String table, String comment,
            String description) {
        WorkspaceIndexDocument doc = new WorkspaceIndexDocument();
        doc.id = id;
        doc.type = "table";
        doc.schema = schema;
        doc.table = table;
        doc.name = table;
        doc.qualifiedName = schema + "." + table;
        doc.comment = comment;
        doc.description = description;
        return doc;
    }

    public static WorkspaceIndexDocument columnDoc(String id, String schema, String table, String column,
            String comment, String description) {
        WorkspaceIndexDocument doc = new WorkspaceIndexDocument();
        doc.id = id;
        doc.type = "column";
        doc.schema = schema;
        doc.table = table;
        doc.column = column;
        doc.name = column;
        doc.qualifiedName = schema + "." + table + "." + column;
        doc.comment = comment;
        doc.description = description;
        return doc;
    }

    /** term/metric 用：这里传入的 description 是它们唯一的业务描述字段，落在 doc.description
     * 而不是 doc.comment——comment 专指表/列的数据库注释镜像，term/metric 没有这个概念。 */
    public static WorkspaceIndexDocument namedDoc(String id, String type, String name, String displayName,
            String description) {
        WorkspaceIndexDocument doc = new WorkspaceIndexDocument();
        doc.id = id;
        doc.type = type;
        doc.name = name;
        doc.qualifiedName = name;
        doc.displayName = displayName;
        doc.description = description;
        return doc;
    }
}
