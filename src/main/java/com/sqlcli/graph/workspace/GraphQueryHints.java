package com.sqlcli.graph.workspace;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用图谱数据给 Agent 的三类提示：报错标识符纠错、空结果值域对照、相近名建议。
 *
 * <p>共同点：信息本来就在图谱里，只是没在 Agent 需要的那一刻递出去。全部是"提示"级输出——
 * 匹配不到就安静返回 null，绝不让提示逻辑本身抛错影响主流程。
 */
public final class GraphQueryHints {

    /** 报错消息里能抠出列名的模式（MySQL / PostgreSQL / Oracle）。 */
    private static final List<Pattern> COLUMN_ERROR_PATTERNS = List.of(
            Pattern.compile("Unknown column '([^']+)'"),
            Pattern.compile("column \"([^\"]+)\" does not exist"),
            Pattern.compile("ORA-00904: \"(?:[^\"]+\"\\.\")?([^\"]+)\": invalid identifier"));

    /** 报错消息里能抠出表名的模式。 */
    private static final List<Pattern> TABLE_ERROR_PATTERNS = List.of(
            Pattern.compile("Table '([^']+)' doesn't exist"),
            Pattern.compile("relation \"([^\"]+)\" does not exist"));

    /** ORA-00942 不带表名，只能回头从 SQL 里找表 token 逐个对图谱。 */
    private static final Pattern ORA_TABLE_NOT_EXIST = Pattern.compile("ORA-00942");

    /** SQL 里跟在 FROM/JOIN/UPDATE/INTO 后面的表 token。ponytail: 正则不懂子查询别名，误报只是多一条提示。 */
    private static final Pattern SQL_TABLE_TOKENS =
            Pattern.compile("(?i)(?:\\bfrom|\\bjoin|\\bupdate|\\binto)\\s+([A-Za-z0-9_$.]+)");

    /** WHERE 里的等值比较：col = 'literal' 或 col = 123。ponytail: 同上，提示级精度够用。 */
    private static final Pattern EQUALS_LITERAL =
            Pattern.compile("([A-Za-z0-9_$.]+)\\s*=\\s*(?:'([^']*)'|(\\d+(?:\\.\\d+)?))");

    private GraphQueryHints() {
    }

    /** 报错是不是「列/表不存在」这一类——是才值得去加载图谱。 */
    public static boolean looksLikeUnknownIdentifier(String message) {
        if (message == null) {
            return false;
        }
        for (Pattern p : COLUMN_ERROR_PATTERNS) {
            if (p.matcher(message).find()) return true;
        }
        for (Pattern p : TABLE_ERROR_PATTERNS) {
            if (p.matcher(message).find()) return true;
        }
        return ORA_TABLE_NOT_EXIST.matcher(message).find();
    }

    /**
     * 对「列/表不存在」类报错生成纠错提示。匹配不出标识符或图谱里没有相近对象时返回 null。
     */
    public static String suggestForError(String message, String sql, GraphWorkspace workspace) {
        if (message == null || workspace == null) {
            return null;
        }
        for (Pattern p : COLUMN_ERROR_PATTERNS) {
            Matcher m = p.matcher(message);
            if (m.find()) {
                String column = lastSegment(m.group(1));
                List<String> similar = similarColumns(workspace, column, 3);
                return similar.isEmpty() ? null
                        : "提示: 列 '" + column + "' 在图谱中未找到，相近的列: " + String.join(", ", similar);
            }
        }
        for (Pattern p : TABLE_ERROR_PATTERNS) {
            Matcher m = p.matcher(message);
            if (m.find()) {
                String table = lastSegment(m.group(1));
                List<String> similar = similarTables(workspace, table, 3);
                return similar.isEmpty() ? null
                        : "提示: 表 '" + table + "' 在图谱中未找到，相近的表: " + String.join(", ", similar);
            }
        }
        if (ORA_TABLE_NOT_EXIST.matcher(message).find() && sql != null) {
            Matcher m = SQL_TABLE_TOKENS.matcher(sql);
            while (m.find()) {
                String token = m.group(1);
                if (workspace.getTableByQualifiedName(token) != null) {
                    continue; // 这个表在图谱里，不是它拼错了
                }
                List<String> similar = similarTables(workspace, lastSegment(token), 3);
                if (!similar.isEmpty()) {
                    return "提示: 表 '" + token + "' 在图谱中未找到，相近的表: " + String.join(", ", similar);
                }
            }
        }
        return null;
    }

