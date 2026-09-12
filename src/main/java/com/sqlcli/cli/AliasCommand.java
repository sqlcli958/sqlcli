package com.sqlcli.cli;

import com.sqlcli.approval.ApprovalGate;
import com.sqlcli.config.AliasConfigStore;
import com.sqlcli.config.AliasConfigValidator;
import com.sqlcli.config.AliasResolver;
import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.config.DriverConfigStore;
import com.sqlcli.config.JdbcUrlParser;
import com.sqlcli.connection.ConnectionManager;
import com.sqlcli.connection.ConnectionTestResult;
import com.sqlcli.secret.SecretResolver;
import com.sqlcli.yearning.YearningQueryExecutor;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Parameters;

import java.io.Console;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "alias",
         description = {
                 "管理数据库连接别名。",
                 "生命周期: add（创建） -> show（查看） -> update（修改） -> remove（删除）。"
         },
         mixinStandardHelpOptions = true,
         usageHelpWidth = 120,
         subcommands = {
                 AliasCommand.ShowAlias.class,
                 AliasCommand.AddAlias.class,
                 AliasCommand.UpdateAlias.class,
                 AliasCommand.RemoveAlias.class
         },
         footer = {
                 "",
                 "示例:",
                 "  sql-cli alias add my-db -i",
                 "  sql-cli alias add my-db \\",
                 "    --driver-ref mysql8 \\",
                 "    --jdbc-url jdbc:mysql://localhost:3306/app \\",
                 "    --username root --secret-ref keyring:my-db",
                 "  sql-cli alias show my-db",
                 "  sql-cli alias update my-db --description \"应用数据库\"",
                 "  sql-cli alias remove my-db",
                 "",
                 "使用 sql-cli alias <command> --help 查看子命令的完整参数。"
         })
public class AliasCommand implements Runnable {

    private final AliasConfigStore aliasStore = new AliasConfigStore();
    private final DriverConfigStore driverStore = new DriverConfigStore();
    private final SecretResolver secretResolver = new SecretResolver();
    private final ConnectionManager connectionManager = new ConnectionManager();
    private final YearningQueryExecutor yearningQueryExecutor = new YearningQueryExecutor();

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    @Command(name = "show", description = "查看一个别名的完整配置", mixinStandardHelpOptions = true, usageHelpWidth = 120)
    static class ShowAlias implements Runnable {
        @ParentCommand private AliasCommand parent;
        @Parameters(index = "0", paramLabel = "<name>", description = "别名名称") private String name;
        @Override public void run() { parent.show(name); }
    }

