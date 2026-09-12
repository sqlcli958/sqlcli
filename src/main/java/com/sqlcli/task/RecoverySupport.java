package com.sqlcli.task;

import com.sqlcli.config.DatabaseConfig;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 主键探测，被 {@link PrecheckStage}（判断能不能生成恢复 SQL）和 {@link JdbcBackend}
 * （真正生成恢复 SQL 前的强制校验）共用——同一件事只写一遍。
 */
final class RecoverySupport {

    private RecoverySupport() {
    }

    static List<String> primaryKeyColumns(Connection connection, String qualifiedTableName,
                                          DatabaseConfig config) throws SQLException {
        int separator = qualifiedTableName.lastIndexOf('.');
        String explicitSchema = separator < 0 ? null : unquote(qualifiedTableName.substring(0, separator));
        String table = unquote(separator < 0 ? qualifiedTableName : qualifiedTableName.substring(separator + 1));
        DatabaseMetaData metadata = connection.getMetaData();
        String catalog = safeCatalog(connection);
        String schema = explicitSchema == null ? safeSchema(connection) : explicitSchema;
        if ("mysql".equalsIgnoreCase(config.getType())) {
            // 主键要在语句真正执行的那个库里找：连接当前库（URL 里的那个）优先，别名的 database
            // 字段只是兜底——用 url 配的别名根本不填它，填了也可能和 URL 不一致。
            // Connector/J 对「这个库里没这张表」静默返回空而不报错（1146/42S02 被驱动吞掉），
            // 查错库的结果看起来就是「表没有主键」，审批通过后 UPDATE 照样被拒。
            catalog = explicitSchema != null ? explicitSchema : catalog != null ? catalog : config.getDatabase();
            schema = null;
        } else if ("oracle".equalsIgnoreCase(config.getType()) && schema == null) {
            schema = config.getUsername();
        }

        List<String> columns = readPrimaryKeys(metadata, catalog, schema, table);
        if (columns.isEmpty() && !table.equals(table.toUpperCase(Locale.ROOT))) {
            columns = readPrimaryKeys(metadata, catalog, schema, table.toUpperCase(Locale.ROOT));
        }
        if (columns.isEmpty() && !table.equals(table.toLowerCase(Locale.ROOT))) {
            columns = readPrimaryKeys(metadata, catalog, schema, table.toLowerCase(Locale.ROOT));
        }
        return columns;
    }

    private static List<String> readPrimaryKeys(DatabaseMetaData metadata, String catalog,
                                                 String schema, String table) throws SQLException {
        record Key(short sequence, String column) {
        }
        List<Key> keys = new ArrayList<>();
        try (ResultSet resultSet = metadata.getPrimaryKeys(catalog, schema, table)) {
            while (resultSet.next()) {
                String column = resultSet.getString("COLUMN_NAME");
                if (column != null && !column.isBlank()) {
                    keys.add(new Key(resultSet.getShort("KEY_SEQ"), column));
                }
            }
        }
        keys.sort(Comparator.comparingInt(Key::sequence));
        return keys.stream().map(Key::column).toList();
    }

    private static String safeCatalog(Connection connection) {
        try {
            return connection.getCatalog();
        } catch (SQLException ignored) {
            return null;
        }
    }

    private static String safeSchema(Connection connection) {
        try {
            return connection.getSchema();
        } catch (SQLException | AbstractMethodError ignored) {
            return null;
        }
    }

    private static String unquote(String identifier) {
        String value = identifier == null ? null : identifier.trim();
        if (value != null && value.length() >= 2
                && (value.startsWith("\"") && value.endsWith("\"")
                || value.startsWith("`") && value.endsWith("`"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
