package com.sqlcli.config;

import com.sqlcli.secret.SecretRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.LoaderOptions;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 统一配置管理器
 * 从 settings.yaml 加载所有配置（别名路径、驱动、密码等）
 */
public class SettingsConfig {
    private static final Logger log = LoggerFactory.getLogger(SettingsConfig.class);
    private static final String SETTINGS_PATH = "config/settings.yaml";
    private static final String ALIASES_PATH_ENV = "SQLCLI_ALIASES_PATH";
    // 只是文件名片段，不带 "config/" 前缀——resolveAliasesPath() 相对分支会拼上 "config"。
    // 之前这里写成 "config/aliases.yaml" 是个潜伏 bug：真实 settings.yaml 里 aliasesPath 只有
    // 文件名（"aliases.yaml"），一旦真的走到这个默认值分支（settings.yaml 缺失/键缺失），
    // 拼接会变成 "config/config/aliases.yaml"。之前测试从没跑到这个分支所以没暴露。
    private static final String DEFAULT_ALIASES_PATH = "aliases.yaml";
    private static final String DEFAULT_SCHEMA_GRAPH_PATH = "config/schema-graphs";

    /**
     * 与 {@code sqlcli.home}（运行库根目录覆盖）同风格的开关，覆盖 {@code config/} 的解析基准目录。
     * 不设时是 {@code ~/.sql-cli}，见 {@link #configRoot()}。
     *
     * <p>存在的理由：{@code SettingsConfig}/{@code AliasConfigStore} 用相对路径定位 config/*，
     * 一旦测试以仓库根为 CWD 跑起来，写操作会打到真实别名配置（生产库地址 + secretRef）。
     * surefire 把它指到 build 目录后，测试永远碰不到仓库里的 config/。
     */
    public static final String CONFIG_ROOT_PROPERTY = "sqlcli.configRoot";

    private static final SettingsConfig instance = new SettingsConfig();

    private String aliasesPath;
    private String masterPasswordEnv;
    private String schemaGraphPath;
    private Map<String, String> driverDefaults = new HashMap<>();
    private Map<String, DriverConfig> drivers = new LinkedHashMap<>();
    private Map<String, SecretRecord> secrets = new LinkedHashMap<>();

    private SettingsConfig() {
        load();
    }

    public static SettingsConfig getInstance() {
        return instance;
    }

    /**
     * 重新加载配置（用于配置更新后刷新）
     */
    public void reload() {
        load();
    }

    /**
     * config/ 的解析基准目录，默认 {@code ~/.sql-cli}——**不是当前目录**。
     *
     * <p>原来默认 {@code Path.of("")}（当前目录），带来两个真发生过的问题：
     * <ul>
     *   <li><b>换个工作目录启动就读不到配置。</b>启动脚本靠 {@code cd} 到 {@code ~/.sql-cli}
     *       兜着，一旦有人绕过脚本直接 {@code java -jar}（比如自动重启脚本），
     *       服务就去仓库根的 {@code config/} 找图谱，报「图谱工作区不存在」，
     *       还会在仓库里拉出一个空的 {@code config/schema-graphs/}</li>
     *   <li><b>把密钥写进仓库。</b>以仓库根为 CWD 时，{@code secret set} 这类写操作
     *       直接落在版本控制里的 {@code config/settings.yaml} 上，一次 commit 就推到远程</li>
     * </ul>
     *
     * <p>跟 {@code sqlcli.home}（运行库根目录）用同一套解析方式：
     * 系统属性优先，否则 {@code ~/.sql-cli}。仓库里的 {@code config/} 从此只是模板，
     * 运行时不会读它、更不会写它。
     */
    public static Path configRoot() {
        String override = System.getProperty(CONFIG_ROOT_PROPERTY);
        return (override != null && !override.isBlank())
                ? Path.of(override)
                : Path.of(System.getProperty("user.home"), ".sql-cli");
    }