    /**
     * SELECT 查回 0 行时，对照 WHERE 等值比较与图谱值域：值不在已知值域内 → 提示。
     * 典型场景：状态列写了中文而库里存数字。
     */
    public static String emptyResultHint(String sql, GraphWorkspace workspace) {
        if (sql == null || workspace == null) {
            return null;
        }
        Matcher m = EQUALS_LITERAL.matcher(sql);
        while (m.find()) {
            String columnName = lastSegment(m.group(1));
            String value = m.group(2) != null ? m.group(2) : m.group(3);
            for (TableWorkspaceNode table : workspace.getTables().values()) {
                ColumnWorkspaceNode column = table.findColumn(columnName);
                if (column == null || column.getValueHints() == null) {
                    continue;
                }
                List<String> enums = column.getValueHints().getEnumValues();
                if (enums == null || enums.isEmpty() || containsIgnoreCase(enums, value)) {
                    continue;
                }
                return "提示: " + table.getQualifiedName() + "." + columnName + " = '" + value
                        + "' 不在图谱记录的值域内，已知值域: " + String.join(", ", enums);
            }
        }
        return null;
    }

    /** 图谱里与给定名字相近的表（qualified name），按相似度排序。 */
    public static List<String> similarTables(GraphWorkspace workspace, String name, int limit) {
        List<Scored> scored = new ArrayList<>();
        for (TableWorkspaceNode table : workspace.getTables().values()) {
            Integer score = similarity(name, table.getName());
            if (score != null) {
                scored.add(new Scored(table.getQualifiedName(), score));
            }
        }
        return top(scored, limit);
    }

    /** 图谱里与给定名字相近的列（table.column），按相似度排序。 */
    public static List<String> similarColumns(GraphWorkspace workspace, String name, int limit) {
        List<Scored> scored = new ArrayList<>();
        for (TableWorkspaceNode table : workspace.getTables().values()) {
            for (ColumnWorkspaceNode column : table.getColumns()) {
                Integer score = similarity(name, column.getName());
                if (score != null) {
                    scored.add(new Scored(table.getQualifiedName() + "." + column.getName(), score));
                }
            }
        }
        return top(scored, limit);
    }

    // ---------------------------------------------------------------- internals

    private record Scored(String name, int score) {
    }

    private static List<String> top(List<Scored> scored, int limit) {
        scored.sort((a, b) -> Integer.compare(a.score, b.score));
        Set<String> out = new LinkedHashSet<>();
        for (Scored s : scored) {
            out.add(s.name);
            if (out.size() >= limit) break;
        }
        return new ArrayList<>(out);
    }

    /**
     * 相似度：0=大小写不同的同名，1=包含关系，2+=编辑距离（阈值 长度/3，至少 1）。
     * 不相近返回 null。
     */
    private static Integer similarity(String input, String candidate) {
        if (input == null || candidate == null) {
            return null;
        }
        String a = input.toLowerCase(Locale.ROOT);
        String b = candidate.toLowerCase(Locale.ROOT);
        if (a.equals(b)) {
            return 0;
        }
        if (a.length() >= 3 && (b.contains(a) || a.contains(b))) {
            return 1;
        }
        // 4 字符以上至少容 2 个编辑（ordr→orders 就是掉字母+少复数），太短的名字只容 1 防噪音
        int threshold = a.length() >= 4 ? Math.max(2, a.length() / 3) : 1;
        int distance = levenshtein(a, b, threshold);
        return distance <= threshold ? 1 + distance : null;
    }

    /** 经典 DP，超过 threshold 提前放弃（返回 threshold+1）。 */
    private static int levenshtein(String a, String b, int threshold) {
        if (Math.abs(a.length() - b.length()) > threshold) {
            return threshold + 1;
        }
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            int rowMin = curr[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, curr[j]);
            }
            if (rowMin > threshold) {
                return threshold + 1;
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }

    private static String lastSegment(String identifier) {
        int dot = identifier.lastIndexOf('.');
        return dot < 0 ? identifier : identifier.substring(dot + 1);
    }

    /** 值域项形如 {@code 0=待付款}（值=含义），比对时只取 '=' 前的值部分。 */
    private static boolean containsIgnoreCase(List<String> values, String target) {
        for (String v : values) {
            if (v == null) continue;
            int eq = v.indexOf('=');
            String bare = eq < 0 ? v : v.substring(0, eq);
            if (bare.trim().equalsIgnoreCase(target)) return true;
        }
        return false;
    }
}
