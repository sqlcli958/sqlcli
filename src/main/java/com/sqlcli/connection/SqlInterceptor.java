package com.sqlcli.connection;

import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.strategy.DatabaseStrategies;

/**
 * SQL 预处理器
 */
public class SqlInterceptor {

    public String preprocess(DatabaseConfig config, String sql) {
        return DatabaseStrategies.resolve(config).preprocessSql(config, sql);
    }
}
