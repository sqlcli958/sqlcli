package com.sqlcli.graph.workspace;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;


import java.util.Map;

/**
 * 表的列。<b>本类刻意不继承 {@link BaseGraphObject}</b>，因此没有 {@link GraphStatus}。
 *
 * <h2>字段语义的闸门是 {@link #verified}，不是候选态（2026-08-27 决定）</h2>
 * 关系、术语、血缘、metric 的 {@code candidate} 表达的是「这个对象存不存在」——二值，
 * 有天然的「写进去了但还不算数」态。而 {@link #businessName} / {@link #semanticType} /
 * {@link #valueHints} 是标量值，「不生效的值」这回事不存在：要做到候选，就得为每个语义
 * 字段再存一份 pending 值，模型翻倍去换一个没人要求的能力。
 *
 * <p>所以闸门用本类早就有的 {@link #verified} / {@link #confidence} 两个字段——
 * 它们正是 {@code BaseGraphObject} 里对这件事唯一有用的两个。规则只有两条：
 * <ul>
 *   <li><b>Agent 写入不自封已确认</b>：CLI {@code schema edit} 的 actor 是
 *       {@link GraphActor#agent}，写完把 verified/confidence 清成 null。值立刻可读
 *       （检索、结果列头都用得上），但标着未确认。</li>
 *   <li><b>人在 UI 里改过即确认</b>：{@code WorkspaceMutationService.applyColumnPatch}
 *       的 actor 恒为 {@link GraphActor#human}，写完置 verified=true。</li>
 * </ul>
 *
 * <p>闸门必须有人读，否则和没有一样（这个字段此前写了三年没有一个消费方）。现有两处：
 * {@code schema describe} 给未确认的语义标 {@code [待确认]}，
 * {@code GET /api/workspace/completeness} 的 {@code backlog.unverifiedColumnSemantics}
 * 进工作台完整性看板。
 *
 * <p>机器测出来的值域不直接盖 {@code valueHints}：人写的值域带含义（{@code 0=待付款}），
 * 剖析只测得出值本身，直接覆盖等于用不知道含义的版本抹掉已有知识。所以
 * {@code schema value-domain} 把三源交叉的结论提成一条条目，走审批管线写回，
 * 由人决定要不要给新值补含义。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ColumnWorkspaceNode {
    private String name;
    private ColumnDataType dataType;
    private boolean nullable = true;
    private String defaultValue;
    private Integer ordinal;
    private boolean primaryKey;
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean unique;
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean indexed;
    /** 数据库列注释的镜像，只由导入写（{@link com.sqlcli.graph.workspace.WorkspaceMetadataExtractor}
     * 等 provider）。人和 Agent 不写这个字段——注释错了，正确的修法是去库里改，然后重新导入，
     * 而不是在图谱里悄悄改一份跟库不一致的文本。业务解读写 {@link #description}。 */
    private String comment;
    /** 业务描述，人和 Agent 维护，导入不覆盖（见 {@link GraphWorkspaceMerger} 类头字段分类）。 */
    private String description;
    private String businessName;
    private SemanticType semanticType;
    private ColumnValueHints valueHints;
    private Double confidence;
    private Boolean verified;
    /** 人工搜索权重，null = 1.0（默认不落盘，旧图谱文件天然兼容）。 */
    private Double boost;
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, Object> attributes = new java.util.HashMap<>();

    // ---- attributes 里约定的 key 名（函数依赖 / 权威来源标注）----
    // 沿用 RelationWorkspaceEdge 的做法：不建新字段，落在 attributes 里，旧图谱 YAML/JSONL
    // 文件原样读进来不受影响。
    //
    // 这是人工/Agent 标注"这个列是那个列的冗余副本"，不是自动发现函数依赖——从数据本身
    // 推导函数依赖（哪些列组合唯一决定另一列）属于外延侧的数据剖析（第三层，未排期），
    // 这里只承载标注结果的落点，不做推导。也不做规范化建议："这张表不满足 3NF，
    // 建议拆分"是设计期的事，不是这个产品的定位，见
    // docs/graph-model-vs-er-model.zh-CN.md §4.2 的边界说明。
    //

    /** 权威来源列的 id（{@code column:alias:schema.table.column}），值存的是目标列而不是
     * 布尔位：查权威值时要能直接跳过去，光知道"我是冗余的"没用。 */
    public static final String ATTR_REDUNDANT_OF = "redundantOf";

    /** 本列的权威来源列 id；不是冗余副本时返回 null。 */
    public String getRedundantOf() {
        Object value = attributes.get(ATTR_REDUNDANT_OF);
        return value == null ? null : value.toString();
    }

    /**
     * 有没有人/Agent 维护的语义内容（数据库注释、类型、可空性这些结构事实不算——
     * 它们由导入维护，没有"确认"这回事）。
     */
    public boolean hasSemantics() {
        return notBlank(description) || notBlank(businessName) || semanticType != null
                || (valueHints != null && valueHints.hasData()) || getRedundantOf() != null;
    }

    /**
     * 有语义内容、但没人确认过。这是闸门唯一的读法，{@code schema describe} 的
     * {@code [待确认]} 和完整性看板的待确认数共用它——判据只有一份，两处不会漂。
     */
    public boolean hasUnconfirmedSemantics() {
        return hasSemantics() && !Boolean.TRUE.equals(verified);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    public static ColumnWorkspaceNode create(String columnName) {
        ColumnWorkspaceNode node = new ColumnWorkspaceNode();
        node.setName(columnName);
        node.setDataType(new ColumnDataType());
        return node;
    }

    public String computeId(String sourceAlias, String schema, String table) {
        return GraphIds.columnId(sourceAlias, schema, table, name);
    }

    public String computeQualifiedName(String schema, String table) {
        return schema + "." + table + "." + name;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isDefaultConfidence() {
        return confidence == null || Math.abs(confidence - 0.7) < 0.001;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isDefaultVerified() {
        return !Boolean.TRUE.equals(verified);
    }

    public void touch(GraphActor actor) {
        // no-op for inline columns, table touch covers this
    }
}
