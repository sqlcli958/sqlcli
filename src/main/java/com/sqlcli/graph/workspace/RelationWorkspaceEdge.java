package com.sqlcli.graph.workspace;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.EqualsAndHashCode;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
// 旧图谱 YAML/JSONL 里还留着已删字段（如 weight）的 key，读的时候要能容忍，不然老库直接加载失败。
@JsonIgnoreProperties(ignoreUnknown = true)
public class RelationWorkspaceEdge extends BaseGraphObject {
    private String sourceAlias;
    private RelationType type;
    private String from;
    private String to;
    private RelationDirection direction = RelationDirection.forward;
    private RelationCardinality cardinality = RelationCardinality.unknown;
    private String joinExpression;
    private List<RelationEvidence> evidence = new ArrayList<>();

    // ---- attributes 里约定的 key 名（复合外键分组 / 参与约束可选性）----
    // 不建新字段：旧图谱 YAML/JSONL 反序列化后要能原样读进来，attributes 是唯一
    // 不破坏兼容性的落点。key 名收敛成常量，避免各处调用方各拼一遍字符串、
    // 拼出 "fkgroup" 和 "fkGroup" 这种不一致的事故。

    /** 复合外键分组标识：同一约束的多列边共享这个值（见 WorkspaceMetadataExtractor）。 */
    public static final String ATTR_FK_GROUP = "fkGroup";
    /** 组内列序，对应 JDBC 的 KEY_SEQ，用于按顺序拼 joinExpression。 */
    public static final String ATTR_FK_KEY_SEQ = "fkKeySeq";
    /** from 端（外键列所在行）参与本关系是否可选。 */
    public static final String ATTR_FROM_OPTIONAL = "fromOptional";
    /** to 端（被引用列所在行）参与本关系是否可选。 */
    public static final String ATTR_TO_OPTIONAL = "toOptional";
    /** optionality 的来源标记，两个取值都不是人确认的值：{@code inferred} 只看 nullable 声明，
     * {@code profiled} 看实测空值率（{@code schema value-domain} 顺手升级上来的，更准）。
     * 外键边本身仍然是 verified=true 的既成事实，optionality 是叠加在它上面的另一件事——
     * 不能因为外键关系本身已验证，就误把这个推断值也当成已确认。 */
    public static final String ATTR_OPTIONALITY_SOURCE = "optionalitySource";
    public static final String OPTIONALITY_SOURCE_INFERRED = "inferred";
    public static final String OPTIONALITY_SOURCE_PROFILED = "profiled";

    /** 复合外键分组标识，非分组边（或非 FK 关系）返回 null。 */
    public String getFkGroup() {
        Object value = getAttributes().get(ATTR_FK_GROUP);
        return value == null ? null : value.toString();
    }

    /** 组内列序，未设置时返回 null。 */
    public Integer getFkKeySeq() {
        Object value = getAttributes().get(ATTR_FK_KEY_SEQ);
        return value instanceof Number number ? number.intValue() : null;
    }

    public Boolean getFromOptional() {
        Object value = getAttributes().get(ATTR_FROM_OPTIONAL);
        return value instanceof Boolean bool ? bool : null;
    }

    public Boolean getToOptional() {
        Object value = getAttributes().get(ATTR_TO_OPTIONAL);
        return value instanceof Boolean bool ? bool : null;
    }

    /** true 表示两端 optionality 是导入时推断出来的初值，还没人确认过。 */
    public boolean isOptionalityInferred() {
        return OPTIONALITY_SOURCE_INFERRED.equals(getAttributes().get(ATTR_OPTIONALITY_SOURCE));
    }

    public static RelationWorkspaceEdge create(String sourceAlias, RelationType type, String from, String to,
            GraphActor actor) {
        RelationWorkspaceEdge edge = new RelationWorkspaceEdge();
        edge.init(GraphObjectKind.relation, GraphIds.relationId(sourceAlias, type, from, to), actor);
        edge.setSourceAlias(sourceAlias);
        edge.setType(type);
        edge.setFrom(from);
        edge.setTo(to);
        edge.setStatus(GraphStatus.forActor(actor));
        // 推断类关系没有默认置信度：留 null，由调用方显式设置，否则校验会拒绝。
        edge.setConfidence(type == null ? null : type.defaultConfidence());
        edge.setVerified(false);
        return edge;
    }
}
