package com.sqlcli.config;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.LoaderOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 别名解析器
 * 支持预制别名和用户自定义别名
 */
public class AliasResolver {
    private static final Logger log = LoggerFactory.getLogger(AliasResolver.class);

    private final Map<String, DatabaseConfig> presetAliases = new HashMap<>();
    private final Map<String, DatabaseConfig> userAliases = new HashMap<>();
    private final DriverResolver driverResolver = new DriverResolver();

    public AliasResolver() {
        loadPresetAliases();
        loadUserAliases();
    }

    /**
     * 加载预制别名 (内置)
     */
    private void loadPresetAliases() {
        try (InputStream is = getClass().getResourceAsStream("/preset-aliases.yaml")) {
            if (is != null) {
                Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
                Map<String, Object> data = yaml.load(is);
                Map<String, Object> aliases = (Map<String, Object>) data.get("aliases");
                if (aliases != null) {
                    aliases.forEach((name, config) -> {
                        DatabaseConfig dbConfig = parseConfig((Map<String, Object>) config);
                        dbConfig.setAliasName(name);
                        presetAliases.put(name, dbConfig);
                        log.debug("Loaded preset alias: {}", name);
                    });
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load preset aliases", e);
        }
    }

    /**
     * 加载用户别名
     * 路径优先级：环境变量 > settings.yaml > 默认 aliases.yaml
     */
    private void loadUserAliases() {
        Path userPath = SettingsConfig.getInstance().resolveAliasesPath();
        if (Files.exists(userPath)) {
            log.info("Loading aliases from: {}", userPath);
            try {
                Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
                Map<String, Object> data = yaml.load(Files.newInputStream(userPath));
                Map<String, Object> aliases = (Map<String, Object>) data.get("aliases");
                if (aliases != null) {
                    aliases.forEach((name, config) -> {
                        DatabaseConfig dbConfig = parseConfig((Map<String, Object>) config);
                        dbConfig.setAliasName(name);
                        userAliases.put(name, dbConfig);
                        log.debug("Loaded user alias: {}", name);
                    });
                }
            } catch (Exception e) {
                log.warn("Failed to load user aliases", e);
            }
        }
    }

    /**
     * 解析配置并替换环境变量
     */
    DatabaseConfig parseConfig(Map<String, Object> config) {
        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setType(resolveEnvVar(stringValue(config, "dbType", "type")));
        dbConfig.setDriverRef(resolveEnvVar(stringValue(config, "driverRef")));
        dbConfig.setSecretRef(resolveEnvVar(stringValue(config, "secretRef")));
        dbConfig.setJdbcUrl(resolveEnvVar(stringValue(config, "url")));
        // 结构化连接字段：以前这里只读 url，导致「不写 url、只给结构化字段」的别名
        // 写得进去却加载不回来——AliasConfigValidator 早就允许那种写法了，两边一直是矛盾的。
        // sqlite 把它放大成必现问题：它的自然写法就是 database: D:/data/shop.db，
        // 没人会手写 jdbc:sqlite: 前缀。
        // 只补 database 一个：sqlite 的自然写法就是 database: D:/data/shop.db。
        // host/port/serviceName/sid 同样没被读进来，mysql/oracle 的「结构化配置不写 url」
        // 因此一直是死路——那是同一个洞的其余部分，需要能连上对应的库才验证得了，
        // 单独立项，不在这次 sqlite 接入里顺手改。
        dbConfig.setDatabase(resolveEnvVar(stringValue(config, "database")));
        // 设置页写进来的默认 schema 以前只有 AliasConfigStore 读，这里不读——CLI 和 UI 服务器
        // 都从这里拿配置，结果是字段存了但执行时永远为空，SQL 一直跑在 URL 里的库。
        dbConfig.setDefaultSchema(resolveEnvVar(stringValue(config, "defaultSchema")));
        dbConfig.setDescription(resolveEnvVar(stringValue(config, "description")));
        dbConfig.setAccessMode(resolveEnvVar(stringValue(config, "accessMode")));
        dbConfig.setYearningHost(resolveEnvVar(stringValue(config, "yearningHost")));
        dbConfig.setYearningIdc(resolveEnvVar(stringValue(config, "yearningIdc", "yearningSource")));
        dbConfig.setYearningDatabase(resolveEnvVar(stringValue(config, "yearningDatabase")));
        if ((dbConfig.getType() == null || dbConfig.getType().isBlank()) && dbConfig.getJdbcUrl() != null) {
            dbConfig.setType(JdbcUrlParser.inferDbType(dbConfig.getJdbcUrl()));
        }
        dbConfig.setUsername(resolveEnvVar(stringValue(config, "username")));
        dbConfig.setPassword(resolveEnvVar(stringValue(config, "password")));
        dbConfig.setReadonly(parseNullableBoolean(config.get("readonly")));
        dbConfig.setApproveQuery(parseNullableBoolean(config.get("approveQuery")));
        dbConfig.setApproveUpdate(parseNullableBoolean(config.get("approveUpdate")));
        dbConfig.setApproveGraph(parseNullableBoolean(config.get("approveGraph")));
        dbConfig.setGraphApproval(resolveEnvVar(stringValue(config, "graphApproval")));
        dbConfig.setSm4Key(resolveEnvVar(stringValue(config, "sm4Key")));
        dbConfig.setSm4PrivateTag(resolveEnvVar(stringValue(config, "sm4PrivateTag")));
        dbConfig.setSm4Version(resolveEnvVar(stringValue(config, "sm4Version")));
        dbConfig.setDecryptColumns(parseList(config.get("decryptColumns")));
        dbConfig.setParams(parseParams(config.get("params")));
        applyPoolOverrides(dbConfig);
        if (!"yearning".equalsIgnoreCase(dbConfig.getAccessMode())) {
            requireResolvableUrl(dbConfig);
            driverResolver.enrich(dbConfig);
        }
        return dbConfig;
    }

    /**
     * 别名必须能拿到一个可连接的 URL——但**不一定要在 yaml 里逐字写出来**。
     *
     * <p>原来这里硬要求 {@code url} 非空，和 {@link AliasConfigValidator} 是矛盾的：
     * 那边早就允许 mysql/clickhouse 只给 {@code database}、oracle 只给 {@code serviceName/sid}，
     * 结果那类配置写得进去却加载不回来。sqlite 把这个矛盾放大成了必现问题——
     * 它的自然写法就是 {@code database: D:/data/shop.db}，人手写别名时不会去拼
     * {@code jdbc:sqlite:} 前缀。
     *
     * <p>改成：URL 为空时先让方言策略从结构化配置构建（{@code buildBaseJdbcUrl} 本来就有这条路径），
     * 构建不出来才报错，并且把 dbType 一起报出来——「url is required」这种消息不告诉人
     * 缺的到底是 url 还是构建 url 所需的那几个字段。
     */
    private void requireResolvableUrl(DatabaseConfig dbConfig) {
        if (dbConfig.getJdbcUrl() != null && !dbConfig.getJdbcUrl().isBlank()) {
            return;
        }
        if ("sqlite".equalsIgnoreCase(dbConfig.getType())) {
            if (dbConfig.getDatabase() != null && !dbConfig.getDatabase().isBlank()) {
                return;
            }
            throw new IllegalArgumentException(
                    "sqlite 别名需要 database（.db 文件路径）或 url=jdbc:sqlite:<path>");
        }
        // 其余库类型保持原样：url 缺失一律硬报错，**不要**退化成「让策略拿默认值拼一个」。
        // 试过那样写，结果是 yaml 里把 url 误拼成 jdbcUrl 时，clickhouse 会拼出
        // jdbc:clickhouse://localhost:8123/... 而不报错——一个拼写错误变成悄悄连本机，
        // 正是这类静默错误最难查。要给别的库开这条路，得先把 host/port/serviceName/sid
        // 都读进来并逐个验证，那是单独一件事。
        throw new IllegalArgumentException("url is required for JDBC alias");
    }

    private String stringValue(Map<String, Object> config, String... keys) {
        for (String key : keys) {
            Object value = config.get(key);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseParams(Object value) {
        Map<String, String> params = new LinkedHashMap<>();
        if (!(value instanceof Map<?, ?> source)) {
            return params;
        }
        source.forEach((k, v) -> params.put(String.valueOf(k), v == null ? "" : resolveEnvVar(String.valueOf(v))));
        return params;
    }

    private Boolean parseNullableBoolean(Object value) {
        if (value == null) {
            return null;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private java.util.List<String> parseList(Object value) {
        java.util.List<String> result = new java.util.ArrayList<>();
        if (!(value instanceof java.util.List<?> source)) {
            return result;
        }
        for (Object item : source) {
            result.add(resolveEnvVar(String.valueOf(item)));
        }
        return result;
    }

    private void applyPoolOverrides(DatabaseConfig config) {
        Map<String, String> params = config.getParams();
        if (params == null || params.isEmpty()) {
            return;
        }
        config.setMaximumPoolSize(parseInt(params.get("maximumPoolSize"), config.getMaximumPoolSize()));
        config.setMinimumIdle(parseInt(params.get("minimumIdle"), config.getMinimumIdle()));
        config.setConnectionTimeoutMs(parseLong(params.get("connectionTimeoutMs"), config.getConnectionTimeoutMs()));
        config.setIdleTimeoutMs(parseLong(params.get("idleTimeoutMs"), config.getIdleTimeoutMs()));
        config.setMaxLifetimeMs(parseLong(params.get("maxLifetimeMs"), config.getMaxLifetimeMs()));
        config.setKeepaliveTimeMs(parseLong(params.get("keepaliveTimeMs"), config.getKeepaliveTimeMs()));
        config.setDefaultQueryLimit(parseInt(params.get("defaultQueryLimit"), config.getDefaultQueryLimit()));
        config.setQueryTimeoutSeconds(parseInt(params.get("queryTimeoutSeconds"), config.getQueryTimeoutSeconds()));
    }

    private int parseInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(value);
    }

    private long parseLong(String value, long defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Long.parseLong(value);
    }

    /**
     * 解析环境变量 ${VAR:default}
     */
    private String resolveEnvVar(String value) {
        if (value == null) return "";
        if (value.startsWith("${") && value.endsWith("}")) {
            String inner = value.substring(2, value.length() - 1);
            String[] parts = inner.split(":");
            String envValue = System.getenv(parts[0]);
            if (envValue != null) return envValue;
            return parts.length > 1 ? parts[1] : "";
        }
        return value;
    }

    /**
     * 获取别名配置
     * 优先级: 用户别名 > 预制别名
     */
    public DatabaseConfig resolve(String alias) {
        if (userAliases.containsKey(alias)) {
            return userAliases.get(alias);
        }
        if (presetAliases.containsKey(alias)) {
            return presetAliases.get(alias);
        }
        throw new IllegalArgumentException("Unknown alias: " + alias);
    }

    /**
     * 列出所有别名
     */
    public Map<String, DatabaseConfig> listAll() {
        Map<String, DatabaseConfig> all = new HashMap<>(presetAliases);
        all.putAll(userAliases);
        return all;
    }
}
