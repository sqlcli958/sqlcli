package com.sqlcli.graph.workspace;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 关系类型。
 *
 * 只有三种，按「谁维护」划分：
 * <ul>
 *   <li>{@link #foreign_key} —— 数据库元数据里声明的外键，导入时重建，人和 Agent 不能创建或编辑</li>
 *   <li>{@link #join_observed} —— 人和 Agent 维护的表间关联，导入不会覆盖</li>
 *   <li>{@link #term_mapping} —— 业务术语到表/字段的映射</li>
 * </ul>
 *
 * 历史上还有 contains / lineage_to / same_as / depends_on 等类型，
 * 没有任何生产代码创建过它们，图谱里也没有实例，已删除。
 */
public enum RelationType {
    /** 数据库声明的外键（旧名 foreign_key_declared） */
    foreign_key,
    /** 人 / Agent 维护的表间关联（旧名 foreign_key_inferred 也归入这里） */
    join_observed,
    /** 业务术语 → 表 / 字段 */
    term_mapping;

    private static final Map<RelationType, RelationEndpointRule> ENDPOINT_RULES = new EnumMap<>(RelationType.class);

    static {
        Set<GraphObjectKind> COLUMN = Set.of(GraphObjectKind.column);
        Set<GraphObjectKind> TERM = Set.of(GraphObjectKind.term);
        Set<GraphObjectKind> COLUMN_OR_TABLE = Set.of(GraphObjectKind.column, GraphObjectKind.table);

        ENDPOINT_RULES.put(foreign_key,
                new RelationEndpointRule(foreign_key, COLUMN, COLUMN, 1.0));
        ENDPOINT_RULES.put(join_observed,
                new RelationEndpointRule(join_observed, COLUMN, COLUMN, 0.5));
        ENDPOINT_RULES.put(term_mapping,
                new RelationEndpointRule(term_mapping, TERM, COLUMN_OR_TABLE, 0.5));
    }

    /**
     * 兼容改名前落盘的图谱。
     *
     * 旧图谱里存的是 foreign_key_declared / foreign_key_inferred，直接反序列化会抛
     * InvalidFormatException 导致整个 workspace 读不出来。已删除的类型同样映射过来，
     * 让旧数据能被读进来而不是让用户先手动清库。
     */
    @JsonCreator
    public static RelationType fromValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "foreign_key", "foreign_key_declared" -> foreign_key;
            case "join_observed", "foreign_key_inferred", "lineage_to",
                 "same_as", "depends_on", "used_with" -> join_observed;
            case "term_mapping" -> term_mapping;
            default -> throw new IllegalArgumentException("Unknown relation type: " + value);
        };
    }

    /** 数据库导入维护的类型，人和 Agent 不能创建或编辑。 */
    public boolean isDatabaseOwned() {
        return this == foreign_key;
    }

    /**
     * 推断类关系：不是从数据库元数据直接读出来的，必须显式给出 confidence。
     * foreign_key / term_mapping 属于声明类，可用 rule.minConfidence 兜底。
     */
    public boolean isInferred() {
        return this == join_observed;
    }

    /**
     * 声明类关系的默认置信度；推断类返回 null（调用方必须显式提供）。
     */
    public Double defaultConfidence() {
        RelationEndpointRule rule = getEndpointRule();
        return isInferred() || rule == null ? null : rule.getMinConfidence();
    }

    /**
     * 获取此关系类型的端点规则。
     */
    public RelationEndpointRule getEndpointRule() {
        return ENDPOINT_RULES.get(this);
    }

    /**
     * 根据关系类型获取端点规则（静态方法）。
     */
    public static RelationEndpointRule getEndpointRule(RelationType type) {
        return type != null ? ENDPOINT_RULES.get(type) : null;
    }

    /**
     * 获取所有端点规则。
     */
    public static Map<RelationType, RelationEndpointRule> getAllEndpointRules() {
        return Map.copyOf(ENDPOINT_RULES);
    }
}
