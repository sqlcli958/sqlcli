package com.sqlcli.graph.workspace;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.EqualsAndHashCode;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TermWorkspaceNode extends BaseGraphObject {
    private String sourceAlias;
    private String name;
    private String displayName;
    private List<String> aliases = new ArrayList<>();
    private List<String> negativeAliases = new ArrayList<>();
    private String description;

    /**
     * 场景的入口对象：子图的 {@code FROM} 从这里开始。表或列的 id，可为空。
     *
     * <p><b>它指向什么，决定这条术语是不是一个场景</b>——指向表就能展开成子图
     * （入口表 + 映射的表 + 它们之间已有的关系），指向列或留空就退化成同义词路由。
     *
     * <p>不用一个显式的 {@code kind: concept|scenario} 字段来区分，是因为那种声明
     * 可以和事实不符：标着 scenario 却没有入口表，就是一条不报错的坏数据。
     */
    private String primaryTarget;

    /**
     * <b>场景专属</b>的过滤条件。判据是「换一个场景这条还成不成立」——
     * {@code state IN (0,1,6)}（未完结的报事）成立不了，因为回访场景要的是
     * {@code state IN (6,7)}；而 {@code del_flag = 0} 换任何场景都成立，
     * 那属于全库约定，走 policy 的约定规则，不写在这里。
     *
     * <p>元素可以是完整条件，也可以带占位符表示<b>必填参数</b>：
     * {@code subject_id = :subjectId}。值运行时才有，但位置必须有——
     * 漏掉项目隔离列不是多查几行，是<b>查到别的项目的数据</b>。
     */
    private List<String> filters = new ArrayList<>();

    public static TermWorkspaceNode create(String sourceAlias, String name, GraphActor actor) {
        TermWorkspaceNode node = new TermWorkspaceNode();
        node.init(GraphObjectKind.term, "term:" + sourceAlias + ":" + name, actor);
        node.setSourceAlias(sourceAlias);
        node.setName(name);
        node.setDisplayName(name);
        node.setStatus(GraphStatus.forActor(actor));
        node.setConfidence(0.8);
        node.setVerified(false);
        return node;
    }
}
