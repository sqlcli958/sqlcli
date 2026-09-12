package com.sqlcli.graph.workspace;

import java.util.List;
import java.util.Locale;

/**
 * 一条血缘是「怎么写」的。四类，判据是读它的 agent 下一步做什么不同：
 *
 * <ul>
 *   <li>{@link #identity} 原样抄来 → 当快照，要当前值去源列</li>
 *   <li>{@link #transformation} 同一行算出 → 用 expression 里的公式，别猜</li>
 *   <li>{@link #aggregation} 多行汇总 → 可能与明细漂移，核对时从明细重算</li>
 *   <li>{@link #rule} 值不来自任何列，源列只决定写不写 → 改源列前先看 through</li>
 * </ul>
 *
 * <p>OpenLineage 的九个子类是给 ETL 管道设计的，JOIN / FILTER / GROUP_BY / SORT / WINDOW
 * 描述一条 SQL 的形状，对修 bug / 重构没有区别，全部收进 {@link #rule}
 * （拍板见 dev-checklist-2026-09 L1 节）。映射值按需算出，不落盘。
 */
public enum LineageKind {
    identity("快照", "DIRECT/IDENTITY"),
    transformation("同行计算", "DIRECT/TRANSFORMATION"),
    aggregation("多行汇总", "DIRECT/AGGREGATION"),
    rule("规则写入", "INDIRECT/CONDITIONAL");

    private final String label;
    private final String openLineage;

    LineageKind(String label, String openLineage) {
        this.label = label;
        this.openLineage = openLineage;
    }

    /** 中文短名，CLI 文本输出用；枚举名本身是契约，label 只是它旁边的注解。只在这里写一份。 */
    public String label() {
        return label;
    }

    public String openLineage() {
        return openLineage;
    }

    /**
     * 不给 kind 时只推断两种：单源无表达式是拷贝，有表达式是同行计算。
     * aggregation / rule 从字段形状认不出来，必须显式给，所以其余返回 null。
     */
    public static LineageKind infer(List<String> sources, String expression) {
        boolean hasExpression = expression != null && !expression.isBlank();
        if (hasExpression) return transformation;
        if (sources != null && sources.size() == 1) return identity;
        return null;
    }

    /**
     * 形状校验：kind 与字段对不上就返回一句带改法的错误，对得上返回 null。
     * 写入闸门和 {@code schema eval} 的 lineage.shape 探针共用这一份判据。
     */
    public static String validate(LineageKind kind, String target, List<String> sources, String expression) {
        boolean hasExpression = expression != null && !expression.isBlank();
        if (target != null && sources != null) {
            String key = target.toLowerCase(Locale.ROOT);
            for (String source : sources) {
                if (source != null && source.equalsIgnoreCase(key)) {
                    return "列不是自己的上游：" + target
                            + "。「谁在写」这类没有源列的事实写进那一列的 --description";
                }
            }
        }
        if (kind == null) {
            return "无法从形状推断 --kind（多源且无表达式）：汇总用 --kind aggregation，"
                    + "只决定写不写的用 --kind rule";
        }
        return switch (kind) {
            case identity -> (sources == null || sources.size() != 1 || hasExpression)
                    ? "identity 只能有一个源且不带 --expression；多源或有公式的用 --kind transformation"
                    : null;
            case transformation, aggregation -> hasExpression ? null
                    : "--kind " + kind + " 必须带 --expression（实际的推导表达式）";
            case rule -> (sources == null || sources.isEmpty())
                    ? "--kind rule 必须带决定写不写的源列；没有源列的写入方式用 schema edit --description"
                    : null;
        };
    }
}
