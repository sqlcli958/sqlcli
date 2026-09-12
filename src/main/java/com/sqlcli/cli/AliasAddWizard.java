package com.sqlcli.cli;

import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.config.DriverConfig;
import com.sqlcli.config.JdbcUrlParser;
import com.sqlcli.secret.ConsolePrompts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Collects an alias configuration without writing any files. */
public final class AliasAddWizard {
    private static final List<String> DB_TYPES = List.of("mysql", "postgresql", "oracle", "clickhouse", "sqlite");

    public interface Prompt {
        String readLine(String prompt);
        char[] readPassword(String prompt);
        void println(String message);
    }

    public static final class ConsolePrompt implements Prompt {
        @Override
        public String readLine(String prompt) {
            return ConsolePrompts.readLine(prompt);
        }

        @Override
        public char[] readPassword(String prompt) {
            return ConsolePrompts.readPassword(prompt);
        }

        @Override
        public void println(String message) {
            System.out.println(message);
        }
    }

    public record CustomDriverPlan(DriverConfig config, List<Path> sourceJars, boolean copyJars) {
        public CustomDriverPlan {
            sourceJars = sourceJars == null ? List.of() : List.copyOf(sourceJars);
        }
    }

    public record Result(DatabaseConfig config,
                         CustomDriverPlan customDriver,
                         String secretValue) {
    }

    private final Prompt prompt;
    private final Map<String, DriverConfig> drivers;
    private final Map<String, String> driverDefaults;

    public AliasAddWizard(Prompt prompt,
                          Map<String, DriverConfig> drivers,
                          Map<String, String> driverDefaults) {
        this.prompt = prompt;
        this.drivers = new LinkedHashMap<>(drivers == null ? Map.of() : drivers);
        this.driverDefaults = new LinkedHashMap<>(driverDefaults == null ? Map.of() : driverDefaults);
    }

    public Optional<Result> run(String aliasName) {
        prompt.println("创建数据库别名: " + aliasName + "（输入 :quit 可取消）");
        prompt.println("");

        DatabaseConfig config = new DatabaseConfig();
        CustomDriverPlan customDriver = null;

        int accessMode = select("访问方式", List.of("JDBC", "Yearning"), 1);
        if (accessMode == 2) {
            config.setAccessMode("yearning");
            collectYearning(config);
        } else {
            config.setAccessMode("jdbc");
            String dbType = DB_TYPES.get(select("数据库类型",
                    List.of("MySQL", "PostgreSQL", "Oracle", "ClickHouse", "SQLite"), 1) - 1);
            config.setType(dbType);
            if ("sqlite".equals(dbType)) {
                // sqlite 是文件连接：没有账号密码，驱动内置在 jar 里也不用选，只问文件路径。
                collectSqliteConnection(config);
            } else {
                DriverSelection selection = selectDriver(aliasName, dbType);
                config.setDriverRef(selection.driverRef());
                customDriver = selection.customDriver();
                collectJdbcConnection(config);
            }
        }

        String secretValue = null;
        if (!"sqlite".equalsIgnoreCase(config.getType())) {
            SecretInput secret = collectSecret(aliasName);
            config.setSecretRef(secret.secretRef());
            if (secret.value() != null) {
                config.setPassword(secret.value());
            }
            secretValue = secret.value();
        }

        config.setDescription(optional("描述（可选）", ""));
        config.setReadonly(confirm("只读模式", false));
        if (confirm("配置 SM4 列加密", false)) {
            config.setSm4Key(required("SM4 密钥"));
            config.setSm4PrivateTag(optional("SM4 密文前缀", "ENC"));
            config.setSm4Version(optional("SM4 版本标识", "240606"));
            String columns = optional("自动解密列（逗号分隔，可选）", "");
            if (!columns.isBlank()) {
                config.setDecryptColumns(java.util.Arrays.stream(columns.split(","))
                        .map(String::trim)
                        .filter(value -> !value.isBlank())
                        .toList());
            }
        }

        printSummary(aliasName, config, customDriver);
        return Optional.of(new Result(config, customDriver, secretValue));
    }