    @Command(name = "add", description = "创建一个新的数据库连接别名", mixinStandardHelpOptions = true, usageHelpWidth = 120,
            footer = {"", "示例:",
                    "  # 交互式创建（适合人工操作）",
                    "  sql-cli alias add my-db -i",
                    "",
                    "  # JDBC",
                    "  sql-cli alias add my-db \\",
                    "    --driver-ref mysql8 \\",
                    "    --jdbc-url jdbc:mysql://localhost:3306/app \\",
                    "    --username root --secret-ref keyring:my-db",
                    "",
                    "  # Yearning",
                    "  sql-cli alias add report-db --access-mode yearning \\",
                    "    --yearning-host https://yearning.example.com \\",
                    "    --yearning-idc prod --yearning-database report \\",
                    "    --secret-ref env:YEARNING_TOKEN"})
    static class AddAlias implements Callable<Integer> {
        @ParentCommand private AliasCommand parent;
        @Parameters(index = "0", paramLabel = "<name>", description = "新别名名称") private String name;
        @Option(names = {"-i", "--interactive"}, description = "通过交互式向导创建别名（不能与其他配置参数同时使用）")
        private boolean interactive;
        @Mixin private AliasOptions options;
        @Override
        public Integer call() {
            try {
                if (interactive) {
                    if (options.hasValues()) {
                        throw new IllegalArgumentException("-i/--interactive cannot be combined with alias configuration options");
                    }
                    parent.addInteractive(name);
                    return 0;
                }
                parent.save(name, options, true);
                return 0;
            } catch (IllegalArgumentException e) {
                System.err.println("Error: " + e.getMessage());
                return 2;
            } catch (RuntimeException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "update", description = "修改已有别名（只更新明确指定的字段）", mixinStandardHelpOptions = true, usageHelpWidth = 120,
            footer = {"", "示例:", "  sql-cli alias update my-db --jdbc-url jdbc:mysql://db.example.com:3306/app", "  sql-cli alias update my-db --description \"应用数据库\" --readonly"})
    static class UpdateAlias implements Runnable {
        @ParentCommand private AliasCommand parent;
        @Parameters(index = "0", paramLabel = "<name>", description = "已有别名名称") private String name;
        @Mixin private AliasOptions options;
        @Override public void run() { parent.save(name, options, false); }
    }

    @Command(name = "remove", description = "删除已有别名", mixinStandardHelpOptions = true, usageHelpWidth = 120,
            footer = {"", "示例:", "  sql-cli alias remove my-db"})
    static class RemoveAlias implements Runnable {
        @ParentCommand private AliasCommand parent;
        @Parameters(index = "0", paramLabel = "<name>", description = "已有别名名称") private String name;
        @Override public void run() { parent.remove(name); }
    }

    static class AliasOptions {
        @Option(names = "--db-type", paramLabel = "<type>", description = "数据库类型；通常可由 --jdbc-url 推断") String dbType;
        @Option(names = "--driver-ref", paramLabel = "<name>", description = "settings.yaml 中的 JDBC 驱动名称（JDBC 必填）") String driverRef;
        @Option(names = "--jdbc-url", paramLabel = "<url>", description = "完整 JDBC URL；也可改用 host/port/database 等分段参数") String jdbcUrl;
        @Option(names = "--host", paramLabel = "<host>", description = "数据库主机") String host;
        @Option(names = "--port", paramLabel = "<port>", description = "数据库端口") Integer port;
        @Option(names = "--database", paramLabel = "<database>", description = "MySQL/ClickHouse 数据库名") String database;
        @Option(names = "--service-name", paramLabel = "<name>", description = "Oracle SERVICE_NAME") String serviceName;
        @Option(names = "--sid", paramLabel = "<sid>", description = "Oracle SID") String sid;
        @Option(names = "--username", paramLabel = "<user>", description = "数据库用户名（JDBC 必填）") String username;
        @Option(names = "--secret-ref", paramLabel = "<ref>", description = "密码引用（必填），如 env:VAR、keyring:name、encrypted:name") String secretRef;
        @Option(names = "--description", paramLabel = "<text>", description = "数据库描述") String description;
        @Option(names = "--access-mode", paramLabel = "<jdbc|yearning>", description = "访问方式（默认 jdbc）") String accessMode;
        @Option(names = "--yearning-host", paramLabel = "<url>", description = "Yearning 服务地址") String yearningHost;
        @Option(names = "--yearning-idc", paramLabel = "<idc>", description = "Yearning 机房环境标识") String yearningIdc;
        @Option(names = "--yearning-database", paramLabel = "<database>", description = "Yearning 默认数据库") String yearningDatabase;
        @Option(names = "--readonly", paramLabel = "<true|false>", description = "是否只读") Boolean readonly;
        @Option(names = "--sm4-key", paramLabel = "<key>", description = "SM4 密钥") String sm4Key;
        @Option(names = "--sm4-private-tag", paramLabel = "<tag>", description = "SM4 密文前缀") String sm4PrivateTag;
        @Option(names = "--sm4-version", paramLabel = "<version>", description = "SM4 版本标识") String sm4Version;
        @Option(names = "--decrypt-columns", paramLabel = "<col1,col2>", split = ",", description = "自动解密列，逗号分隔") String[] decryptColumns;

        boolean hasValues() {
            return dbType != null || driverRef != null || jdbcUrl != null || host != null || port != null
                    || database != null || serviceName != null || sid != null || username != null
                    || secretRef != null || description != null || accessMode != null || yearningHost != null
                    || yearningIdc != null || yearningDatabase != null || readonly != null || sm4Key != null
                    || sm4PrivateTag != null || sm4Version != null || decryptColumns != null;
        }
    }

    private void addInteractive(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Please specify alias name");
        }
        if (new AliasResolver().listAll().containsKey(name)) {
            throw new IllegalArgumentException("Alias already exists: " + name + ". Use 'sql-cli alias update " + name + "'.");
        }
        Console console = System.console();
        if (console == null) {
            throw new IllegalArgumentException("Interactive mode requires a terminal. Use alias options for non-interactive creation.");
        }

        AliasAddWizard wizard = new AliasAddWizard(
                new AliasAddWizard.ConsolePrompt(), driverStore.loadDrivers(), driverStore.loadDefaults());
        AliasAddWizard.Result result;
        try {
            var collected = wizard.run(name);
            if (collected.isEmpty()) {
                return;
            }
            result = collected.get();
        } catch (AliasAddWizard.CancelledException e) {
            System.out.println("已取消，未写入任何配置。");
            return;
        }

        validate(result.config());
        persistInteractive(name, result);
        System.out.println("Alias created: " + name);
        testInteractiveConnection(name);
    }

