package com.sqlcli.graph.workspace;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public final class GraphIds {
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT);

    private GraphIds() {
    }

    public static String schemaId(String alias, String schema) {
        return "schema:" + alias + ":" + schema;
    }

    public static String tableId(String alias, String schema, String table) {
        return "table:" + alias + ":" + schema + "." + table;
    }

    public static String columnId(String alias, String schema, String table, String column) {
        return "column:" + alias + ":" + schema + "." + table + "." + column;
    }

    public static String relationId(String alias, RelationType type, String from, String to) {
        return "relation:" + alias + ":" + type + ":" + sanitizeRef(from) + "->" + sanitizeRef(to);
    }

    /**
     * 血缘 id 由 (target, through) 决定：一段代码写一列只有一条记录。
     * 原来把 sources / expression 也哈希进去，结果改一个字的表达式就是一条新记录、旧的留着，
     * 「补类别」变成「再生成一条」。现在改 sources / expression 是更新，不是新增。
     */
    public static String lineageId(String alias, String target, String through) {
        return "lineage:" + alias + ":" + sanitizeRef(target) + ":" + shortHash(through == null ? "" : through);
    }

    private static String shortHash(String content) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 4);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 指标 id 由 name 决定（同 {@link TermWorkspaceNode} 的做法），不是内容哈希：
     * name 就是指标的稳定标识，同名重写是有意的幂等 upsert，不应该因为改了一个字就变成新对象。
     */
    public static String metricId(String alias, String name) {
        return "metric:" + alias + ":" + name;
    }

    public static String changeId(String alias) {
        return "change:" + alias + ":" + DATE_FMT.format(LocalDateTime.now()) + ":" + SEQ.incrementAndGet();
    }

    public static String validationIssueId(String alias) {
        return "issue:" + alias + ":" + DATE_FMT.format(LocalDateTime.now()) + ":" + SEQ.incrementAndGet();
    }

    private static String sanitizeRef(String value) {
        return value.replace(':', '_').replace('/', '_').replace('\\', '_').replace(' ', '_');
    }
}