    /**
     * sqlite 只问一个文件路径，没有 host/port/账号密码。
     *
     * <p>{@link com.sqlcli.strategy.SqliteDatabaseStrategy} 在真正连接时会拒绝一个不存在的
     * 文件（防止拼错路径被静默新建成一张空库），除非别名 params 里显式开了
     * {@code createIfMissing}。这里当场把同样的检查跑一遍：路径不存在就要求确认，
     * 确认后把这个开关一起存进配置——否则等向导退出、第一次真正连接时会被同一个检查
     * 拦下来，用户会觉得「明明刚才让我确认过」。这一步只收集配置，不碰文件系统本身，
     * 真正建库是 sqlite 驱动在第一次连接时做的。
     */
    private void collectSqliteConnection(DatabaseConfig config) {
        while (true) {
            String raw = required("数据库文件路径（sql-cli 所在机器上的路径）");
            Path resolved;
            try {
                resolved = resolveSqlitePath(raw);
            } catch (IllegalArgumentException e) {
                prompt.println(e.getMessage());
                continue;
            }
            if (Files.isDirectory(resolved)) {
                prompt.println("这是一个目录，不是数据库文件: " + resolved);
                continue;
            }
            if (!Files.exists(resolved)) {
                prompt.println("此路径下没有文件，保存后 SQLite 会在第一次连接时在这里新建一个空库: " + resolved);
                if (!confirm("确认新建", false)) {
                    continue;
                }
                config.getParams().put("createIfMissing", "true");
            }
            config.setDatabase(resolved.toString().replace('\\', '/'));
            return;
        }
    }

    private Path resolveSqlitePath(String raw) {
        String expanded = raw;
        if (raw.equals("~")) {
            expanded = System.getProperty("user.home");
        } else if (raw.startsWith("~/") || raw.startsWith("~\\")) {
            expanded = System.getProperty("user.home") + raw.substring(1);
        }
        return Paths.get(expanded).toAbsolutePath().normalize();
    }

    private void collectYearning(DatabaseConfig config) {
        config.setYearningHost(required("Yearning 服务地址"));
        config.setYearningIdc(required("Yearning IDC"));
        config.setYearningDatabase(required("Yearning 默认数据库"));
    }

    private DriverSelection selectDriver(String aliasName, String dbType) {
        List<DriverConfig> matching = drivers.values().stream()
                .filter(driver -> dbType.equalsIgnoreCase(driver.getDbType()))
                .toList();
        List<String> labels = new ArrayList<>();
        String defaultRef = driverDefaults.get(dbType);
        int defaultChoice = 1;
        for (int i = 0; i < matching.size(); i++) {
            DriverConfig driver = matching.get(i);
            boolean isDefault = driver.getName().equals(defaultRef);
            labels.add(driver.getName() + (isDefault ? "（默认）" : ""));
            if (isDefault) {
                defaultChoice = i + 1;
            }
        }
        labels.add("注册自定义 JDBC 驱动");
        if (matching.isEmpty()) {
            defaultChoice = 1;
        }
        int selected = select("驱动来源", labels, defaultChoice);
        if (selected <= matching.size()) {
            return new DriverSelection(matching.get(selected - 1).getName(), null);
        }
        CustomDriverPlan plan = collectCustomDriver(aliasName, dbType);
        return new DriverSelection(plan.config().getName(), plan);
    }

