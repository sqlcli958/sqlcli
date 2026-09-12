package com.sqlcli.graph.workspace;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.EqualsAndHashCode;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = false)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TableWorkspaceNode extends BaseGraphObject {
    private String sourceAlias;
    private String schema;
    private String name;
    private String qualifiedName;
    private String businessName;
    /** 数据库表注释的镜像，只由导入写（{@link WorkspaceMetadataExtractor} 等 provider）。
     * 人和 Agent 不写这个字段——注释错了，正确的修法是去库里改，然后重新导入，
     * 而不是在图谱里悄悄改一份跟库不一致的文本。业务解读写 {@link #description}。 */
    private String comment;
    /** 业务描述，人和 Agent 维护，导入不覆盖（见 {@link GraphWorkspaceMerger} 类头字段分类）。 */
    private String description;
    /** 表粒度，说明「一行代表什么」（例如「一行一个订单商品」）。人工维护，导入不覆盖，
     * 与 {@link #description} 同组（见 {@link GraphWorkspaceMerger} 类头字段分类）。
     * 是扇形陷阱检查（{@code expand-metric} 展开指标时判断 join 是否放大行数）的输入，
     * 也是 policy 规则 {@code fact_table_grain_required} 的校验对象——事实表
     * （打了 {@code fact} 标签）必须声明粒度才能过评审。 */
    private String grain;
    private TableType tableType = TableType.unknown;
    private boolean system;
    private List<String> primaryKey = new ArrayList<>();
    private List<ColumnWorkspaceNode> columns = new ArrayList<>();
    private List<TableIndexMetadata> indexes = new ArrayList<>();

    private Long rowEstimate;
    private List<String> tags = new ArrayList<>();
    /** 人工搜索权重，null = 1.0（默认不落盘，旧图谱文件天然兼容）。 */
    private Double boost;

    public static TableWorkspaceNode create(String sourceAlias, String schema, String tableName, GraphActor actor) {
        TableWorkspaceNode node = new TableWorkspaceNode();
        node.init(GraphObjectKind.table, GraphIds.tableId(sourceAlias, schema, tableName), actor);
        node.setSourceAlias(sourceAlias);
        node.setSchema(schema);
        node.setName(tableName);
        node.setQualifiedName(schema + "." + tableName);
        node.setStatus(GraphStatus.discovered);
        node.setConfidence(0.8);
        node.setVerified(false);
        return node;
    }

    public ColumnWorkspaceNode findColumn(String columnName) {
        for (ColumnWorkspaceNode col : columns) {
            if (col.getName() != null && col.getName().equalsIgnoreCase(columnName)) {
                return col;
            }
        }
        return null;
    }
}
