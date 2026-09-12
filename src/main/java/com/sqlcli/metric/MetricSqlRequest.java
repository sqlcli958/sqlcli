package com.sqlcli.metric;

import java.util.List;

/**
 * 一次「metric 展开成 SQL」请求：切哪个时间粒度、要不要限定时间窗、按哪些维度切。
 *
 * <p>三者都可选，且互相独立：
 * <ul>
 *   <li>{@code grain} 为空时不生成时间分桶列（SELECT/GROUP BY 都不带），但只要 metric
 *       声明了 {@code grain.timeColumn}，{@code timeFrom}/{@code timeTo} 仍然可以单独使用，
 *       只加 WHERE 过滤、不加分桶——「过去 30 天的总量」不需要按天拆开。</li>
 *   <li>{@code dimensionColumnIds} 是 metric 声明的 {@code dimensions} 的子集（已解析成列 id，
 *       不是原始 ref 字符串），空列表表示不按维度拆分，只出总量/时间序列。</li>
 * </ul>
 */
public record MetricSqlRequest(String grain, String timeFrom, String timeTo, List<String> dimensionColumnIds) {
    public MetricSqlRequest {
        dimensionColumnIds = dimensionColumnIds == null ? List.of() : List.copyOf(dimensionColumnIds);
    }
}
