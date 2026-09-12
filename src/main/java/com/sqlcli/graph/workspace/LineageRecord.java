package com.sqlcli.graph.workspace;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/**
 * 字段级血缘：一条 n 元推导（sources → target），带表达式和产生上下文。
 *
 * <p>它**不是**关系边（决策见 dev-checklist P3）：n 元推导拆成二元边会丢
 * 「a 和 b 一起产生 c」这个结构；{@code through} 是血缘本身的一部分而不是佐证；
 * 规模比 relations 大两个数量级，进扁平 relations 列表会拖垮 26 处线性全扫。
 * 因此它是 {@link GraphWorkspace} 里与 terms 平级的第三个集合，
 * 复用 BaseGraphObject 的候选状态（agent 写入 = candidate）。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LineageRecord extends BaseGraphObject {
    private String sourceAlias;
    /** 派生出来的列 id，恰好 1 个。 */
    private String target;
    /** 参与推导的源列 id，n 个。 */
    private List<String> sources = new ArrayList<>();
    /** 推导表达式，如 SUM(a)+SUM(b)、CASE WHEN ...。 */
    private String expression;
    /** 靠什么产生：视图名 / ETL 作业 / Mapper statement id。 */
    private String through;
    /**
     * 怎么写的：抄 / 算 / 汇总 / 按条件写。字段名不叫 kind 是因为 {@link BaseGraphObject#getKind()}
     * 已经是对象种类（lineage）；CLI 参数仍叫 {@code --kind}。
     * 旧记录没有这一列，加载时按 {@link LineageKind#infer} 在内存里补，不回写。
     */
    private LineageKind lineageKind;

    public static LineageRecord create(String sourceAlias, String target, List<String> sources,
            String expression, String through, GraphActor actor) {
        return create(sourceAlias, target, sources, expression, through,
                LineageKind.infer(sources, expression), actor);
    }

    public static LineageRecord create(String sourceAlias, String target, List<String> sources,
            String expression, String through, LineageKind lineageKind, GraphActor actor) {
        LineageRecord record = new LineageRecord();
        record.init(GraphObjectKind.lineage, GraphIds.lineageId(sourceAlias, target, through), actor);
        record.setSourceAlias(sourceAlias);
        record.setTarget(target);
        record.setSources(new ArrayList<>(sources));
        record.setExpression(expression);
        record.setThrough(through);
        record.setLineageKind(lineageKind);
        record.setStatus(GraphStatus.forActor(actor));
        record.setConfidence(0.8);
        record.setVerified(false);
        return record;
    }
}
