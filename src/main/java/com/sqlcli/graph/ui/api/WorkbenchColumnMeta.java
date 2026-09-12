package com.sqlcli.graph.ui.api;

import com.sqlcli.graph.workspace.ColumnWorkspaceNode;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectItem;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 结果列名 → 图谱列元数据（业务名 / 语义类型 / 值域）的映射。
 *
 * <p><b>映射规则（拍板过的，别放宽）：只在能百分百确定来源时标注，对不上就裸列名，绝不猜。</b>
 * <ul>
 *   <li>SELECT 必须能解析出<b>单一表</b>：无 JOIN、无 WITH、FROM 不是子查询；
 *       裸表名在图谱多个 schema 里同名时也放弃——挑一个就是猜。</li>
 *   <li>列名必须是<b>不带 AS 别名的裸列</b>：{@code SELECT phone AS account} 的结果列
 *       "account" 不会拿到图谱里 account 列的含义——标错比不标糟糕得多。
 *       {@code SELECT *}（或 {@code t.*}）的结果列全部来自这张表，整体可匹配。</li>
 *   <li>匹配忽略大小写（{@link TableWorkspaceNode#findColumn} 的既有语义）。</li>
 * </ul>
 *
 * <p>图谱是只读输入：调用方负责 exists 检查，这里绝不触发任何导入。
 */
final class WorkbenchColumnMeta {

    private WorkbenchColumnMeta() {
    }

    /**
     * @param sql         用户提交的原始 SQL（改写前——SM4/方言改写不动表结构）
     * @param workspace   已加载的图谱
     * @param columnNames 结果集的列标签（JDBC getColumnLabel）
     * @return 列名 → 元数据；解析不出单一表、或没有任何列命中时为空 map
     */
    static Map<String, Map<String, Object>> resolve(String sql, GraphWorkspace workspace,
                                                    List<String> columnNames) {
        PlainSelect plain = singleTableSelect(sql);
        if (plain == null) {
            return Map.of();
        }
        TableWorkspaceNode table = findUniqueTable(workspace,
                ((Table) plain.getFromItem()).getFullyQualifiedName());
        if (table == null) {
            return Map.of();
        }

        List<SelectItem<?>> items = plain.getSelectItems();
        boolean star = items.size() == 1 && items.get(0).getExpression() instanceof AllColumns;
        // 非 * 时只信没带别名的裸列引用；表达式和别名列一律不标
        Set<String> bareNames = new HashSet<>();
        if (!star) {
            for (SelectItem<?> item : items) {
                if (item.getAlias() == null && item.getExpression() instanceof Column column) {
                    bareNames.add(unquote(column.getColumnName()).toLowerCase(Locale.ROOT));
                }
            }
        }

        Map<String, Map<String, Object>> meta = new LinkedHashMap<>();
        for (String name : columnNames) {
            if (!star && !bareNames.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            ColumnWorkspaceNode column = table.findColumn(name);
            if (column == null) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            if (column.getBusinessName() != null && !column.getBusinessName().isBlank()) {
                entry.put("businessName", column.getBusinessName());
            }
            if (column.getSemanticType() != null) {
                entry.put("semanticType", column.getSemanticType().name());
            }
            if (column.getValueHints() != null && column.getValueHints().getEnumValues() != null
                    && !column.getValueHints().getEnumValues().isEmpty()) {
                entry.put("enumValues", List.copyOf(column.getValueHints().getEnumValues()));
            }
            if (!entry.isEmpty()) {
                meta.put(name, entry);
            }
        }
        return meta;
    }

    /** 无 JOIN、无 WITH、FROM 是一张裸表的 SELECT；不是就返回 null。 */
    private static PlainSelect singleTableSelect(String sql) {
        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(sql);
        } catch (Exception e) {
            return null;
        }
        if (!(statement instanceof PlainSelect plain)) {
            return null;
        }
        if (plain.getWithItemsList() != null && !plain.getWithItemsList().isEmpty()) {
            return null;
        }
        if (plain.getJoins() != null && !plain.getJoins().isEmpty()) {
            return null;
        }
        if (!(plain.getFromItem() instanceof Table)) {
            return null;
        }
        return plain;
    }

    /** 带 schema 前缀按 qualifiedName 找；裸表名跨 schema 同名多于一个时返回 null——不猜。 */
    private static TableWorkspaceNode findUniqueTable(GraphWorkspace workspace, String rawName) {
        String name = unquote(rawName);
        boolean qualified = name.indexOf('.') >= 0;
        TableWorkspaceNode match = null;
        for (TableWorkspaceNode table : workspace.getTables().values()) {
            boolean hit = qualified
                    ? name.equalsIgnoreCase(table.getQualifiedName())
                    : name.equalsIgnoreCase(table.getName());
            if (hit) {
                if (match != null) {
                    return null;
                }
                match = table;
            }
        }
        return match;
    }

    private static String unquote(String identifier) {
        return identifier == null ? "" : identifier.replace("`", "").replace("\"", "")
                .replace("[", "").replace("]", "");
    }
}
