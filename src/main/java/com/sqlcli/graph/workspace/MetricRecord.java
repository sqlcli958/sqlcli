package com.sqlcli.graph.workspace;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/**
 * BI 语义层的指标定义：一个业务口径（GMV、复购率……）的权威声明。
 *
 * <p>不是 {@link RelationWorkspaceEdge}（决策见 dev-checklist「BI 语义层」一节，理由与
 * 「字段血缘不是关系类型」逐字相同）：指标不是两个节点之间的二元边，它需要独立的口径上下文
 * （expression / filters / grain / dimensions），塞进 relations 的扁平边列表既装不下这些字段，
 * 也会让"这条边是不是指标"变成要靠 attributes 猜的事。因此它和 terms / lineage 一样，是
 * {@link GraphWorkspace} 里独立的第四个集合，白拿同一套 revision 乐观锁、候选状态、
 * 人工评审发布、变更审计、export/import 载体，不重新发明。
 *
 * <p>只装"让 Agent 少猜一次"的东西：物化、预聚合、调度、metric store 存储层、指标血缘大图
 * 明确不装，那是 dbt / Cube 的赛道，这里只服务于"让 Agent 生成更正确的 SQL"。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MetricRecord extends BaseGraphObject {
    private String sourceAlias;
    /** 稳定标识，如 gmv_paid；同名重写是幂等 upsert（见 {@link GraphIds#metricId}）。 */
    private String name;
    private String businessName;
    /** 同义词，复用 {@link TermWorkspaceNode} 的思路：检索要能靠业务黑话命中，不是只能靠技术名。 */
    private List<String> aliases = new ArrayList<>();
    /** 聚合函数 + 列引用，如 {@code SUM(order.amount)}。是否可执行由消费方（SQL 生成层）解析，
     * 这一层只负责把它存下来、让评审能看见、让检索能命中——解析与校验不属于模型/存储/检索这一层。 */
    private String expression;

    /**
     * 口径过滤条件，如 {@code status IN (2,3)}。
     *
     * <p>这一行是整个 metric 层的存在理由：同一个"订单金额"，算不算取消单、算不算退款单，
     * 90% 的口径争议就发生在这一行，而不是 expression 的聚合函数上。不能把它做成可选附属字段——
     * 没有 filters 的 metric 只是给聚合函数起了个名字，没有回答"这个数到底怎么算"这个真正的问题。
     */
    private String filters;

    /** 时间列 + 支持到什么粒度，如 created_at / [day, week, month]。 */
    private MetricGrain grain;

    /**
     * 允许按哪些列切的维度，元素是列 id（{@code column:alias:schema.table.column}）。
     * 取值域直接查目标列自己的 {@link ColumnValueHints#getEnumValues()}，不在这里复制一份——
     * 同一份枚举值分头维护两处，迟早对不上。
     */
    private List<String> dimensions = new ArrayList<>();

    /**
     * 跨表时的 JOIN 路径，人工显式声明，不是自动发现的产物。
     *
     * <p>理由一：口径本身就是要扯皮的东西。LookML 的 explore、Cube 的 join 都是人手写的，
     * 不是系统替你猜的——猜对了没人知道为什么对，猜错了更没人能看出来错在哪。
     *
     * <p>理由二（更硬的技术理由）：跨表聚合有两个自动推导天生防不住的经典陷阱，
     * Business Objects 时代就有名字了：
     * <ul>
     *   <li><b>扇形陷阱 fan trap</b>——{@code A →1:N B →1:N C}，对 {@code B.amount} 求和时，
     *       会被 C 的行数原样乘大，金额直接算错且不报错，因为 SQL 语法上完全合法；</li>
     *   <li><b>深坑陷阱 chasm trap</b>——从同一维度分别以 1:N 连出两张事实表，两边做笛卡尔积，
     *       两个指标同时算错，同样不报语法错误。</li>
     * </ul>
     * 两个陷阱的共同点是：路径在图上都"看起来能走通"，出错的是基数，不是连通性——
     * 而基数正是自动 JOIN 路径发现完全不检查的东西。所以 joinPath 必须是权威声明而不是
     * 图遍历的产物：**metric 里人工声明的 join 是全系统质量最高的 join 知识**，
     * 它应该反哺给图谱，而不是等图谱自动推导后喂给它。
     *
     * <p>如果将来有人提"要不 joinPath 自动生成/自动推导吧"——请先回答这两个陷阱怎么防，
     * 而不是先去实现。
     */
    private List<MetricJoinStep> joinPath = new ArrayList<>();

    /**
     * 比率指标的分子，如复购率的 {@code COUNT(DISTINCT user_id) WHERE order_count >= 2}。
     * 与 {@link #denominator} 成对出现——只声明其中一个的 metric 不是合法的比率指标，
     * 展开时仍然会走 {@link #expression} 单串分支（多半直接报错，因为 expression 也没填）。
     *
     * <p>不复用 {@link #expression}/{@link #filters} 装分子：分子分母各自的 filters 天差地别
     * （复购率分子要"下单≥2次"，分母不要），塞进同一对字段会变成"到底是谁的过滤条件"。
     */
    private MetricComponent numerator;
    /** 比率指标的分母，见 {@link #numerator}。 */
    private MetricComponent denominator;

    /**
     * 可加性：这个数能不能跨维度/跨时间直接相加。三档取自 Kimball 维度建模的经典分类：
     * <ul>
     *   <li>{@code additive}——随便加，SUM 之后再 SUM 结果不变（订单金额）；</li>
     *   <li>{@code semi_additive}——跨维度能加、跨时间不能加（库存余额：把 1 号和 2 号的
     *       余额加起来毫无意义，应该取某一天的值）；</li>
     *   <li>{@code non_additive}——怎么加都不对，只能从分子分母重新算（复购率、客单价）。</li>
     * </ul>
     * 比率指标（{@link #numerator}/{@link #denominator} 都声明了）不需要人填这个字段——
     * {@link #effectiveAdditivity()} 会强制推定成 {@code non_additive}，分子分母结构本身
     * 就已经回答了这个问题。真正需要人填的是 semi-additive，展开器判断不出"这是个快照"。
     */
    private Additivity additivity;

    public static MetricRecord create(String sourceAlias, String name, GraphActor actor) {
        MetricRecord record = new MetricRecord();
        record.init(GraphObjectKind.metric, GraphIds.metricId(sourceAlias, name), actor);
        record.setSourceAlias(sourceAlias);
        record.setName(name);
        record.setBusinessName(name);
        record.setStatus(GraphStatus.forActor(actor));
        record.setConfidence(0.8);
        record.setVerified(false);
        return record;
    }

    /** 有没有声明成分子/分母结构；决定 {@code MetricSqlExpander} 走哪个展开分支。
     * 只声明其中一个不算——那不是一个能算出比率的完整结构。 */
    public boolean isRatio() {
        return numerator != null && denominator != null;
    }

    /** 实际生效的可加性：比率结构不管 {@link #additivity} 填没填、填的是什么，都强制是
     * {@code non_additive}——分子分母结构本身就是"不可加"的证明，不该被人为改写。
     * 非比率指标原样返回人填的值（可能是 null，代表没标注，展开器不拦）。 */
    public Additivity effectiveAdditivity() {
        return isRatio() ? Additivity.non_additive : additivity;
    }

    /** 比率指标一侧（分子或分母）的口径：表达式 + 只属于这一侧的过滤条件。 */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MetricComponent {
        /** 聚合表达式，如 {@code COUNT(DISTINCT user_id)}；与顶层 {@link MetricRecord#expression}
         * 同样不透明，展开器原样拼，不解析。 */
        private String expression;
        /** 只作用于这一侧的过滤条件，如复购率分子的 {@code order_count >= 2}；分母通常留空
         * （"全部用户"）。这是比率指标存在的理由——分子分母各自的口径必须能分别声明，
         * 塞进同一个 filters 字段没法表达"这个条件只卡分子"。 */
        private String filters;
    }

    public enum Additivity {
        additive,
        semi_additive,
        non_additive
    }

    /** 时间列是哪个 + 支持到什么粒度；两者缺一都答不出"按天还是按月看"这个问题。 */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MetricGrain {
        /** 时间列 id（{@code column:alias:schema.table.column}）。 */
        private String timeColumn;
        /** 支持的粒度，如 [day, week, month]；自由文本，不是枚举——粒度命名跟着业务习惯走，
         * 强行收敛成固定枚举只会让"季度"这种真实存在的口径无处安放。 */
        private List<String> grains = new ArrayList<>();
    }

    /**
     * JOIN 路径的一步：引用一条已存在的关系边（沿用它声明的 from/to 列，不再重复声明一遍列），
     * 只额外声明这一步在本指标里按 INNER 还是 LEFT 连——同一条关系边在不同指标里可能需要不同的
     * JOIN 类型（比如"有没有下单"要 LEFT，"下单金额"要 INNER），这是指标口径的一部分，
     * 不该硬编码在关系边本身上。
     */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MetricJoinStep {
        /** 关系边 id，必须已存在于 {@link GraphWorkspace#getRelations()}。 */
        private String relationId;
        private MetricJoinType joinType = MetricJoinType.inner;
    }

    public enum MetricJoinType {
        inner,
        left
    }
}
