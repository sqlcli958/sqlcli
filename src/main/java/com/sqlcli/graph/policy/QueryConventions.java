package com.sqlcli.graph.policy;

import com.sqlcli.graph.workspace.ColumnWorkspaceNode;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.TableWorkspaceNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 全库查询约定：{@code category: query_convention} 的规则，产出给 Agent 的过滤提示。
 *
 * <h2>为什么是规则，不是每张表一个字段</h2>
 * {@code del_flag = 0} 这类条件换任何场景都成立，全库几乎每张表都有。
 * 给 531 张表各存一份 {@code defaultFilter} 是 531 份重复：新表进来没人补，
 * 改一次要改 531 处。一条规则说完，新表自动适用。
 *
 * <h2>这是 policy 的第二个消费者，输出形态不同</h2>
 * 现有规则全是**断言**（「这必须成立」），产出 {@link PolicyViolation} 给人看。
 * 约定规则产出的是**注入查询上下文的提示**，消费者是 Agent，不进违规列表。
 *
 * <h2>为什么必须按列名 + 值域匹配，不能无条件套用</h2>
 * 软删除的写法全库并不统一：有的用 {@code del_flag}、有的 {@code is_deleted}、
 * 有的 {@code deleted_at IS NULL}，**值也不统一**。真库上的实证：
 * {@code erp_prop_report.del_flag} 注释写「0代表存在 2代表删除」，
 * 而采样结果是「库里存在未声明的值 1；声明了但库里从未出现 2」。
 * 一条无条件注入 {@code del_flag = 0} 的规则在这张表上**恰好蒙对**，
 * 但只要有一张表用 {@code del_flag = 1} 表示存在，同一条规则就会静默筛掉全部数据。
 *
 * <p>所以 {@code when.columnValueIn} 是必要的闸门：值域里没有那个值就不匹配，
 * 宁可不给提示，也不给一条错的。
 *
 * <pre>
 * - id: soft_delete_filter
 *   category: query_convention
 *   when:
 *     columnNameRegex: "^(del_flag|is_deleted)$"
 *     columnValueIn: ["0"]
 *   statement:
 *     queryFilter: "{column} = 0"
 * </pre>
 */
public final class QueryConventions {

    public static final String CATEGORY = "query_convention";

    /** 一条适用于某张表的约定：给 Agent 看的过滤条件，以及它出自哪条规则。 */
    public record Hint(String ruleId, String filter) {
    }

    private final List<PolicyRule> rules;
    private final Set<String> waivedRuleTargets;

    private QueryConventions(List<PolicyRule> rules, Set<String> waivedRuleTargets) {
        this.rules = rules;
        this.waivedRuleTargets = waivedRuleTargets;
    }

    /** 空实例：没有绑定规则集、或读取失败时用它，调用方不用判空。 */
    public static QueryConventions empty() {
        return new QueryConventions(List.of(), Set.of());
    }

    /**
     * 从工作区加载约定规则。
     *
     * <p><b>读不到规则不是错误</b>——多数工作区根本没绑规则集。这条路径挂在 describe /
     * search 上，为了一份可选的提示让查询失败是本末倒置，所以任何异常都退化成空实例。
     */
    public static QueryConventions load(Path workspaceRoot, List<RuleWaiver> waivers) {
        List<PolicyRule> found = new ArrayList<>();
        try {
            RuleSetLoader loader = new RuleSetLoader();
            for (Path path : loader.boundRulePaths(workspaceRoot)) {
                RuleSet ruleSet = loader.load(path);
                for (PolicyRule rule : ruleSet.getRules()) {
                    if (CATEGORY.equalsIgnoreCase(rule.getCategory())) found.add(rule);
                }
            }
        } catch (Exception e) {
            return empty();
        }
        Set<String> waived = new LinkedHashSet<>();
        for (RuleWaiver waiver : waivers == null ? List.<RuleWaiver>of() : waivers) {
            if (waiver.getStatus() == RuleWaiverStatus.active) {
                waived.add(waiver.getRuleId() + "#" + waiver.getTargetId());
            }
        }
        return new QueryConventions(found, waived);
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /** 这张表命中了哪些约定。没有就是空表。 */
    public List<Hint> forTable(GraphWorkspace workspace, TableWorkspaceNode table) {
        if (rules.isEmpty() || table == null) return List.of();
        List<Hint> hints = new ArrayList<>();
        for (PolicyRule rule : rules) {
            if (waivedRuleTargets.contains(rule.getId() + "#" + table.getId())) continue;
            if (!matchesTable(workspace, rule, table)) continue;
            String template = string(rule.getStatement().get("queryFilter"));
            if (template == null || template.isBlank()) continue;
            for (ColumnWorkspaceNode column : table.getColumns()) {
                if (!matchesColumn(rule, column)) continue;
                hints.add(new Hint(rule.getId(), template.replace("{column}", column.getName())));
                break;  // 一条规则在一张表上只给一次提示，命中第一列即可
            }
        }
        return List.copyOf(hints);
    }

    private static boolean matchesTable(GraphWorkspace workspace, PolicyRule rule, TableWorkspaceNode table) {
        Map<String, Object> when = rule.getWhen();
        Object dbTypes = when.get("dbTypeAny");
        if (dbTypes != null && strings(dbTypes).stream()
                .noneMatch(value -> value.equalsIgnoreCase(workspace.getDataSource().getDbType()))) {
            return false;
        }
        Object nameRegex = when.get("tableNameRegex");
        return nameRegex == null
                || Pattern.compile(String.valueOf(nameRegex)).matcher(table.getName()).find();
    }

    private static boolean matchesColumn(PolicyRule rule, ColumnWorkspaceNode column) {
        Map<String, Object> when = rule.getWhen();
        Object nameRegex = when.get("columnNameRegex");
        if (nameRegex != null && !Pattern.compile(String.valueOf(nameRegex))
                .matcher(column.getName()).find()) {
            return false;
        }
        Object valueIn = when.get("columnValueIn");
        if (valueIn == null) return true;
        // 值域没标注过就不匹配——**宁可不给提示，也不给一条错的**。
        // 提示是要被 Agent 直接写进 WHERE 的，错一次就是静默筛掉全部数据。
        if (column.getValueHints() == null || column.getValueHints().getEnumValues() == null) return false;
        Set<String> declared = new LinkedHashSet<>();
        for (String item : column.getValueHints().getEnumValues()) {
            int split = item.indexOf('=');
            declared.add((split < 0 ? item : item.substring(0, split)).trim());
        }
        return declared.containsAll(strings(valueIn));
    }

    private static List<String> strings(Object value) {
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) if (item != null) out.add(String.valueOf(item));
            return out;
        }
        return value == null ? List.of() : List.of(String.valueOf(value));
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
