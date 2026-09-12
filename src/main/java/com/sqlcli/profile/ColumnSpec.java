package com.sqlcli.profile;

import com.sqlcli.graph.workspace.SemanticType;

/**
 * 要剖析的一列。
 *
 * <p>semanticType 是可选的：传了才能触发"敏感列不列样本值"这条硬约束，以及未来
 * "标注质量对账"（标了 phone 的列实际长度分布不像手机号 → 告警）——那一步这次不做，
 * 但字段留在这里，下一批直接能用，不用改调用方签名。
 */
public record ColumnSpec(String columnName, SemanticType semanticType) {

    public ColumnSpec(String columnName) {
        this(columnName, null);
    }

    /**
     * 敏感列（手机号/身份证/邮箱/姓名/地址/银行卡/账号）不暴露实际取值，只出统计量。
     * 这不是"可以关掉的选项"，是 CLAUDE.md 与任务书都点名的硬约束，所以不放进
     * {@link ProfileOptions}——可配置的东西才需要开关，安全底线不需要。
     */
    boolean sensitive() {
        return semanticType != null && semanticType.isSensitive();
    }
}
