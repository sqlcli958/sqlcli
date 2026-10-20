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
 * <p>枚举条件使用 {@code when.columnValueMeanings} 校验值和含义；仅有数字的旧规则
 * 输出迁移提示，不作为可直接应用的过滤条件。
 *
 * <pre>
 * - id: soft_delete_filter
 *   category: query_convention
 *   when:
 *     columnNameRegex: "^(del_flag|is_deleted)$"
 *     columnValueIn: ["0"]
 *     columnValueMeanings: {"0": "存在"}
 *   statement:
 *     queryFilter: "{column} = 0"
 * </pre>
 */
public final class QueryConventions {

    public static final String CATEGORY = "query_convention";

    /** 一条适用于某张表的约定：给 Agent 看的过滤条件，以及它出自哪条规则。 */
    public record Hint(String ruleId, String filter, String proposedFilter, String warning) {
    }

    private final List<PolicyRule> rules;
    private final Set<String> waivedRuleTargets;
    private final String loadWarning;

    private QueryConventions(List<PolicyRule> rules, Set<String> waivedRuleTargets) {
        this(rules, waivedRuleTargets, null);
    }

    private QueryConventions(List<PolicyRule> rules, Set<String> waivedRuleTargets, String loadWarning) {
        this.rules = rules;
        this.waivedRuleTargets = waivedRuleTargets;
        this.loadWarning = loadWarning;
    }

    /** 空实例：没有绑定规则集时用它，调用方不用判空。 */
    public static QueryConventions empty() {
        return new QueryConventions(List.of(), Set.of());
    }

    /**
     * 从工作区加载约定规则。
     *
     * <p><b>读不到规则不是错误</b>——多数工作区根本没绑规则集。这条路径挂在 describe /
     * search 上，为了一份可选的提示让查询失败是本末倒置，所以读取失败返回显式待确认提示，不阻断查询。
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
            return new QueryConventions(List.of(), Set.of(), "查询约定读取失败，过滤条件未检查: " + e.getMessage());
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
        return rules.isEmpty() && loadWarning == null;
    }

    /** 这张表命中了哪些约定。没有就是空表。 */
    public List<Hint> forTable(GraphWorkspace workspace, TableWorkspaceNode table) {
        if (table == null) return List.of();
        if (loadWarning != null) return List.of(new Hint(null, null, null, loadWarning));
        List<Hint> hints = new ArrayList<>();
        for (PolicyRule rule : rules) {
            if (waivedRuleTargets.contains(rule.getId() + "#" + table.getId())) continue;
            if (!matchesTable(workspace, rule, table)) continue;
            String template = string(rule.getStatement().get("queryFilter"));
            if (template == null || template.isBlank()) continue;
            for (ColumnWorkspaceNode column : table.getColumns()) {
                if (!matchesColumn(rule, column)) continue;
                String filter = template.replace("{column}", column.getName());
                String warning = meaningWarning(rule, column);
                hints.add(new Hint(rule.getId(), warning == null ? filter : null,
                        warning == null ? null : filter, warning));
            }
        }
        return hints.stream().distinct().toList();
    }

    private static boolean matchesTable(GraphWorkspace workspace, PolicyRule rule, TableWorkspaceNode table) {
        Map<String, Object> when = rule.getWhen();
        if (when.containsKey("tableTagsAny") && strings(when.get("tableTagsAny")).stream()
                .noneMatch(tag -> table.getTags().stream().anyMatch(tag::equalsIgnoreCase))) return false;
        if (when.containsKey("tableTypeAny") && strings(when.get("tableTypeAny")).stream()
                .noneMatch(type -> table.getTableType() != null && type.equals(table.getTableType().name()))) return false;
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
        if (when.containsKey("columnTagsAny") && strings(when.get("columnTagsAny")).stream()
                .noneMatch(tag -> strings(column.getAttributes().get("tags")).stream().anyMatch(tag::equalsIgnoreCase))) return false;
        if (when.containsKey("semanticTypeAny") && strings(when.get("semanticTypeAny")).stream()
                .noneMatch(value -> column.getSemanticType() != null && value.equalsIgnoreCase(column.getSemanticType().name()))) return false;
        Object nameRegex = when.get("columnNameRegex");
        if (nameRegex != null && !Pattern.compile(String.valueOf(nameRegex))
                .matcher(column.getName()).find()) {
            return false;
        }
        return true;
    }

    private static String meaningWarning(PolicyRule rule, ColumnWorkspaceNode column) {
        Object configured = rule.getWhen().get("columnValueMeanings");
        if (!(configured instanceof Map<?, ?> expected)) {
            if (rule.getWhen().containsKey("columnValueIn")) {
                return "旧规则仅声明枚举值，请补充 when.columnValueMeanings 并核对含义后使用";
            }
            return column.getValueHints() != null && column.getValueHints().getEnumValues() != null
                    && !column.getValueHints().getEnumValues().isEmpty()
                    ? "枚举过滤未声明含义，请补充 when.columnValueMeanings 并核对后使用" : null;
        }
        if (column.getAttributes().containsKey(ColumnWorkspaceNode.ATTR_SEMANTIC_BASIS_CHANGES)) {
            return "字段来源已变化，请先复核值域含义";
        }
        List<String> declared = column.getValueHints() == null || column.getValueHints().getEnumValues() == null
                ? List.of() : column.getValueHints().getEnumValues();
        for (Map.Entry<?, ?> entry : expected.entrySet()) {
            String value = String.valueOf(entry.getKey());
            String meaning = String.valueOf(entry.getValue()).trim();
            List<String> meanings = declared.stream().filter(java.util.Objects::nonNull)
                    .filter(item -> item.split("=", 2)[0].trim().equals(value))
                    .map(item -> item.contains("=") ? item.substring(item.indexOf('=') + 1).trim() : "")
                    .distinct().toList();
            if (meanings.size() != 1 || !meanings.get(0).equals(meaning)) {
                return "字段 " + column.getName() + " 需要 " + value + "=" + meaning
                        + "，实际含义为 " + (meanings.isEmpty() ? "未声明" : meanings) + "，请核对后使用";
            }
        }
        return null;
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
