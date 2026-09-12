package com.sqlcli.graph.eval;

import com.sqlcli.runstate.RunStateStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从**失败的交互**里挖发现：agent 拿着图谱去干活，撞了什么墙。
 *
 * <h2>为什么它不在 {@link GraphEvaluator} 里</h2>
 * 那个类是纯函数，理由写在 {@link GraphEvaluation} 的注释里——同一份图谱跑两次结果
 * 必须一样，否则两次评估没法比。而交互记录会随时间变，塞进去就把这条毁了。
 * 所以它由 {@link GraphEvalRunner} 在落库那一步追加，评估器本身不动。
 *
 * <h2>为什么一律是 warning</h2>
 * 硬错误决定这次评估的成败（退出码非 0），而「agent 猜错了一个表名」不是图谱的错。
 * 把它算成硬错误，`schema eval` 会因为别人手滑而失败。跟数据层探针同一条判据：
 * 「值得看」不等于「一定错」。
 *
 * <h2>三类归因，分开报</h2>
 * 前两类修图谱、第三类修技能。混在一起就是 {@code task-eval} 那份文档说的
 * 「通过率是一个无法归因的数字」——看了不知道该补图谱还是改 skill。
 */
public final class InteractionProbes {

    /**
     * 一次评估最多看这么多条失败记录。
     *
     * <p>不是性能考虑，是**判据**：真出现成千上万条失败，该报的不是一万条发现，
     * 而是「这个库上 agent 一直在撞墙」这一件事，那需要人去看，不是队列能解决的。
     * 超出部分由 {@link GraphEvaluator#gate} 收成一条。
     */
    private static final int MAX_ROWS = 500;

    /** 窗口兜底：从没跑过评估时往回看这么久。 */
    private static final long FALLBACK_WINDOW_MS = 7L * 24 * 60 * 60 * 1000;

    /**
     * 字段不存在。
     *
     * <p><b>中文那条是主力</b>：sql-cli 自己会拿图谱预检，还没打到库就拦下了，措辞是
     * 「列 'x' 在图谱中未找到」；英文那几条是真打到库之后 MySQL / PG 的报错。
     * 第一版只写了英文，结果真库上最常见的那条反而漏掉了——实测漏了才发现。
     */
    private static final Pattern UNKNOWN_COLUMN = Pattern.compile(
            "列\\s*['\"]?([\\w.]+)['\"]?\\s*在图谱中未找到"
                    + "|(?i)(?:unknown column|column .* does not exist|invalid column name)"
                    + "\\s*['\"]?([\\w.]+)");

    /** 表不存在，中英文同样都要认，理由见上。 */
    private static final Pattern MISSING_TABLE = Pattern.compile(
            "表\\s*['\"]?([\\w.]+)['\"]?\\s*在图谱中未找到"
                    + "|(?i)(?:table not found:|table\\s+['\"]?([\\w.]+)['\"]?\\s+doesn'?t exist"
                    + "|relation\\s+[\"]?([\\w.]+)[\"]?\\s+does not exist)\\s*['\"]?([\\w.]*)");

    private InteractionProbes() {
    }

    /**
     * @param alias 数据源
     * @param since 窗口起点（毫秒）；传 0 表示用 {@link #FALLBACK_WINDOW_MS} 兜底
     */
    public static List<GraphFinding> run(RunStateStore runState, String alias, long since) {
        long from = since > 0 ? since : System.currentTimeMillis() - FALLBACK_WINDOW_MS;
        List<RunStateStore.FailedInteraction> failures =
                runState.failedInteractionsSince(alias, from, MAX_ROWS);
        if (failures.isEmpty()) return List.of();

        // 同一个错误撞十次是一条发现（带次数），不是十条——十条会把队列淹掉，
        // 而「撞了十次」本身才是优先级信号
        Map<String, Bucket> tableMiss = new LinkedHashMap<>();
        Map<String, Bucket> columnMiss = new LinkedHashMap<>();
        Map<String, Bucket> gateReject = new LinkedHashMap<>();

        for (RunStateStore.FailedInteraction failure : failures) {
            String error = failure.errorSummary();
            if (error == null || error.isBlank()) continue;
            String table = firstGroup(MISSING_TABLE.matcher(error));
            if (table != null) {
                tableMiss.computeIfAbsent(table, Bucket::new).add(failure);
                continue;
            }
            String column = firstGroup(UNKNOWN_COLUMN.matcher(error));
            if (column != null) {
                columnMiss.computeIfAbsent(column, Bucket::new).add(failure);
                continue;
            }
            // 剩下的：命令被工具自己拒了（闸门、参数不合法）。判据是「动作是写图谱」——
            // 连库的 SQL 报错上面两条已经吃掉，剩下的 schema 写命令失败都是用法问题。
            if (failure.action() != null && failure.action().startsWith("schema ")) {
                gateReject.computeIfAbsent(failure.action(), Bucket::new).add(failure);
            }
        }

        List<GraphFinding> out = new ArrayList<>();
        out.addAll(GraphEvaluator.gate("agent.table-miss", tableMissFindings(alias, tableMiss)));
        out.addAll(GraphEvaluator.gate("agent.column-miss", columnMissFindings(alias, columnMiss)));
        out.addAll(GraphEvaluator.gate("agent.gate-reject", gateRejectFindings(gateReject)));
        return out;
    }

