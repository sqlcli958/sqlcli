package com.sqlcli.task;

import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.strategy.DatabaseCapabilities;

import java.util.Set;

final class SqlTaskUtil {
    private static final Set<String> WRITE_TYPES = Set.of(
            "INSERT", "UPDATE", "DELETE", "MERGE", "CREATE", "ALTER", "DROP", "TRUNCATE", "GRANT", "REVOKE");

    private SqlTaskUtil() {
    }

    static boolean isWriteOperation(String sqlType) {
        return WRITE_TYPES.contains(sqlType);
    }

    static boolean isReadonlyAlias(DatabaseConfig config) {
        return Boolean.TRUE.equals(config.getReadonly());
    }

    static boolean isYearning(DatabaseConfig config) {
        return config != null && "yearning".equalsIgnoreCase(config.getAccessMode());
    }

    /** 有的数据库/驱动的 affected-rows 不可信（比如 ClickHouse），这种情况下报 null 而不是骗一个数字。 */
    static Integer reliableAffectedRows(DatabaseCapabilities capabilities, int affected) {
        return capabilities.isAffectedRowsReliable() && affected >= 0 ? affected : null;
    }
}
