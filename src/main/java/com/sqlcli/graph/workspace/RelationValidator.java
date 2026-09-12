package com.sqlcli.graph.workspace;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * 验证关系端点类型和规则。
 */
public class RelationValidator {

    @Getter
    public static class ValidationResult {
        private final boolean valid;
        private final List<String> errors;

        public ValidationResult(boolean valid, List<String> errors) {
            this.valid = valid;
            this.errors = errors != null ? errors : new ArrayList<>();
        }

        public static ValidationResult success() {
            return new ValidationResult(true, new ArrayList<>());
        }

        public static ValidationResult failure(List<String> errors) {
            return new ValidationResult(false, errors);
        }
    }

    /** verified=true 所需的最低置信度。 */
    public static final double VERIFIED_MIN_CONFIDENCE = 0.9;

    public ValidationResult validate(RelationType type, GraphObjectKind fromKind,
                                      GraphObjectKind toKind, Double confidence) {
        return validate(type, fromKind, toKind, confidence, null);
    }

    /**
     * 验证关系是否合法。
     * @param type 关系类型
     * @param fromKind from 端点类型
     * @param toKind to 端点类型
     * @param confidence 置信度；推断类关系必须显式提供
     * @param verified 是否标记为已验证；为 true 时要求 confidence >= 0.9
     * @return 验证结果
     */
    public ValidationResult validate(RelationType type, GraphObjectKind fromKind,
                                      GraphObjectKind toKind, Double confidence, Boolean verified) {
        List<String> errors = new ArrayList<>();

        RelationEndpointRule rule = RelationType.getEndpointRule(type);
        if (rule == null) {
            errors.add("unknown relation type: " + type);
            return ValidationResult.failure(errors);
        }

        if (!rule.isValidEndpoint(fromKind, toKind)) {
            errors.add(String.format("invalid endpoint for %s: from=%s, to=%s, valid from=%s, valid to=%s",
                    type, fromKind, toKind, rule.getValidFromKinds(), rule.getValidToKinds()));
        }

        if (confidence == null) {
            if (type.isInferred()) {
                errors.add(String.format(
                        "relation type %s is inferred and requires an explicit confidence"
                                + " (>= %.2f); pass --confidence or the \"confidence\" field",
                        type, rule.getMinConfidence()));
            }
        } else if (confidence < 0.0 || confidence > 1.0) {
            errors.add(String.format("confidence %.2f must be between 0.0 and 1.0", confidence));
        } else if (confidence < rule.getMinConfidence()) {
            errors.add(String.format("confidence %.2f below minimum %.2f for %s",
                    confidence, rule.getMinConfidence(), type));
        }

        if (Boolean.TRUE.equals(verified)
                && (confidence == null || confidence < VERIFIED_MIN_CONFIDENCE)) {
            errors.add(String.format(
                    "verified=true requires confidence >= %.2f but was %s; publish the relation"
                            + " after raising its confidence",
                    VERIFIED_MIN_CONFIDENCE, confidence == null ? "unset" : String.format("%.2f", confidence)));
        }

        return errors.isEmpty() ? ValidationResult.success() : ValidationResult.failure(errors);
    }

    /**
     * 验证 declared FK 是否受到保护（不允许页面创建或删除）。
     */
    public boolean isProtectedRelation(RelationType type, GraphActor createdBy) {
        if (type == RelationType.foreign_key) {
            return createdBy == GraphActor.human;
        }
        return false;
    }

    /**
     * 检查关系类型是否允许用户创建。
     * foreign_key 由系统/导入创建，不允许用户手动创建。
     */
    public boolean isUserCreatable(RelationType type) {
        return type != RelationType.foreign_key;
    }
}
