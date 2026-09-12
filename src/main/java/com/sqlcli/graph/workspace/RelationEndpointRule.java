package com.sqlcli.graph.workspace;

import lombok.Getter;

import java.util.Collections;
import java.util.Set;

/**
 * 关系端点规则：定义每种 RelationType 合法的 from/to kind 组合。
 */
@Getter
public class RelationEndpointRule {
    private final RelationType type;
    private final Set<GraphObjectKind> validFromKinds;
    private final Set<GraphObjectKind> validToKinds;
    private final double minConfidence;

    public RelationEndpointRule(RelationType type, Set<GraphObjectKind> validFromKinds,
                                 Set<GraphObjectKind> validToKinds, double minConfidence) {
        this.type = type;
        this.validFromKinds = validFromKinds != null ? Collections.unmodifiableSet(validFromKinds) : Collections.emptySet();
        this.validToKinds = validToKinds != null ? Collections.unmodifiableSet(validToKinds) : Collections.emptySet();
        this.minConfidence = minConfidence;
    }

    /**
     * 检查给定的 from/to kind 组合是否合法。
     */
    public boolean isValidEndpoint(GraphObjectKind fromKind, GraphObjectKind toKind) {
        return validFromKinds.contains(fromKind) && validToKinds.contains(toKind);
    }
}
