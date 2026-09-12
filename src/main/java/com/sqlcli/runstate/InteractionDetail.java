package com.sqlcli.runstate;

import java.util.List;

/**
 * 一次交互的**明细**，由具体命令填，由 {@code SqlCli.run} 收口时写进
 * {@code agent_interaction} 的同一行。
 *
 * <h2>为什么要这么绕</h2>
 * 埋点只该有一处——{@code SqlCli.run} 是所有命令的唯一入口，而且它已经在 catch 异常、
 * 返回退出码，所以「这次交互成没成、错在哪」只有它知道全。在 28 个 action 里各埋一次
 * 的结果是：漏掉那些**根本走不到 handler 的失败**（参数不合法、别名不存在、
 * describe 找不到表——它在解析出表之前就 return 了），而那恰恰是最值钱的一类。
 *
 * <p>但入口看不见「搜到了几条、命中了哪些对象」。所以命令把明细放这里，入口取走，
 * 两边拼成一行——**而不是写两行**。写两行的话「交互了几次」这个最基本的问题就要去重。
 *
 * <p>用 {@link ThreadLocal} 而不是普通静态字段：CLI 进程一条命令一次调用不会撞，
 * 但 {@code RunStateStore} 同时被常驻的 Web UI 用着，静态字段会在那边串台。
 */
public final class InteractionDetail {

    private static final ThreadLocal<InteractionDetail> CURRENT = new ThreadLocal<>();

    /**
     * {@code schema <动作>} 这类命令在交互日志里的 action 名。
     *
     * <p><b>只能有这一处拼法。</b>入口（{@code SqlCli.actionOf}）写进去、查询侧
     * （{@code countTargetHits} / {@code missedSearchQueries} / {@code describe} 的
     * 「被搜到 N 次」）读出来，两边各写一遍字符串的后果实测过两次：
     * 写的是 {@code "schema search"}、查的是 {@code "search"}，
     * **数永远是 0 而且不报任何错**——照跑、有结果、数是错的。
     */
    public static String schemaAction(String action) {
        return "schema " + action;
    }

    private final String query;
    private final Integer hitCount;
    private final Double topScore;
    private final List<String> targetIds;

    private InteractionDetail(String query, Integer hitCount, Double topScore,
            List<String> targetIds) {
        this.query = query;
        this.hitCount = hitCount;
        this.topScore = topScore;
        this.targetIds = targetIds;
    }

    /**
     * 记下这次读命中了什么。**同一次调用里多次 set 以最后一次为准**——
     * 目前没有命令会调两次，真出现了也该是「最终结果」而不是中间态。
     *
     * @param query     检索词或被查看对象的名字，**存原文不归一化**：这批要解决的
     *                  恰恰是「业务词搜不到对的表」，把词磨平就把线索磨掉了
     * @param targetIds 命中对象的 id，{@code schema gaps} 靠它数「哪张表被搜到过多少次」
     */
    public static void set(String query, Integer hitCount, Double topScore,
            List<String> targetIds) {
        CURRENT.set(of(query, hitCount, topScore, targetIds));
    }

    /** 直接造一条明细，不经过 ThreadLocal。给测试和直接调用 {@code recordInteraction} 的地方用。 */
    public static InteractionDetail of(String query, Integer hitCount, Double topScore,
            List<String> targetIds) {
        return new InteractionDetail(query, hitCount, topScore, targetIds);
    }

    /** 取走并清空。入口调一次，清空是为了下一次调用（同一线程复用时）不带上一次的残留。 */
    public static InteractionDetail take() {
        InteractionDetail detail = CURRENT.get();
        CURRENT.remove();
        return detail;
    }

    public String query() {
        return query;
    }

    public Integer hitCount() {
        return hitCount;
    }

    public Double topScore() {
        return topScore;
    }

    public List<String> targetIds() {
        return targetIds;
    }
}