    private void load() {
        Path settingsFile = configRoot().resolve(SETTINGS_PATH);
        if (!Files.exists(settingsFile)) {
            log.info("settings.yaml not found, using defaults");
            aliasesPath = DEFAULT_ALIASES_PATH;
            masterPasswordEnv = "SQLCLI_MASTER_PASSWORD";
            return;
        }

        try (InputStream is = Files.newInputStream(settingsFile)) {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Map<String, Object> data = yaml.load(is);
            if (data == null) {
                data = new HashMap<>();
            }

            // 加载 aliasesPath
            aliasesPath = stringValue(data.get("aliasesPath"), DEFAULT_ALIASES_PATH);
            masterPasswordEnv = stringValue(data.get("masterPasswordEnv"), "SQLCLI_MASTER_PASSWORD");
            schemaGraphPath = stringValue(data.get("schemaGraphPath"), "config/schema-graphs");

            // 加载驱动默认配置
            loadDriverDefaults(data);

            // 加载驱动配置
            loadDrivers(data);

            // 加载密码存储
            loadSecrets(data);

            log.info("Loaded settings from: {}", settingsFile);
        } catch (Exception e) {
            log.warn("Failed to load settings.yaml", e);
            aliasesPath = DEFAULT_ALIASES_PATH;
        }
    }

    @SuppressWarnings("unchecked")
    private void loadDriverDefaults(Map<String, Object> data) {
        Object defaultsObj = data.get("driverDefaults");
        if (defaultsObj instanceof Map) {
            Map<String, Object> defaults = (Map<String, Object>) defaultsObj;
            defaults.forEach((dbType, ref) -> driverDefaults.put(dbType.toLowerCase(), String.valueOf(ref)));
        }
    }

    @SuppressWarnings("unchecked")
    private void loadDrivers(Map<String, Object> data) {
        Object driversObj = data.get("drivers");
        if (driversObj instanceof Map) {
            Map<String, Object> entries = (Map<String, Object>) driversObj;
            entries.forEach((name, value) -> {
                if (value instanceof Map) {
                    Map<String, Object> config = (Map<String, Object>) value;
                    DriverConfig driverConfig = new DriverConfig();
                    driverConfig.setName(name);
                    driverConfig.setDbType(stringValue(config.get("dbType")));
                    driverConfig.setDriverClass(stringValue(config.get("driverClass")));
                    driverConfig.setJars(asStringList(config.get("jars")));
                    drivers.put(name, driverConfig);
                }
            });
        }
    }

    @SuppressWarnings("unchecked")
    private void loadSecrets(Map<String, Object> data) {
        Object secretsObj = data.get("secrets");
        if (secretsObj instanceof Map) {
            Map<String, Object> entries = (Map<String, Object>) secretsObj;
            entries.forEach((name, value) -> {
                if (value instanceof Map) {
                    Map<String, Object> item = (Map<String, Object>) value;
                    SecretRecord record = new SecretRecord();
                    record.setName(name);
                    record.setType(stringValue(item.get("type")));
                    record.setSalt(stringValue(item.get("salt")));
                    record.setIv(stringValue(item.get("iv")));
                    record.setCiphertext(stringValue(item.get("ciphertext")));
                    record.setValue(stringValue(item.get("value")));
                    secrets.put(name, record);
                }
            });
        }
    }

    /**
     * 获取别名配置文件路径
     * 优先级：环境变量 > settings.yaml > 默认
     */
    public Path resolveAliasesPath() {
        String envPath = System.getenv(ALIASES_PATH_ENV);
        if (envPath != null && !envPath.isBlank()) {
            log.info("Using aliases path from environment: {}", envPath);
            return Paths.get(envPath);
        }

        Path path = Paths.get(aliasesPath);
        if (!path.isAbsolute()) {
            path = configRoot().resolve("config").resolve(aliasesPath);
        }
        return path;
    }

    public String getMasterPasswordEnv() {
        return masterPasswordEnv;
    }

    public String getSchemaGraphPath() {
        return schemaGraphPath;
    }

