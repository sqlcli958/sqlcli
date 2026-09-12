package com.sqlcli.profile;

/**
 * 单列剖析引擎的可配项——采样规模、超时、只读连接都不写死。
 * 理由见任务背景：这东西要跑在生产库上，一条没有 LIMIT 的 {@code COUNT(DISTINCT ...)}
 * 就能把库拖垮，"默认安全、显式放宽" 是唯一站得住的默认值方向。
 *
 * @param sampleSize 每列统计基于的采样行数上限，&lt;= 0 表示不采样、直接全表扫描
 *                    （与既有 {@code --max-rows 0} 用同一套 "0 = 不限" 的约定，不另起一套语义）。
 *                    默认 10000：大到让 distinct 数 / 空值率这类比例统计不会因样本太小而失真
 *                    （大数定律意义上足够稳定），小到一条聚合查询在生产库上是毫秒级——
 *                    这正是任务书点名要防住的那种查询。表本身行数小于这个值时，效果等同全量。
 * @param lowCardinalityThreshold distinct 数不超过这个阈值才把实际取值列进
 *                    {@code enumValues} 候选，超过只给基数不列值。默认 50：
 *                    覆盖状态码/类型这类典型枚举列，又不会把姓名、地址这类高基数列意外拖进来
 *                    （敏感语义类型另有强制跳过，见 {@link ColumnSpec#sensitive()}，
 *                    这个阈值只管非敏感列该不该列值）。
 * @param queryTimeoutSeconds 单条统计 SQL 的超时秒数，默认 30——与 CLI 查询执行链路里
 *                    {@code EffectiveLimits} 的默认查询超时保持一致，不另起一个数字。
 * @param readOnlyConnection 是否在剖析前尝试把连接标记为只读（{@link java.sql.Connection#setReadOnly}）。
 *                    只是双重保险：真正的只读保证应该来自调用方传入的连接本身（例如走只读别名）；
 *                    部分 JDBC 驱动对 setReadOnly 支持不完整或有前置条件限制，失败时静默忽略，
 *                    不能因为一个保险性质的调用失败就让整次剖析跑不起来。
 */
public record ProfileOptions(
        int sampleSize,
        int lowCardinalityThreshold,
        int queryTimeoutSeconds,
        boolean readOnlyConnection
) {

    public static ProfileOptions defaults() {
        return new ProfileOptions(10_000, 50, 30, true);
    }
}
