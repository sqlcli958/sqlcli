package com.sqlcli.yearning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.parser.SqlParseException;
import com.sqlcli.parser.SqlStatementAnalyzer;
import com.sqlcli.secret.SecretResolver;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.DescribeStatement;
import net.sf.jsqlparser.statement.ExplainStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class YearningQueryExecutor {
    private final SecretResolver secretResolver = new SecretResolver();
    private final YearningClient client = new YearningClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final SqlStatementAnalyzer statementAnalyzer = new SqlStatementAnalyzer();

    /**
     * 结构化查询，供 {@code SqlTaskModule} 的 {@code YearningBackend} 调用。
     *
     * <p>只读校验、database 限定、SQL 改写全部还在这里做——{@code GuardStage} 只挡
     * 写语句，剩下这些是 Yearning 通道特有的规则，不该因为搬进 Module 就散到别处去。
     */
    public YearningQueryResult query(DatabaseConfig config, String sql) {
        ensureReadOnlyQuery(sql);
        try {
            YearningConfig baseConfig = YearningConfig.from(config, secretResolver);
            YearningConfig effectiveConfig = resolveConfigForSql(baseConfig, sql);
            return client.query(effectiveConfig, qualifySql(sql, effectiveConfig));
        } catch (Exception e) {
            throw new RuntimeException("Yearning query failed: " + e.getMessage(), e);
        }
    }

    public boolean test(DatabaseConfig config) {
        try {
            YearningConfig yearningConfig = YearningConfig.from(config, secretResolver);
            return client.test(yearningConfig);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public String getTableDdl(DatabaseConfig config, String tableName, String schemaName) {
        try {
            YearningConfig baseConfig = YearningConfig.from(config, secretResolver);
            TableTarget target = resolveTableTarget(baseConfig, tableName, schemaName);
            String sql = "SHOW CREATE TABLE " + target.config().getDatabase() + "." + target.table();
            YearningQueryResult result = client.query(target.config(), sql);
            return extractCreateTableDdl(result, target.table());
        } catch (Exception e) {
            throw new RuntimeException("Yearning ddl failed: " + e.getMessage(), e);
        }
    }

    private void ensureReadOnlyQuery(String sql) {
        String type = statementAnalyzer.detectSqlType(sql);
        if (!List.of("SELECT", "WITH", "SHOW", "DESC", "DESCRIBE", "EXPLAIN").contains(type)) {
            throw new IllegalArgumentException("Yearning access mode only supports read-only queries. SQL type: " + type);
        }
    }

    private String qualifySql(String sql, YearningConfig config) {
        String database = config.getDatabase();
        if (sql == null || sql.isBlank() || database == null || database.isBlank()) {
            return sql;
        }
        String type = statementAnalyzer.detectSqlType(sql);
        if (!List.of("SELECT", "WITH", "EXPLAIN").contains(type)) {
            return sql;
        }
        try {
            Statement statement = statementAnalyzer.parseStatement(sql);
            qualifyStatement(statement, database);
            return statement.toString();
        } catch (SqlParseException e) {
            return sql;
        }
    }

    private YearningConfig resolveConfigForSql(YearningConfig config, String sql) {
        Set<String> databases = extractExplicitDatabases(sql);
        if (databases.size() != 1) {
            return config;
        }
        String database = databases.iterator().next();
        if (database.equalsIgnoreCase(config.getDatabase())) {
            return config;
        }
        return config.withDatabase(database);
    }

    private Set<String> extractExplicitDatabases(String sql) {
        Set<String> databases = new LinkedHashSet<>();
        if (sql == null || sql.isBlank()) {
            return databases;
        }
        try {
            Statement statement = statementAnalyzer.parseStatement(sql);
            for (String tableRef : new TablesNamesFinder().getTables(statement)) {
                String database = extractDatabase(tableRef);
                if (database != null && !database.isBlank()) {
                    databases.add(database);
                }
            }
        } catch (SqlParseException ignored) {
            return databases;
        }
        return databases;
    }

    private String extractDatabase(String tableRef) {
        if (tableRef == null || !tableRef.contains(".")) {
            return null;
        }
        int idx = tableRef.lastIndexOf('.');
        if (idx <= 0) {
            return null;
        }
        return unquoteIdentifier(tableRef.substring(0, idx));
    }

    private void qualifyStatement(Statement statement, String database) {
        if (statement instanceof Select select) {
            qualifySelect(select, database, cteNames(select));
        } else if (statement instanceof ExplainStatement explainStatement && explainStatement.getStatement() != null) {
            qualifySelect(explainStatement.getStatement(), database, cteNames(explainStatement.getStatement()));
        } else if (statement instanceof DescribeStatement describeStatement) {
            qualifyTable(describeStatement.getTable(), database, Set.of());
        }
    }

    private void qualifySelect(Select select, String database, Set<String> inheritedCteNames) {
        Set<String> localCteNames = new LinkedHashSet<>(inheritedCteNames);
        if (select.getWithItemsList() != null) {
            for (WithItem withItem : select.getWithItemsList()) {
                if (withItem.getAlias() != null && withItem.getAlias().getName() != null) {
                    localCteNames.add(withItem.getAlias().getName().toLowerCase(Locale.ROOT));
                }
            }
            for (WithItem withItem : select.getWithItemsList()) {
                if (withItem.getSelect() != null) {
                    qualifySelect(withItem.getSelect(), database, localCteNames);
                }
            }
        }
        if (select instanceof PlainSelect plainSelect) {
            qualifyPlainSelect(plainSelect, database, localCteNames);
        } else if (select instanceof SetOperationList setOperationList && setOperationList.getSelects() != null) {
            for (Select child : setOperationList.getSelects()) {
                qualifySelect(child, database, localCteNames);
            }
        } else if (select instanceof ParenthesedSelect parenthesedSelect && parenthesedSelect.getSelect() != null) {
            qualifySelect(parenthesedSelect.getSelect(), database, localCteNames);
        }
    }

    private void qualifyPlainSelect(PlainSelect plainSelect, String database, Set<String> cteNames) {
        qualifyFromItem(plainSelect.getFromItem(), database, cteNames);
        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                qualifyFromItem(join.getRightItem(), database, cteNames);
            }
        }
    }

    private void qualifyFromItem(FromItem fromItem, String database, Set<String> cteNames) {
        if (fromItem instanceof Table table) {
            qualifyTable(table, database, cteNames);
        } else if (fromItem instanceof ParenthesedSelect parenthesedSelect && parenthesedSelect.getSelect() != null) {
            qualifySelect(parenthesedSelect.getSelect(), database, cteNames);
        }
    }

    private void qualifyTable(Table table, String database, Set<String> cteNames) {
        if (table == null || database == null || database.isBlank()) {
            return;
        }
        String tableName = table.getName();
        if (tableName == null || tableName.isBlank()) {
            return;
        }
        String normalized = tableName.toLowerCase(Locale.ROOT);
        if (table.getSchemaName() != null && !table.getSchemaName().isBlank()) {
            return;
        }
        if ("dual".equalsIgnoreCase(normalized) || cteNames.contains(normalized)) {
            return;
        }
        table.setSchemaName(database);
    }

    private Set<String> cteNames(Select select) {
        Set<String> names = new LinkedHashSet<>();
        if (select == null || select.getWithItemsList() == null) {
            return names;
        }
        for (WithItem withItem : select.getWithItemsList()) {
            if (withItem.getAlias() != null && withItem.getAlias().getName() != null) {
                names.add(withItem.getAlias().getName().toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private String unquoteIdentifier(String value) {
        if (value == null || value.length() < 2) {
            return value;
        }
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        if ((first == '`' && last == '`') || (first == '"' && last == '"')) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private String extractCreateTableDdl(YearningQueryResult result, String tableName) {
        if (result.getRows().isEmpty()) {
            throw new IllegalStateException("SHOW CREATE TABLE returned no rows for " + tableName);
        }
        Map<String, Object> row = result.getRows().get(0);
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String key = entry.getKey();
            if (key != null && key.toLowerCase(Locale.ROOT).contains("create table")) {
                Object value = entry.getValue();
                if (value != null && !String.valueOf(value).isBlank()) {
                    return String.valueOf(value);
                }
            }
        }
        if (row.size() == 2) {
            for (Object value : row.values()) {
                if (value != null && String.valueOf(value).toUpperCase(Locale.ROOT).startsWith("CREATE TABLE")) {
                    return String.valueOf(value);
                }
            }
        }
        throw new IllegalStateException("SHOW CREATE TABLE response does not contain DDL text for " + tableName);
    }

    private TableTarget resolveTableTarget(YearningConfig config, String tableName, String schemaName) {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("tableName is required");
        }
        String effectiveDatabase = schemaName;
        String effectiveTable = tableName;
        if (effectiveDatabase == null || effectiveDatabase.isBlank()) {
            int idx = tableName.indexOf('.');
            if (idx > 0 && idx < tableName.length() - 1) {
                effectiveDatabase = unquoteIdentifier(tableName.substring(0, idx));
                effectiveTable = tableName.substring(idx + 1);
            }
        }
        if (effectiveDatabase == null || effectiveDatabase.isBlank()) {
            effectiveDatabase = config.getDatabase();
        }
        return new TableTarget(config.withDatabase(effectiveDatabase), unquoteIdentifier(effectiveTable));
    }

    private record TableTarget(YearningConfig config, String table) {}
}