    private static List<GraphFinding> tableMissFindings(String alias, Map<String, Bucket> buckets) {
        List<GraphFinding> out = new ArrayList<>();
        // targetId 用它要找的那个名字，不是图谱 id——这个对象**恰恰不在图谱里**，
        // 编一个 id 会变成 desc.dead-ref 探针要抓的那种死引用。留空则工作队列的
        // 「对象」列是空的，一列空白的队列没法排序也没法定位
        buckets.forEach((table, bucket) -> out.add(GraphFinding.warning(
                "agent.table-miss", table,
                "agent 查了 " + table + " 但图谱/库里没有（" + bucket.count + " 次）：" + bucket.sample,
                // 两种可能，命令给的是「先确认它到底存不存在」那一步，不是盲目重导
                "sql-cli " + alias + " schema search '" + shortName(table) + "'"
                        + "   # 找不到再看是不是新表没导入：schema import --from-db --schema <schema>")));
        return out;
    }

    private static List<GraphFinding> columnMissFindings(String alias, Map<String, Bucket> buckets) {
        List<GraphFinding> out = new ArrayList<>();
        buckets.forEach((column, bucket) -> out.add(GraphFinding.warning(
                "agent.column-miss", column,
                "agent 按图谱写了字段 " + column + "，库里没有（" + bucket.count + " 次）："
                        + bucket.sample
                        + "。字段名对不上会报错，而值域对不上不报错——那种更危险",
                "sql-cli " + alias + " schema describe <表> --json   # 跟库核对后重导这张表")));
        return out;
    }

    private static List<GraphFinding> gateRejectFindings(Map<String, Bucket> buckets) {
        List<GraphFinding> out = new ArrayList<>();
        buckets.forEach((action, bucket) -> out.add(GraphFinding.warning(
                "agent.gate-reject", action,
                "`" + action + "` 被工具拒绝 " + bucket.count + " 次："
                        + trimTrailingStop(bucket.sample)
                        + "。这不是图谱的问题，是 agent 的用法跟规则对不上",
                // 修的是技能不是图谱，所以命令指向文档而不是 schema edit
                "看 skills/sql-cli/references/graph-write.md 对应段落，"
                        + "确认技能里有没有把这条规则说清楚")));
        return out;
    }

    /** 取第一个非空捕获组——三个正则的组数不一样，统一在这里拿。 */
    private static String firstGroup(Matcher matcher) {
        if (!matcher.find()) return null;
        for (int i = 1; i <= matcher.groupCount(); i++) {
            String value = matcher.group(i);
            if (value != null && !value.isBlank()) return value.toLowerCase(Locale.ROOT);
        }
        return null;
    }

    /** 错误原文常自带句号，拼接时去掉，免得出现「重试。。这不是」。 */
    private static String trimTrailingStop(String text) {
        String trimmed = text == null ? "" : text.trim();
        return trimmed.endsWith("。") || trimmed.endsWith(".")
                ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /** `db.table` → `table`：搜索用短名命中率更高。 */
    private static String shortName(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot < 0 ? qualified : qualified.substring(dot + 1);
    }

    /** 一类错误的聚合：撞了几次 + 一条样例。 */
    private static final class Bucket {
        private final String key;
        private int count;
        private String sample;

        Bucket(String key) {
            this.key = key;
        }

        void add(RunStateStore.FailedInteraction failure) {
            count++;
            if (sample == null) {
                String error = failure.errorSummary();
                sample = error.length() > 120 ? error.substring(0, 120) + "…" : error;
            }
        }

        @Override
        public String toString() {
            return key + " x" + count;
        }
    }
}
