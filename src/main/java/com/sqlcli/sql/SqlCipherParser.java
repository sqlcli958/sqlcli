package com.sqlcli.sql;

import com.sqlcli.connection.QueryExecutionOptions;

/**
 * SQL 密文字段解析与改写器
 */
public interface SqlCipherParser {
    String rewrite(String sql, QueryExecutionOptions options);
}
