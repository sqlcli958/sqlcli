package com.sqlcli.graph.policy;

/**
 * 规则的作用域：{@code change} 只对 design review / migration lint 送进来的变更对象生效，
 * {@code all} 还会对存量图谱做全量检查。
 *
 * <p>命名类规则默认是 {@code change}。实测判据：某个十年前的遗留库上最后一次
 * {@code policy check} 报了 1487 条违规、其中 1215 条是 {@code naming_convention}，
 * 豁免 0 条，之后无人再跑——那些表名永远不会改，而一份没人能执行的报告和没有报告是一样的。
 * 要在存量库上跑命名规则，在规则里写 {@code scope: all}，或临时加 {@code --all}。
 */
public enum PolicyScope {
    change, all
}