    /**
     * 图谱工作区根目录，解析到磁盘路径（相对值挂 {@link #CONFIG_ROOT_PROPERTY} 覆盖）。
     * {@link #getSchemaGraphPath()} 保留原样返回配置里的裸字符串，仅供 {@link #save()} 回写用。
     */
    public Path resolveSchemaGraphPath() {
        String raw = (schemaGraphPath == null || schemaGraphPath.isBlank())
                ? DEFAULT_SCHEMA_GRAPH_PATH : schemaGraphPath;
        Path path = Paths.get(raw);
        return path.isAbsolute() ? path : configRoot().resolve(path);
    }

    public Map<String, String> getDriverDefaults() {
        return new HashMap<>(driverDefaults);
    }

    public Map<String, DriverConfig> getDrivers() {
        return new LinkedHashMap<>(drivers);
    }

    public DriverConfig getDriver(String name) {
        return drivers.get(name);
    }

    public void putDriver(String name, DriverConfig config) {
        drivers.put(name, config);
    }

    public void removeDriver(String name) {
        drivers.remove(name);
    }

    public void putDriverDefault(String dbType, String driverRef) {
        driverDefaults.put(dbType, driverRef);
    }

    public void removeDriverDefault(String driverRef) {
        driverDefaults.entrySet().removeIf(entry -> entry.getValue().equals(driverRef));
    }

    public SecretRecord getSecret(String name) {
        return secrets.get(name);
    }

    public boolean hasSecret(String name) {
        return secrets.containsKey(name);
    }

    /**
     * 保存密码记录
     */
    public void saveSecret(String name, SecretRecord record) {
        secrets.put(name, record);
        save();
    }

    /**
     * 删除密码记录
     */
    public void deleteSecret(String name) {
        secrets.remove(name);
        save();
    }

    /**
     * 保存配置到 settings.yaml
     */
    public void save() {
        try {
            Path settingsFile = configRoot().resolve(SETTINGS_PATH);
            Files.createDirectories(settingsFile.getParent());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("aliasesPath", aliasesPath);
            data.put("masterPasswordEnv", masterPasswordEnv);
            data.put("schemaGraphPath", schemaGraphPath);
            data.put("driverDefaults", driverDefaults);

            // 保存驱动配置
            Map<String, Object> driversData = new LinkedHashMap<>();
            for (DriverConfig driver : drivers.values()) {
                Map<String, Object> driverMap = new LinkedHashMap<>();
                driverMap.put("dbType", driver.getDbType());
                driverMap.put("driverClass", driver.getDriverClass());
                driverMap.put("jars", driver.getJars());
                driversData.put(driver.getName(), driverMap);
            }
            data.put("drivers", driversData);

            // 保存密码存储
            Map<String, Object> secretsData = new LinkedHashMap<>();
            for (SecretRecord record : secrets.values()) {
                Map<String, Object> secretMap = new LinkedHashMap<>();
                secretMap.put("type", record.getType());
                secretMap.put("salt", record.getSalt());
                secretMap.put("iv", record.getIv());
                secretMap.put("ciphertext", record.getCiphertext());
                if (record.getValue() != null && !record.getValue().isBlank()) {
                    secretMap.put("value", record.getValue());
                }
                secretsData.put(record.getName(), secretMap);
            }
            data.put("secrets", secretsData);

            org.yaml.snakeyaml.DumperOptions options = new org.yaml.snakeyaml.DumperOptions();
            options.setDefaultFlowStyle(org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);
            Yaml yaml = new Yaml(options);
            try (java.io.Writer writer = Files.newBufferedWriter(settingsFile)) {
                yaml.dump(data, writer);
            }
            log.info("Saved settings to: {}", settingsFile);
        } catch (Exception e) {
            log.error("Failed to save settings.yaml", e);
            throw new RuntimeException("Failed to save settings.yaml", e);
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String stringValue(Object value, String defaultValue) {
        return value == null ? defaultValue : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private List<String> asStringList(Object value) {
        if (!(value instanceof List)) {
            return new ArrayList<>();
        }
        List<String> results = new ArrayList<>();
        for (Object item : (List<Object>) value) {
            results.add(stringValue(item));
        }
        return results;
    }
}
