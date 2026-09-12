package com.sqlcli.graph.workspace;

import java.util.Locale;
import java.util.Set;

/**
 * DB-P3-006：系统 schema 识别与 LIKE pattern 转义的唯一实现。
 *
 * <p>此前同一套规则散在 {@link WorkspaceMetadataProvider} 的默认方法、
 * {@link WorkspaceMetadataExtractor} 的覆写和 ClickHouse provider 的常量里，
 * 三处清单各自演化已经不一致（Oracle 的清单就漂移了）。新数据库只改这里。
 */
public final class SystemSchemas {

    private static final Set<String> MYSQL = Set.of(
            "information_schema", "mysql", "performance_schema", "sys");

    private static final Set<String> ORACLE = Set.of(
            "sys", "system", "sysaux", "dbsnmp", "sysman", "outln",
            "ctxsys", "mdsys", "olapsys", "odm", "odm_mtr", "ordsys", "ordplugins",
            "wmsys", "xdb", "xs$", "exfsys", "lbacsys", "mts_user", "tracesvr",
            "oracle_ocm", "scott", "appqossys", "orddata", "ords_metadata",
            "si_informtn_schema", "dip", "audsys", "dvsys", "dv_acctmgr",
            "dv_owner", "dv_public", "dv_admin", "gsmadmin_internal",
            "gsmcatuser", "gsmuser", "sysbackup", "sysdg", "syskm", "sysrac",
            "remote_scheduler_agent", "perfstat", "spatial_csw_admin_usr",
            "spatial_wfs_admin_usr");

    private static final Set<String> CLICKHOUSE = Set.of("system", "information_schema");

    private SystemSchemas() {
    }

    /**
     * 判断 schema 是否为系统 schema：可以列出但默认不导入图谱。
     * 比较不区分大小写；未知数据库类型一律返回 false。
     */
    public static boolean isSystem(String databaseType, String schemaName) {
        if (schemaName == null || databaseType == null) {
            return false;
        }
        String value = schemaName.toLowerCase(Locale.ROOT);
        return switch (databaseType.toLowerCase(Locale.ROOT)) {
            case "mysql" -> MYSQL.contains(value);
            case "postgresql" -> value.startsWith("pg_") || "information_schema".equals(value);
            case "oracle" -> ORACLE.contains(value)
                    || value.startsWith("apex_") || value.startsWith("flows_") || value.startsWith("ords_");
            case "clickhouse" -> CLICKHOUSE.contains(value);
            default -> false;
        };
    }

    /**
     * 把用户提供的字面量转成可安全放进 LIKE 的 pattern：转义 {@code %}、{@code _} 和转义符本身。
     *
     * @param escapeChar SQL 里 ESCAPE 声明的字符，JDBC 元数据用 {@code DatabaseMetaData.getSearchStringEscape()}，
     *                   通常是反斜杠
     */
    public static String escapeLikePattern(String literal, String escapeChar) {
        if (literal == null || literal.isEmpty() || escapeChar == null || escapeChar.isEmpty()) {
            return literal;
        }
        return literal.replace(escapeChar, escapeChar + escapeChar)
                .replace("%", escapeChar + "%")
                .replace("_", escapeChar + "_");
    }
}
