package com.sqlcli.parser;

/**
 * SQL解析异常
 */
public class SqlParseException extends Exception {
    private final String originalSql;

    public SqlParseException(String message, String originalSql) {
        super(message);
        this.originalSql = originalSql;
    }

    public SqlParseException(String message, String originalSql, Throwable cause) {
        super(message, cause);
        this.originalSql = originalSql;
    }

    public String getOriginalSql() {
        return originalSql;
    }
}