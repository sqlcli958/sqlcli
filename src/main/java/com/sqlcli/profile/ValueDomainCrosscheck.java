package com.sqlcli.profile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 值域三源交叉：库里的实际分布 × 字段注释 × 代码枚举。
 *
 * <h2>不是「拿库校验图谱」，是「拿三个来源生成一份可信值域」</h2>
 * 三个来源各有各的短板，单独一个都不够：
 * <ul>
 *   <li><b>库里实际分布</b>——是事实，但不含语义（只知道有 {@code 0} 和 {@code 1}，
 *       不知道哪个是「已付款」）</li>
 *   <li><b>字段注释</b>——有语义，但可能过期、可能不全</li>
 *   <li><b>代码枚举</b>——有语义、通常最新，但 CLI 读不到代码，由 Agent 读完带进来</li>
 * </ul>
 *
 * <p>交叉之后落到四种结论，**每一种都是一个可操作的信号**，见
 * {@code docs/graph-eval.md} §4。其中最容易被忽略、对 Agent 最有用的是第四种
 * （{@link ValueDomainVerdict#dead()}）：这个字段虽然存在但实际没在用，
 * 别拿它做判断——只看 DDL 和注释永远得不出这个结论。
 *
 * <p>纯函数，不碰数据库也不碰图谱：库那一侧由 {@link TableProfiler} 先跑出
 * {@link ColumnProfile}，写回图谱那一侧走审批。这样这段判定逻辑可以脱离两者单独测。
 */
public final class ValueDomainCrosscheck {

    private ValueDomainCrosscheck() {
    }

    /**
     * 从注释里认值域声明。
     *
     * <p>真实注释的写法是乱的，这个正则要同时吃下（都是 `erp_plush_test` 里的真样本）：
     * <pre>
     *   是否扫码 0=免扫码,1=必须扫码
     *   任务状态  0未开始 1一开始 2已完成 3已取消
     *   计划类型(0为线上计划1为线下计划)
     * </pre>
     * 取值是数字或字母，分隔符 {@code = : ：} 或「为」或没有，标签一直读到下一个取值
     * （所以标签里不能有数字——这是它认不出 {@code 1=A1类} 这种的原因，接受）。
     */
    private static final Pattern ENUM_IN_COMMENT = Pattern.compile(
            "([0-9]{1,4}|[A-Za-z]{1,6})\\s*(?:[=:：]|为)?\\s*"
                    + "([\\u4e00-\\u9fa5A-Za-z][^0-9,，;；()（）\\[\\]]*)");

    /**
     * 解析注释里的值域，认不出来返回空。
     *
     * <p>**宁可解析不出来，也不要解析错**——一个解析错的值域会变成一条错的发现，
     * 而人对着一条错的发现做决定，比没有发现更糟。所以这里有两道保守闸门：
     * 至少认出 2 组（1 组多半是普通行文里的误伤），且取值不能重复。
     */
    public static List<String> parseCommentEnum(String comment) {
        if (comment == null || comment.isBlank()) return List.of();
        Matcher matcher = ENUM_IN_COMMENT.matcher(comment);
        Map<String, String> found = new LinkedHashMap<>();
        while (matcher.find()) {
            String value = matcher.group(1).trim();
            String label = matcher.group(2).trim();
            if (label.isEmpty()) continue;
            // 取值重复说明这不是一份值域声明，是把普通文字认成了键值对
            if (found.containsKey(value)) return List.of();
            found.put(value, label);
        }
        if (found.size() < 2) return List.of();
        List<String> result = new ArrayList<>();
        found.forEach((value, label) -> result.add(value + "=" + label));
        return result;
    }


    /**
     * 采样行数低于这个数就不下结论——20 行里出现 3 个值说明不了任何事。
     * package-private 是给 {@link DataQualityProbes} 用的：同一句判断在两处各写一个数字，
     * 迟早分叉成 30 和 50。
     */
    static final int MIN_SAMPLE = 30;

    /**
     * 取值最多只能占非空行的这个比例。枚举的定义就是**同一个值反复出现**，
     * 不重复的东西是 ID 或自由文本。
     */
    private static final double MAX_DISTINCT_RATIO = 0.1;

    /** 枚举取值不会这么长；超过就是 ID、时间戳、IP 或一段文本。 */
    private static final int MAX_VALUE_LENGTH = 20;

    /**
     * 内联值域的取值个数上限。
     *
     * <p>判据不是我定的，是模型自己写的——{@code ColumnValueHints.enumValues} 的注释：
     * 「值多到不该内联的（字典表）不在这里——那是一条指向字典表的关系边」。
     * 取值一多就不是枚举而是字典表引用，或者干脆是序号 / 版本号 / 计数器。
     *
     * <p>真库上漏过网：`sort`（1~17 的排序号）和 `version`（乐观锁版本号）相对基数都够低，
     * 只有这条能拦住它们。代价是一个真有 10 个状态的枚举会被跳过——按上面那句注释，
     * 那种本来就该建字典表而不是内联。
     */
    private static final int MAX_ENUM_SIZE = 8;

    /**
     * 「这一列压根不是枚举」的判据，不是就返回 null。
     *
     * <h2>为什么不能用 {@link ColumnProfile#lowCardinality()}</h2>
     * 那个标志是「distinct &lt; 50」的**绝对**阈值，为另一个问题设计的。
     * 在一张只有 39 行的表上，**每一列**的 distinct 都小于 50——包括 UUID 主键、
     * 计划名称、创建时间。第一次拿它当枚举判据在真库上跑，35 个字段报出 31 个「发现」，
     * 其中 25 个是垃圾（主键、时间戳、IP、逗号拼接的 id 列表）。
     *
     * <p>正确的判据是**相对**的：枚举的定义是同一个值反复出现，所以
     * {@code distinct / nonNull} 必须够小。这个比值天然随表的规模缩放，
     * 小表上收得紧（39 行只认 ≤3 个取值），大表上放得开。
     */
    private static String notEnumReason(ColumnProfile profile) {
        // 门槛按**采样行数**判，不是非空行数：采了 1000 行全是 NULL 是一个确定的发现
        // （这个字段没在用），而采了 10 行说明不了任何事
        if (profile.rowsSampled() < MIN_SAMPLE) {
            return "样本太少（采样 " + profile.rowsSampled() + " 行），不下结论";
        }
        if (profile.nonNullCount() == 0) {
            return null; // 全 NULL 交给下面的 dead 判定，它是有效发现不是「不是枚举」
        }
        if (profile.distinctCount() > profile.nonNullCount() * MAX_DISTINCT_RATIO) {
            return "取值几乎不重复（" + profile.distinctCount() + "/" + profile.nonNullCount()
                    + "），是 ID 或自由文本，不是枚举";
        }
        if (profile.distinctCount() > MAX_ENUM_SIZE) {
            return "取值过多（" + profile.distinctCount()
                    + " 个），是字典表引用或序号 / 版本号，不该内联成值域";
        }
        for (String value : profile.enumValues()) {
            if (value != null && value.length() > MAX_VALUE_LENGTH) {
                return "取值过长（如 " + value.substring(0, Math.min(24, value.length()))
                        + "…），是 ID 或文本，不是枚举";
            }
        }
        return null;
    }

    /** `0=待付款` → `0`；没有 `=` 就是取值本身。 */
    public static String valueOf(String enumItem) {
        int split = enumItem.indexOf('=');
        return (split < 0 ? enumItem : enumItem.substring(0, split)).trim();
    }

    /**
     * 交叉三源，产出一条判定。
     *
     * @param profile    库里的实际分布（{@link TableProfiler} 的产出）
     * @param comment    字段注释原文，可为 null
     * @param codeEnum   代码里的枚举，Agent 读完代码带进来；没有传空
     * @param declared   图谱里已有的值域（`valueHints.enumValues`），用来判断有没有变化
     */
    public static ValueDomainVerdict cross(ColumnProfile profile, String comment,
            List<String> codeEnum, List<String> declared) {
        return cross(profile, comment, codeEnum, declared, false);
    }

    /**
     * @param asserted 调用方**点名**了这一列（`--column`）。这时下面那组「这是不是枚举」的
     *                 启发式不再拦截——它们是给整表扫描防噪音用的，而点名意味着调用方
     *                 已经回答了「它是不是枚举」这个问题。继续拦就是跟调用方对着干：
     *                 真库上撞见过，agent 点名 `task_type` 并给出 6 个取值的代码枚举，
     *                 却因为 6/39 超过相对基数阈值被判「不是枚举」。
     */
    public static ValueDomainVerdict cross(ColumnProfile profile, String comment,
            List<String> codeEnum, List<String> declared, boolean asserted) {
        if (profile.skipped()) {
            return ValueDomainVerdict.skipped(profile.columnName(), profile.skipReason());
        }
        if (!asserted) {
            String notEnum = notEnumReason(profile);
            if (notEnum != null) {
                return ValueDomainVerdict.skipped(profile.columnName(), notEnum);
            }
        }

        // 语义有三个来源，库只提供「有哪些值」这个事实。按可信度从低到高叠加，后面的覆盖前面的：
        //
        //   图谱已有 → 注释 → 代码
        //
        // **图谱已有的必须先放进来**，它是人批准过的标签。漏掉它的后果在真库上撞见过：
        // is_scan 的注释只写了「是否扫码」没声明值域，而图谱里已经有人写好了
        // `0=免扫码,1=必须扫码`——不把它算作语义源，提议就变成把标签抹成裸的 `0`/`1`，
        // 批准一下就毁掉了人工整理的成果。
        Map<String, String> labels = new LinkedHashMap<>();
        for (String item : declared == null ? List.<String>of() : declared) {
            labels.put(valueOf(item), item);
        }
        for (String item : parseCommentEnum(comment)) labels.put(valueOf(item), item);
        // 代码枚举最后放：同一个取值上代码通常比注释和图谱都新
        for (String item : codeEnum == null ? List.<String>of() : codeEnum) {
            labels.put(valueOf(item), item);
        }

        Set<String> inDatabase = new LinkedHashSet<>();
        // 空串不是一个取值：把它写进值域会让 Agent 以为 col = '' 是个有意义的筛选条件
        for (String value : profile.enumValues()) {
            if (value != null && !value.isBlank()) inDatabase.add(value);
        }
        Set<String> onlyInDatabase = new LinkedHashSet<>(inDatabase);
        onlyInDatabase.removeAll(labels.keySet());
        Set<String> onlyInDeclared = new LinkedHashSet<>(labels.keySet());
        onlyInDeclared.removeAll(inDatabase);

        // 建议值域 = 库里真实出现的值，能配上语义的带上语义。
        // 以库为准而不是以注释为准：注释里写了但库里从没出现的值，
        // 放进值域会让 Agent 以为那是个可用的筛选条件。
        List<String> proposed = new ArrayList<>();
        for (String value : inDatabase) {
            proposed.add(labels.getOrDefault(value, value));
        }

        boolean dead = profile.nonNullCount() == 0
                || (profile.distinctCount() == 1 && profile.nullRatio() < 0.5);

        return new ValueDomainVerdict(profile.columnName(), false, null,
                proposed, List.copyOf(onlyInDatabase), List.copyOf(onlyInDeclared),
                labels.isEmpty(), dead, profile.nullRatio(), profile.distinctCount(),
                sameValues(proposed, declared));
    }

    /** 只比取值，不比标签：标签改个措辞不该算成一次值域变化。 */
    private static boolean sameValues(List<String> proposed, List<String> declared) {
        if (declared == null) return proposed.isEmpty();
        Set<String> a = new LinkedHashSet<>();
        proposed.forEach(item -> a.add(valueOf(item)));
        Set<String> b = new LinkedHashSet<>();
        declared.forEach(item -> b.add(valueOf(item)));
        return a.equals(b);
    }
}
