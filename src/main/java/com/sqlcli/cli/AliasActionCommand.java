package com.sqlcli.cli;

import com.sqlcli.config.AliasResolver;
import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.config.JdbcUrlParser;
import com.sqlcli.connection.ConnectionManager;
import com.sqlcli.connection.ConnectionTestResult;
import com.sqlcli.connection.QueryExecutionOptions;
import com.sqlcli.connection.QueryResultRenderer;
import com.sqlcli.secret.ConsolePrompts;
import com.sqlcli.secret.SecretResolver;
import com.sqlcli.strategy.DatabaseStrategies;
import com.sqlcli.strategy.DatabaseStrategy;
import com.sqlcli.strategy.TableInfo;
import com.sqlcli.yearning.YearningQueryExecutor;
import lombok.Setter;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.sql.Connection;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Alias 操作命令
 * sql-cli <alias> <action> [args...]
 * <p>
 * action: test, info, ddl, tables, secret
 */
@Command(name = "alias-action",
         description = "Alias operations",
         mixinStandardHelpOptions = true)
public class AliasActionCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "操作: test, info, ddl, tables, secret")
    private String action;

    @Parameters(index = "1", arity = "0..1", description = "表名 (ddl操作需要)")
    private String tableName;

    @Option(names = {"-s", "--schema"}, description = "Schema 名称")
    private String schemaName;

    @Option(names = {"-p", "--pattern"}, description = "表名匹配模式")
    private String pattern;

    @Option(names = {"-f", "--format"}, defaultValue = "table", converter = OutputFormatConverter.class,
            description = "输出格式: table, csv, json")
    private String format;

    @Option(names = {"-j", "--json"}, description = "JSON格式输出 (test/info/secret)")
    private boolean jsonOutput;

    @Option(names = {"--stdin"}, description = "从标准输入读取密码 (secret set)")
    private boolean stdin;

    // 用于存储 alias 名，由 SqlCli 设置
    @Setter
    private String alias;

    private final AliasResolver aliasResolver = new AliasResolver();
    private final ConnectionManager connectionManager = new ConnectionManager();
    private final SecretResolver secretResolver = new SecretResolver();
    private final YearningQueryExecutor yearningQueryExecutor = new YearningQueryExecutor();
    private final QueryResultRenderer queryResultRenderer = new QueryResultRenderer();
    private final boolean isInteractive = System.console() != null;

    public static class OutputFormatConverter implements ITypeConverter<String> {
        @Override
        public String convert(String value) {
            return QueryExecutionOptions.normalizeFormat(value);
        }
    }

    @Override
    public Integer call() {
        if (alias == null) {
            CommandLine.usage(this, System.out);
            return 0;
        }

        if (action == null) {
            System.out.println("Alias: " + alias);
            System.out.println("Available actions: test, info, ddl, tables, secret");
            System.out.println("Usage: sql-cli " + alias + " <action> [args...]");
            return 0;
        }

        return switch (action.toLowerCase()) {
            case "test" -> executeTest();
            case "info" -> executeInfo();
            case "ddl" -> executeDdl();
            case "tables" -> executeTables();
            case "secret" -> executeSecret();
            default -> {
                System.err.println("Unknown action: " + action);
                System.err.println("Available: test, info, ddl, tables, secret");
                yield 2;
            }
        };
    }

    // ========== test ==========

    private int executeTest() {
        try {
            DatabaseConfig config = aliasResolver.resolve(alias);

            if (isInteractive && !jsonOutput) {
                    System.err.println("Testing connection: " + alias);
                    if ("yearning".equalsIgnoreCase(config.getAccessMode())) {
                        System.err.println("Access mode: yearning");
                        System.err.println("Yearning host: " + config.getYearningHost());
                        System.err.println("Yearning idc: " + config.getYearningIdc());
                        System.err.println("Yearning database: " + config.getYearningDatabase());
                } else {
                    System.err.println("URL: " + safe(config.buildJdbcUrl()));
                    System.err.println("Driver: " + config.getDriverRef() + " (" + config.getDriverClass() + ")");
                }
                System.err.println("Java: " + System.getProperty("java.runtime.version"));
                if (!"yearning".equalsIgnoreCase(config.getAccessMode()) && "oracle".equalsIgnoreCase(config.getType())) {
                    System.err.println("Oracle URL mode: " + JdbcUrlParser.inferOracleConnectMode(config.buildJdbcUrl()));
                }
            }

            ConnectionTestResult result = "yearning".equalsIgnoreCase(config.getAccessMode())
                    ? testYearning(config)
                    : connectionManager.testConnectionDetailed(config);
            if (jsonOutput) {
                printTestResultJson(alias, result);
            } else {
                printTestResultText(result);
            }
            return result.isSuccess() ? 0 : 1;
        } catch (Exception e) {
            if (jsonOutput) {
                CliJson.printFailure("CONNECTION_FAILED", safe(e.getMessage()));
            } else {
                System.out.println("Connection failed");
                System.out.println("Message: " + e.getMessage());
            }
            return 1;
        }
    }

    private void printTestResultJson(String alias, ConnectionTestResult result) {
        if (!result.isSuccess()) {
            CliJson.printFailure("CONNECTION_FAILED", safe(result.getMessage()));
            return;
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("alias", alias);
        if (result.getServerVersion() != null && !result.getServerVersion().isBlank()) {
            output.put("serverVersion", result.getServerVersion());
        }
        if (result.getExtraInfo() != null && !result.getExtraInfo().isEmpty()) {
            output.put("extraInfo", result.getExtraInfo());
        }
        CliJson.printSuccess(output);
    }

    private void printTestResultText(ConnectionTestResult result) {
        if (result.isSuccess()) {
            System.out.println("Connection successful");
            if (result.getServerVersion() != null && !result.getServerVersion().isBlank()) {
                System.out.println("  Server version: " + result.getServerVersion());
            }
            if (result.getExtraInfo() != null && !result.getExtraInfo().isEmpty()) {
                result.getExtraInfo().forEach((key, value) ->
                        System.out.println("  " + key + ": " + value));
            }
        } else {
            System.out.println("Connection failed");
            System.out.println("Message: " + safe(result.getMessage()));
            if (result.getRootCause() != null && !result.getRootCause().isBlank()) {
                System.out.println("Root cause: " + safe(result.getRootCause()));
            }
            if (!result.getHints().isEmpty()) {
                System.out.println("Hints:");
                result.getHints().forEach(hint -> System.out.println("  - " + safe(hint)));
            }
        }
    }

    private ConnectionTestResult testYearning(DatabaseConfig config) {
        try {
            yearningQueryExecutor.test(config);
            return ConnectionTestResult.success();
        } catch (RuntimeException e) {
            Throwable root = rootCause(e);
            return ConnectionTestResult.failure(
                    e.getMessage() == null ? "Yearning connection failed" : e.getMessage(),
                    root == null ? "" : root.getClass().getSimpleName() + ": " + root.getMessage(),
                    java.util.List.of("Check yearningHost/yearningIdc/yearningDatabase and secretRef token")
            );
        }
    }

    // ========== info ==========

    private int executeInfo() {
        try {
            DatabaseConfig config = aliasResolver.resolve(alias);
            DatabaseStrategy strategy = DatabaseStrategies.resolve(config);
            Map<String, String> info = new LinkedHashMap<>();
            info.put("alias", alias);
            info.put("accessMode", blankToDefault(config.getAccessMode(), "jdbc"));
            info.put("dbType", blankToDefault(config.getType(), "unknown"));

            boolean unsupported = "yearning".equalsIgnoreCase(config.getAccessMode());
            if (unsupported) {
                info.put("connectionStatus", "unsupported");
                info.put("message", "info is not supported for accessMode=yearning");
            } else {
                try (Connection conn = connectionManager.getConnection(config)) {
                    info.put("connectionStatus", "ok");
                    info.putAll(strategy.collectConnectionInfo(conn, config));
                }
            }

            if (jsonOutput) {
                CliJson.printSuccess(info);
            } else {
                printInfoText(info);
            }
            return unsupported ? 1 : 0;
        } catch (Exception e) {
            if (jsonOutput) {
                CliJson.printFailure("INFO_FAILED", safe(e.getMessage()));
            } else {
                System.err.println("Failed to get database info: " + safe(e.getMessage()));
            }
            return 1;
        }
    }

    private void printInfoText(Map<String, String> info) {
        System.out.println("Alias info:");
        info.forEach((key, value) -> System.out.println("  " + key + ": " + value));
    }

    // ========== ddl ==========

    private int executeDdl() {
        if (tableName == null) {
            System.err.println("Usage: sql-cli " + alias + " ddl <table> [--schema <schema>]");
            return 2;
        }

        try {
            DatabaseConfig config = aliasResolver.resolve(alias);

            // Oracle 表名大小写处理：如果表名不含双引号且包含小写字母，自动转为大写
            String effectiveTableName = tableName;
            boolean isOracle = "oracle".equalsIgnoreCase(config.getType()) ||
                    ("yearning".equalsIgnoreCase(config.getAccessMode()) &&
                     config.getYearningDatabase() != null &&
                     config.getYearningDatabase().toLowerCase().contains("oracle"));
            if (isOracle && !tableName.contains("\"") && !tableName.equals(tableName.toUpperCase())) {
                effectiveTableName = tableName.toUpperCase();
            }

            if ("yearning".equalsIgnoreCase(config.getAccessMode())) {
                String ddl = yearningQueryExecutor.getTableDdl(config, effectiveTableName, schemaName);
                System.out.println(ddl);
                return 0;
            }
            DatabaseStrategy strategy = DatabaseStrategies.resolve(config);

            String effectiveSchema = schemaName;
            if (effectiveSchema == null || effectiveSchema.isBlank()) {
                effectiveSchema = resolveDefaultSchema(config, strategy);
            }

            try (Connection conn = connectionManager.getConnection(config)) {
                String ddl = strategy.getTableDdl(conn, effectiveTableName, effectiveSchema);
                System.out.println(ddl);
            }
            return 0;
        } catch (Exception e) {
            System.err.println("Failed to get DDL: " + safe(e.getMessage()));
            return 1;
        }
    }

    // ========== tables ==========

    private int executeTables() {
        try {
            DatabaseConfig config = aliasResolver.resolve(alias);
            if ("yearning".equalsIgnoreCase(config.getAccessMode())) {
                System.err.println("tables is not supported for accessMode=yearning");
                return 1;
            }
            DatabaseStrategy strategy = DatabaseStrategies.resolve(config);

            String effectiveSchema = schemaName;
            if (effectiveSchema == null || effectiveSchema.isBlank()) {
                effectiveSchema = resolveDefaultSchema(config, strategy);
            }

            try (Connection conn = connectionManager.getConnection(config)) {
                List<TableInfo> tables = strategy.listTables(conn, effectiveSchema, pattern);
                if (tables.isEmpty()) {
                    System.out.println("No tables found");
                    return 0;
                }
                printTables(tables, config);
            }
            return 0;
        } catch (Exception e) {
            System.err.println("Failed to list tables: " + safe(e.getMessage()));
            return 1;
        }
    }

    /** 由数据库策略解析未显式传入时的默认 schema/database。 */
    private String resolveDefaultSchema(DatabaseConfig config, DatabaseStrategy strategy) {
        return strategy.defaultSchema(config);
    }

    private void printTables(List<TableInfo> tables, DatabaseConfig config) {
        String normalizedFormat = QueryExecutionOptions.normalizeFormat(format);
        if ("table".equals(normalizedFormat)) {
            printTablesTable(tables);
            return;
        }
        List<String> headers = List.of("SCHEMA", "NAME", "TYPE", "REMARKS");
        List<List<Object>> rows = tables.stream()
                .<List<Object>>map(table -> java.util.Arrays.asList(
                        table.getSchema(), table.getName(), table.getType(), table.getRemarks()))
                .toList();
        System.out.print(queryResultRenderer.render(headers, rows, normalizedFormat, 0L,
                alias, config.getType(), "TABLES", false));
    }

    private void printTablesTable(List<TableInfo> tables) {
        int schemaWidth = Math.max(8, tables.stream().mapToInt(t -> t.getSchema() != null ? t.getSchema().length() : 0).max().orElse(0));
        int nameWidth = Math.max(4, tables.stream().mapToInt(t -> t.getName().length()).max().orElse(0));
        int typeWidth = Math.max(4, tables.stream().mapToInt(t -> t.getType() != null ? t.getType().length() : 0).max().orElse(8));
        int remarksWidth = Math.max(7, tables.stream().mapToInt(t -> t.getRemarks() != null ? Math.min(t.getRemarks().length(), 50) : 0).max().orElse(0));

        String separator = "+" + "-".repeat(schemaWidth + 2) + "+" + "-".repeat(nameWidth + 2) + "+" + "-".repeat(typeWidth + 2) + "+" + "-".repeat(remarksWidth + 2) + "+";

        System.out.println(separator);
        System.out.printf("| %-" + schemaWidth + "s | %-" + nameWidth + "s | %-" + typeWidth + "s | %-" + remarksWidth + "s |\n", "SCHEMA", "NAME", "TYPE", "REMARKS");
        System.out.println(separator);

        for (TableInfo table : tables) {
            String schema = table.getSchema() != null ? table.getSchema() : "";
            String remarks = table.getRemarks() != null ? truncate(table.getRemarks(), remarksWidth) : "";
            String type = table.getType() != null ? table.getType() : "TABLE";
            System.out.printf("| %-" + schemaWidth + "s | %-" + nameWidth + "s | %-" + typeWidth + "s | %-" + remarksWidth + "s |\n", schema, table.getName(), type, remarks);
        }
        System.out.println(separator);
        System.out.println("(" + tables.size() + " tables)");
    }

    // ========== secret ==========

    private int executeSecret() {
        String secretAction = tableName; // tableName 在这里用作 secret 的 action

        if (secretAction == null) {
            System.err.println("Usage: sql-cli " + alias + " secret set|delete|status");
            return 2;
        }

        try {
            return switch (secretAction.toLowerCase()) {
                case "set" -> executeSecretSet();
                case "delete" -> executeSecretDelete();
                case "status" -> executeSecretStatus();
                default -> {
                System.err.println("Unknown secret action: " + secretAction);
                    yield 2;
                }
            };
        } catch (Exception e) {
            if (jsonOutput) {
                CliJson.printFailure("SECRET_FAILED", safe(e.getMessage()));
            } else {
                System.err.println("Secret operation failed: " + safe(e.getMessage()));
            }
            return 1;
        }
    }

    private int executeSecretSet() {
        DatabaseConfig config = aliasResolver.resolve(alias);
        String secretRef = config.getSecretRef();
        if (secretRef == null || secretRef.isBlank()) {
            System.err.println("Alias secretRef is required");
            return 1;
        }

        while (true) {
            String password = stdin
                    ? ConsolePrompts.readLine("Database password: ")
                    : new String(ConsolePrompts.readPassword("Database password: "));

            if (secretRef.startsWith("encrypted:")) {
                String name = secretRef.substring("encrypted:".length());
                char[] masterPassword = ConsolePrompts.readPassword("Master password: ");
                secretResolver.storeEncryptedSecret(name, password, masterPassword);
            } else if (secretRef.startsWith("keyring:")) {
                String name = secretRef.substring("keyring:".length());
                secretResolver.storeKeyringSecret(name, password);
            } else {
                System.err.println("Unsupported secretRef: " + secretRef);
                return 1;
            }
            System.out.println("Secret stored for alias: " + alias);

            System.out.println("Testing connection...");
            ConnectionTestResult result = "yearning".equalsIgnoreCase(config.getAccessMode())
                    ? testYearning(config)
                    : connectionManager.testConnectionDetailed(config);

            if (result.isSuccess()) {
                System.out.println("Connection successful!");
                return 0;
            }

            System.out.println("Connection failed: " + result.getMessage());

            String retry = ConsolePrompts.readLine("Retry with new password? (y/n): ");
            if (!"y".equalsIgnoreCase(retry.trim())) {
                return 1;
            }
        }
    }

    private int executeSecretDelete() {
        DatabaseConfig config = aliasResolver.resolve(alias);
        String secretRef = config.getSecretRef();
        if (secretRef == null || secretRef.isBlank()) {
            System.err.println("Alias secretRef is required");
            return 1;
        }

        if (secretRef.startsWith("encrypted:")) {
            String name = secretRef.substring("encrypted:".length());
            secretResolver.deleteEncryptedSecret(name);
            System.out.println("Encrypted secret deleted for alias: " + alias);
        } else if (secretRef.startsWith("keyring:")) {
            String name = secretRef.substring("keyring:".length());
            secretResolver.deleteKeyringSecret(name);
            System.out.println("Keyring secret deleted for alias: " + alias);
        } else {
            System.err.println("Unsupported secretRef: " + secretRef);
            return 1;
        }
        return 0;
    }

    /** secret status 的判定结果；与输出格式无关，便于单测。 */
    record SecretStatus(boolean exists, boolean usable, String detail) {
    }

    /**
     * 判定 secretRef 指向的密码是否存在、是否真正可用。
     *
     * <p>抽成静态方法并显式传入依赖，是为了让各分支（含空值、损坏条目）可单测——
     * 命令类里的 resolver 字段是硬编码 new 出来的，无法替换。
     *
     * @param env 环境变量查询，测试可传入替身；生产传 {@code System::getenv}
     */
    static SecretStatus inspectSecret(SecretResolver resolver, String secretRef,
                                      java.util.function.UnaryOperator<String> env) {
        if (secretRef.startsWith("env:")) {
            String value = env.apply(secretRef.substring("env:".length()));
            boolean exists = value != null;
            boolean usable = value != null && !value.isBlank();
            return new SecretStatus(exists, usable,
                    exists && !usable ? "environment variable is set but empty" : null);
        }
        if (secretRef.startsWith("encrypted:")) {
            // 解密需要主密码，status 不应触发交互式输入，因此只做存在性检查
            boolean exists = resolver.encryptedSecretExists(secretRef.substring("encrypted:".length()));
            return new SecretStatus(exists, exists, null);
        }
        if (secretRef.startsWith("keyring:")) {
            if (!resolver.keyringSecretExists(secretRef.substring("keyring:".length()))) {
                return new SecretStatus(false, false, null);
            }
            // 条目存在不等于内容可用：损坏或空值的密码此前会被报成 available
            try {
                String value = resolver.resolveSecret(secretRef);
                boolean usable = value != null && !value.isBlank();
                return new SecretStatus(true, usable,
                        usable ? null : "stored secret is empty; run 'secret set' again");
            } catch (RuntimeException e) {
                return new SecretStatus(true, false, "stored secret cannot be read: " + e.getMessage());
            }
        }
        return new SecretStatus(false, false, null);
    }

    private int executeSecretStatus() {
        DatabaseConfig config = aliasResolver.resolve(alias);
        String secretRef = config.getSecretRef();

        if (secretRef == null || secretRef.isBlank()) {
            if (jsonOutput) {
                CliJson.printSuccess(Map.of("alias", alias, "configured", false));
            } else {
                System.out.println("No secretRef configured");
            }
            return 0;
        }

        String type = secretRef.split(":")[0];
        SecretStatus status = inspectSecret(secretResolver, secretRef, System::getenv);
        boolean exists = status.exists();
        boolean usable = status.usable();
        String detail = status.detail();

        if (jsonOutput) {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("alias", alias);
            payload.put("configured", true);
            payload.put("exists", exists);
            payload.put("usable", usable);
            payload.put("type", type);
            if (detail != null) payload.put("detail", detail);
            CliJson.printSuccess(payload);
        } else {
            String state = !exists ? "missing" : (usable ? "available" : "unusable");
            System.out.println(type + " secret configured: " + state);
            if (detail != null) System.out.println("  " + detail);
        }
        return 0;
    }

    // ========== utils ==========

    private String truncate(String value, int maxLen) {
        if (value == null) return "";
        return value.length() <= maxLen ? value : value.substring(0, maxLen - 3) + "...";
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String safe(String value) {
        return JdbcUrlParser.redactSecrets(value);
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