    private CustomDriverPlan collectCustomDriver(String aliasName, String dbType) {
        String defaultName = aliasName + "-driver";
        String name;
        while (true) {
            name = optional("驱动名称", defaultName);
            if (!drivers.containsKey(name)) {
                break;
            }
            prompt.println("驱动名称已存在，请输入新名称或选择已有驱动。");
        }
        String driverClass = optional("驱动类", defaultDriverClass(dbType));

        List<Path> sources = new ArrayList<>();
        do {
            while (true) {
                try {
                    sources.add(resolveJarPath(required("JAR 路径 #" + (sources.size() + 1))));
                    break;
                } catch (IllegalArgumentException e) {
                    prompt.println(e.getMessage());
                }
            }
        } while (confirm("继续添加 JAR", false));

        int storage = select("JAR 管理方式",
                List.of("复制到 ./drivers/" + dbType + "/（推荐）", "直接引用原始路径"), 1);
        boolean copy = storage == 1;

        DriverConfig driver = new DriverConfig();
        driver.setName(name);
        driver.setDbType(dbType);
        driver.setDriverClass(driverClass);
        if (copy) {
            driver.setJars(sources.stream()
                    .map(path -> "./drivers/" + dbType + "/" + path.getFileName())
                    .toList());
        } else {
            driver.setJars(sources.stream().map(Path::toString).toList());
        }
        return new CustomDriverPlan(driver, sources, copy);
    }

    private void collectJdbcConnection(DatabaseConfig config) {
        String dbType = config.getType();
        config.setHost(optional("主机", "localhost"));
        config.setPort(promptPort(dbType));
        if ("oracle".equals(dbType)) {
            int mode = select("Oracle 连接类型", List.of("SERVICE_NAME", "SID"), 1);
            if (mode == 1) {
                config.setServiceName(required("SERVICE_NAME"));
            } else {
                config.setSid(required("SID"));
            }
        } else {
            config.setDatabase(required("数据库名"));
        }
        config.setUsername(required("用户名"));
    }

    private SecretInput collectSecret(String aliasName) {
        int choice = select("密码存储方式",
                List.of("系统钥匙串（推荐）", "环境变量", "encrypted 加密存储"), 1);
        if (choice == 2) {
            String envName = required("环境变量名");
            return new SecretInput("env:" + envName, null);
        }
        String value;
        while (true) {
            value = new String(prompt.readPassword("数据库密码: "));
            String confirmation = new String(prompt.readPassword("再次输入数据库密码: "));
            if (value.equals(confirmation)) {
                break;
            }
            prompt.println("两次输入的密码不一致，请重试。");
        }
        return choice == 1
                ? new SecretInput("keyring:" + aliasName, value)
                : new SecretInput("encrypted:" + aliasName, value);
    }

    private void printSummary(String aliasName, DatabaseConfig config, CustomDriverPlan customDriver) {
        prompt.println("");
        if (customDriver != null) {
            DriverConfig driver = customDriver.config();
            prompt.println("将创建驱动:");
            prompt.println("  name:        " + driver.getName());
            prompt.println("  dbType:      " + driver.getDbType());
            prompt.println("  driverClass: " + driver.getDriverClass());
            prompt.println("  jars:        " + String.join(", ", driver.getJars()));
            prompt.println("");
        }
        boolean sqlite = "sqlite".equalsIgnoreCase(config.getType());
        prompt.println("将创建别名:");
        prompt.println("  name:        " + aliasName);
        prompt.println("  accessMode:  " + config.getAccessMode());
        if ("yearning".equals(config.getAccessMode())) {
            prompt.println("  host:        " + config.getYearningHost());
            prompt.println("  idc:         " + config.getYearningIdc());
            prompt.println("  database:    " + config.getYearningDatabase());
        } else {
            prompt.println("  dbType:      " + config.getType());
            if (!sqlite) {
                prompt.println("  driverRef:   " + config.getDriverRef());
            }
            // sqlite 的「连接目标」就是这一行——文件路径本身，没有 host/port 可显示。
            prompt.println("  jdbcUrl:     " + JdbcUrlParser.redactSecrets(config.buildJdbcUrl()));
            if (!sqlite) {
                prompt.println("  username:    " + config.getUsername());
            }
        }
        if (!sqlite) {
            prompt.println("  secretRef:   " + maskSecretRef(config.getSecretRef()));
        }
        prompt.println("  description: " + nullSafe(config.getDescription()));
        prompt.println("  readonly:    " + config.getReadonly());
        if (config.getSm4Key() != null && !config.getSm4Key().isBlank()) {
            prompt.println("  sm4Key:      ***");
            prompt.println("  decryptCols: " + String.join(", ", config.getDecryptColumns()));
        }
        prompt.println("");
    }

