package com.sqlcli.profile;

import java.time.Instant;
import java.util.List;

/**
 * 一张表的剖析结果：表级行数 + 每列的 {@link ColumnProfile}。
 *
 * <p>行数是精确的 {@code COUNT(*)}，不是按 {@code ProfileOptions.sampleSize} 采样出来的——
 * 这是"行数（表级）"这个产出项本身要求的语义，采样规模只管每列统计基于多少行。
 * 它可能因为超时或权限失败，失败时 {@code rowCount} 为 -1、{@code rowCountError} 非空，
 * 不会因为行数拿不到就让整张表的剖析全部作废。
 */
public record TableProfile(
        String schemaName,
        String tableName,
        long rowCount,
        String rowCountError,
        Instant profiledAt,
        List<ColumnProfile> columns
) {

    public boolean rowCountAvailable() {
        return rowCountError == null;
    }
}