    private void persistInteractive(String name, AliasAddWizard.Result result) {
        AliasAddWizard.CustomDriverPlan driverPlan = result.customDriver();
        List<Path> copiedJars = new ArrayList<>();
        boolean driverSaved = false;
        boolean secretStored = false;
        try {
            if (driverPlan != null) {
                if (driverStore.get(driverPlan.config().getName()) != null) {
                    throw new IllegalArgumentException("Driver already exists: " + driverPlan.config().getName());
                }
                if (driverPlan.copyJars()) {
                    copiedJars.addAll(copyDriverJars(driverPlan));
                }
                driverStore.saveDriver(driverPlan.config());
                driverSaved = true;
            }
            secretStored = storeInteractiveSecret(result.config().getSecretRef(), result.secretValue());
            aliasStore.saveAlias(name, result.config());
        } catch (RuntimeException | IOException e) {
            rollbackInteractive(name, result, driverSaved, secretStored, copiedJars);
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException("Failed to copy JDBC driver: " + e.getMessage(), e);
        }
    }

    private List<Path> copyDriverJars(AliasAddWizard.CustomDriverPlan plan) throws IOException {
        List<Path> copied = new ArrayList<>();
        try {
            for (int i = 0; i < plan.sourceJars().size(); i++) {
                Path source = plan.sourceJars().get(i);
                Path target = Path.of(plan.config().getJars().get(i)).toAbsolutePath().normalize();
                if (Files.exists(target)) {
                    throw new IllegalArgumentException("Driver target already exists: " + target
                            + ". Choose direct path mode or rename the source JAR.");
                }
                Files.createDirectories(target.getParent());
                Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
                copied.add(target);
            }
            return copied;
        } catch (RuntimeException | IOException e) {
            for (Path path : copied) {
                Files.deleteIfExists(path);
            }
            throw e;
        }
    }

    private boolean storeInteractiveSecret(String secretRef, String value) {
        if (secretRef == null || secretRef.startsWith("env:")) {
            return false;
        }
        String name = secretRef.substring(secretRef.indexOf(':') + 1);
        requireSecretAvailable(secretResolver, secretRef, name);
        if (secretRef.startsWith("keyring:")) {
            secretResolver.storeKeyringSecret(name, value);
            return true;
        }
        if (secretRef.startsWith("encrypted:")) {
            secretResolver.storeEncryptedSecret(name, value);
            return true;
        }
        throw new IllegalArgumentException("Unsupported secretRef: " + secretRef);
    }

    static void requireSecretAvailable(SecretResolver resolver, String secretRef, String name) {
        boolean exists = secretRef.startsWith("keyring:")
                ? resolver.keyringSecretExists(name)
                : secretRef.startsWith("encrypted:") && resolver.encryptedSecretExists(name);
        if (exists) {
            throw new IllegalArgumentException("Secret already exists: " + secretRef
                    + ". Delete it explicitly before reusing this name.");
        }
    }