    private int select(String title, List<String> choices, int defaultChoice) {
        while (true) {
            prompt.println(title + ":");
            for (int i = 0; i < choices.size(); i++) {
                prompt.println("  " + (i + 1) + ". " + choices.get(i));
            }
            String raw = input("请选择 [" + defaultChoice + "]: ").trim();
            if (raw.isEmpty()) {
                return defaultChoice;
            }
            try {
                int selected = Integer.parseInt(raw);
                if (selected >= 1 && selected <= choices.size()) {
                    return selected;
                }
            } catch (NumberFormatException ignored) {
                // Re-prompt below.
            }
            prompt.println("请输入 1-" + choices.size() + " 之间的数字。");
        }
    }

    private boolean confirm(String label, boolean defaultValue) {
        String suffix = defaultValue ? " [Y/n]: " : " [y/N]: ";
        while (true) {
            String value = input(label + suffix).trim().toLowerCase(Locale.ROOT);
            if (value.isEmpty()) return defaultValue;
            if (value.equals("y") || value.equals("yes")) return true;
            if (value.equals("n") || value.equals("no")) return false;
            prompt.println("请输入 y 或 n。");
        }
    }

    private String required(String label) {
        while (true) {
            String value = input(label + ": ").trim();
            if (!value.isBlank()) {
                return value;
            }
            prompt.println(label + "不能为空。");
        }
    }

    private String optional(String label, String defaultValue) {
        String suffix = defaultValue == null || defaultValue.isBlank() ? ": " : " [" + defaultValue + "]: ";
        String value = input(label + suffix).trim();
        return value.isEmpty() ? defaultValue : value;
    }

    private String input(String label) {
        String value = prompt.readLine(label);
        if (value == null || ":quit".equalsIgnoreCase(value.trim())) {
            throw new CancelledException();
        }
        return value;
    }

    private Path resolveJarPath(String raw) {
        String expanded = raw;
        if (raw.equals("~")) {
            expanded = System.getProperty("user.home");
        } else if (raw.startsWith("~/") || raw.startsWith("~\\")) {
            expanded = System.getProperty("user.home") + raw.substring(1);
        }
        Path path = Paths.get(expanded).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IllegalArgumentException("JAR 文件不存在或不可读: " + path);
        }
        if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new IllegalArgumentException("驱动文件必须是 .jar: " + path);
        }
        return path;
    }

    private int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65535) {
                throw new NumberFormatException();
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("端口必须是 1-65535 之间的数字: " + value);
        }
    }

    private int promptPort(String dbType) {
        while (true) {
            String value = optional("端口", String.valueOf(defaultPort(dbType)));
            try {
                return parsePort(value);
            } catch (IllegalArgumentException e) {
                prompt.println(e.getMessage());
            }
        }
    }

    private int defaultPort(String dbType) {
        return switch (dbType) {
            case "mysql" -> 3306;
            case "postgresql" -> 5432;
            case "oracle" -> 1521;
            case "clickhouse" -> 8123;
            default -> 0;
        };
    }

    private String defaultDriverClass(String dbType) {
        return switch (dbType) {
            case "mysql" -> "com.mysql.cj.jdbc.Driver";
            case "postgresql" -> "org.postgresql.Driver";
            case "oracle" -> "oracle.jdbc.OracleDriver";
            case "clickhouse" -> "com.clickhouse.jdbc.ClickHouseDriver";
            default -> "";
        };
    }

    private String maskSecretRef(String value) {
        return AliasCommand.maskSecretRef(value);
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private record DriverSelection(String driverRef, CustomDriverPlan customDriver) {
    }

    private record SecretInput(String secretRef, String value) {
    }

    public static final class CancelledException extends RuntimeException {
        public CancelledException() {
            super("Interactive alias creation cancelled");
        }
    }
}
