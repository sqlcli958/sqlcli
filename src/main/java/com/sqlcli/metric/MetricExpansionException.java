package com.sqlcli.metric;

/**
 * metric 展开成 SQL 失败时抛出：引用的表/列/关系在图谱里找不到，或者请求的粒度/维度
 * 超出该 metric 的声明范围。
 *
 * <p>不生成半截 SQL 是这一层的硬约束——消息必须点名缺的是哪个 id、哪个字段，
 * 让 Agent 能直接定位问题（去 add-relation 补关系，或者去 schema edit 补列），
 * 而不是拿到一段看似能跑、实际引用了不存在对象的 SQL。
 */
public class MetricExpansionException extends RuntimeException {
    public MetricExpansionException(String message) {
        super(message);
    }
}
