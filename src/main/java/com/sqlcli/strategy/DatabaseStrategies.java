package com.sqlcli.strategy;

import com.sqlcli.config.DatabaseConfig;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据库策略注册表
 */
public final class DatabaseStrategies {
    private static final DatabaseStrategy FALLBACK = new GenericDatabaseStrategy();
    private static final Map<String, DatabaseStrategy> STRATEGIES = new ConcurrentHashMap<>();

    static {
        register(new MySqlDatabaseStrategy());
        register(new OracleDatabaseStrategy());
        register(new PostgreSqlDatabaseStrategy());
        register(new ClickHouseDatabaseStrategy());
        register(new SqliteDatabaseStrategy());
    }

    private DatabaseStrategies() {
    }

    public static void register(DatabaseStrategy strategy) {
        STRATEGIES.put(strategy.type().toLowerCase(Locale.ROOT), strategy);
    }

    public static DatabaseStrategy resolve(DatabaseConfig config) {
        return resolve(config == null ? null : config.getType());
    }

    public static DatabaseStrategy resolve(String dbType) {
        if (dbType == null || dbType.isBlank()) {
            return FALLBACK;
        }
        return STRATEGIES.getOrDefault(dbType.toLowerCase(Locale.ROOT), FALLBACK);
    }
}
