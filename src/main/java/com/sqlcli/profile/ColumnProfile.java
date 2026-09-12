package com.sqlcli.profile;

import java.util.List;

/**
 * 单列剖析结果。
 *
 * <p>字段形状是按下一批"写回图谱"的消费方设计的，不是只为打印一份报告：
 * {@code enumValues} 直接对应 {@code ColumnValueHints.enumValues} 的候选来源；
 * {@code nullRatio} 直接喂关系 optionality 推断，比只看 {@code nullable} 准；
 * {@code lowCardinality} 是"要不要自动生成候选"的开关。
 * 这一批任务约束不做写回（那一步要碰 {@code graph/workspace}，按约束不能动），
 * 但字段名和粒度已经按写回方对齐，减少下一批的转换成本。
 *
 * @param topValue 采样里出现次数最多的非空取值；敏感列（见 {@link ColumnSpec#sensitive()}）
 *                 恒为 null——那条约束比这里的可读性重要，计数照给、值不给
 * @param topCount {@code topValue} 出现的次数；没测出来时是 0。它补上的正是 E1 交付说明里
 *                 「分布倾斜只判到非空行全是同一个值」那个缺口——有了计数才能判
 *                 「99% 集中在一个值」，而不是只能判 distinct == 1
 */
public record ColumnProfile(
        String columnName,
        boolean skipped,
        String skipReason,
        long rowsSampled,
        long nonNullCount,
        double nullRatio,
        long distinctCount,
        boolean lowCardinality,
        List<String> enumValues,
        String minValue,
        String maxValue,
        LengthStats lengthStats,
        String topValue,
        long topCount
) {

    /**
     * 某一列统计失败时用这个工厂造一条"跳过"记录。
     *
     * <p>字段全部清零而不是让调用方对着 null 到处判断——只看 {@code skipped} 一个开关，
     * 其余字段的值在 skipped=true 时没有意义，不需要区分"是 0 还是没测出来"。
     */
    public static ColumnProfile skipped(String columnName, String reason) {
        return new ColumnProfile(columnName, true, reason, 0, 0, 0.0, 0, false, List.of(),
                null, null, null, null, 0);
    }

    /**
     * 字符串列的长度分布，只给 min/max/avg 三个数，不是完整直方图。
     *
     * <p>够用的判据：这次点名的消费方是"标了 phone 的列长度分布不像手机号"这类质量告警——
     * min=max=avg=11 就是像，跨度很大就是不像，三个数已经回答这个问题。真要做异常检测
     * 需要分桶直方图时再加，现在加是给一个还不存在的消费方预留结构。
     */
    public record LengthStats(int minLength, int maxLength, double avgLength) {
    }
}