    private void rollbackInteractive(String aliasName,
                                     AliasAddWizard.Result result,
                                     boolean driverSaved,
                                     boolean secretStored,
                                     List<Path> copiedJars) {
        try {
            if (aliasStore.get(aliasName) != null) {
                aliasStore.deleteAlias(aliasName);
            }
        } catch (RuntimeException ignored) {
            // Preserve the original failure.
        }
        if (secretStored) {
            deleteInteractiveSecret(result.config().getSecretRef());
        }
        if (driverSaved && result.customDriver() != null) {
            try {
                driverStore.deleteDriver(result.customDriver().config().getName());
            } catch (RuntimeException ignored) {
                // Preserve the original failure.
            }
        }
        for (Path path : copiedJars) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // Preserve the original failure.
            }
        }
    }

    private void deleteInteractiveSecret(String secretRef) {
        if (secretRef == null || !secretRef.contains(":")) return;
        String name = secretRef.substring(secretRef.indexOf(':') + 1);
        try {
            if (secretRef.startsWith("keyring:")) {
                secretResolver.deleteKeyringSecret(name);
            } else if (secretRef.startsWith("encrypted:")) {
                secretResolver.deleteEncryptedSecret(name);
            }
        } catch (RuntimeException ignored) {
            // Preserve the original failure.
        }
    }

    private void testInteractiveConnection(String aliasName) {
        System.out.println("Testing connection...");
        DatabaseConfig config = new AliasResolver().resolve(aliasName);
        if ("yearning".equalsIgnoreCase(config.getAccessMode())) {
            try {
                yearningQueryExecutor.test(config);
                System.out.println("Connection successful");
            } catch (RuntimeException e) {
                System.out.println("Connection failed: " + e.getMessage());
                System.out.println("Alias was kept. Retry with: sql-cli " + aliasName + " test");
            }
            return;
        }
        ConnectionTestResult test = connectionManager.testConnectionDetailed(config);
        if (test.isSuccess()) {
            System.out.println("Connection successful");
        } else {
            System.out.println("Connection failed: " + test.getMessage());
            System.out.println("Alias was kept. Inspect with: sql-cli alias show " + aliasName);
            System.out.println("Retry with: sql-cli " + aliasName + " test");
        }
    }

    private void show(String name) {
        DatabaseConfig config = requireExisting(name);
        boolean sqlite = "sqlite".equalsIgnoreCase(config.getType());
        System.out.println("name: " + name);
        System.out.println("description: " + nullSafe(config.getDescription()));
        System.out.println("accessMode: " + nullSafe(config.getAccessMode()));
        if (!"yearning".equalsIgnoreCase(config.getAccessMode())) {
            System.out.println("dbType: " + config.getType());
            if (!sqlite) {
                System.out.println("driverRef: " + nullSafe(config.getDriverRef()));
            }
            System.out.println("url: " + nullSafe(JdbcUrlParser.redactSecrets(config.getJdbcUrl())));
            if (blank(config.getJdbcUrl())) {
                if (sqlite) {
                    // sqlite 没有 host:port，「连接目标」就是这一行的文件路径。
                    System.out.println("database: " + nullSafe(config.getDatabase()));
                } else {
                    System.out.println("host: " + nullSafe(config.getHost()));
                    System.out.println("port: " + config.getPort());
                    System.out.println("database: " + nullSafe(config.getDatabase()));
                    System.out.println("serviceName: " + nullSafe(config.getServiceName()));
                    System.out.println("sid: " + nullSafe(config.getSid()));
                }
            }
        }
        if (!sqlite) {
            System.out.println("username: " + nullSafe(config.getUsername()));
            System.out.println("secretRef: " + maskSecretRef(config.getSecretRef()));
        }
        if ("yearning".equalsIgnoreCase(config.getAccessMode())) {
            System.out.println("yearningHost: " + nullSafe(config.getYearningHost()));
            System.out.println("yearningIdc: " + nullSafe(config.getYearningIdc()));
            System.out.println("yearningDatabase: " + nullSafe(config.getYearningDatabase()));
        }
        System.out.println("sm4Key: " + maskSensitive(config.getSm4Key()));
        System.out.println("sm4PrivateTag: " + nullSafe(config.getSm4PrivateTag()));
        System.out.println("sm4Version: " + nullSafe(config.getSm4Version()));
        System.out.println("decryptColumns: " + String.join(", ", config.getDecryptColumns()));
        System.out.println("readonly: " + config.getReadonly());
        // 没配就是 auto（无审核）——CLAUDE.md 不改这个默认值，只是让它在这里可见。
        System.out.println("graphApproval: " + ApprovalGate.graphMode(config).name());
    }

    private void save(String name, AliasOptions options, boolean create) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Please specify alias name");
        }
        DatabaseConfig current = create ? new DatabaseConfig() : requireExisting(name);
        applyUpdates(current, options, create);
        validate(current);
        aliasStore.saveAlias(name, current);
        System.out.println((create ? "Alias created: " : "Alias updated: ") + name);
        printDeprecationHint();
    }

    private void remove(String name) {
        requireExisting(name);
        aliasStore.deleteAlias(name);
        System.out.println("Alias removed: " + name);
        printDeprecationHint();
    }

    /** UI 是人类控制面（P0 架构决策）：CLI 管理命令仅提示，不移除。 */
    static void printDeprecationHint() {
        System.err.println("提示: 别名/驱动管理建议使用 Web UI 设置页（sql-cli ui），CLI 管理命令后续版本可能精简");
    }

    private DatabaseConfig requireExisting(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Please specify alias name");
        }
        DatabaseConfig config = aliasStore.get(name);
        if (config == null) {
            throw new IllegalArgumentException("Unknown alias: " + name);
        }
        return config;
    }

    private void applyUpdates(DatabaseConfig config, AliasOptions options, boolean create) {
        if (create || options.dbType != null) config.setType(options.dbType);
        if (create || options.driverRef != null) config.setDriverRef(options.driverRef);
        if (create || options.jdbcUrl != null) config.setJdbcUrl(options.jdbcUrl);
        if (create || options.host != null) config.setHost(options.host);
        if (options.port != null) config.setPort(options.port);
        if (create || options.database != null) config.setDatabase(options.database);
        if (create || options.serviceName != null) config.setServiceName(options.serviceName);
        if (create || options.sid != null) config.setSid(options.sid);
        if (create || options.username != null) config.setUsername(options.username);
        if (create || options.secretRef != null) config.setSecretRef(options.secretRef);
        if (create || options.description != null) config.setDescription(options.description);
        if (create || options.accessMode != null) config.setAccessMode(options.accessMode);
        if (create || options.yearningHost != null) config.setYearningHost(options.yearningHost);
        if (create || options.yearningIdc != null) config.setYearningIdc(options.yearningIdc);
        if (create || options.yearningDatabase != null) config.setYearningDatabase(options.yearningDatabase);
        if (create || options.sm4Key != null) config.setSm4Key(options.sm4Key);
        if (create || options.sm4PrivateTag != null) config.setSm4PrivateTag(options.sm4PrivateTag);
        if (create || options.sm4Version != null) config.setSm4Version(options.sm4Version);
        if (options.decryptColumns != null) config.setDecryptColumns(java.util.Arrays.asList(options.decryptColumns));
        if (options.readonly != null) config.setReadonly(options.readonly);
        inferDbType(config);
    }

    private void validate(DatabaseConfig config) {
        AliasConfigValidator.validate(config);
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private void inferDbType(DatabaseConfig config) {
        AliasConfigValidator.inferDbType(config);
    }

    /**
     * 脱敏 secretRef，只保留 scheme 前缀（env:、keyring:、encrypted:）。
     * 没有冒号时整体遮蔽——secretRef 被误写成裸密码时不能原样回显。
     */
    static String maskSecretRef(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int idx = value.indexOf(':');
        return idx < 0 ? "***" : value.substring(0, idx + 1) + "***";
    }

    private String maskSensitive(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return "***";
    }
}
