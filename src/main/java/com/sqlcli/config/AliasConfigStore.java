package com.sqlcli.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.LoaderOptions;

import java.io.InputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * alias 配置持久化
 */
public class AliasConfigStore {
    // 相对路径，实际落盘位置挂 SettingsConfig.configRoot()（sqlcli.configRoot 系统属性）——
    // 测试把它指到 build 目录，否则以仓库根为 CWD 跑测试会直接覆盖真实别名配置。
    private static final String ALIASES_PATH = "config/aliases.yaml";

    public Map<String, DatabaseConfig> loadAliases() {
        Path path = SettingsConfig.configRoot().resolve(ALIASES_PATH);
        Map<String, DatabaseConfig> aliases = new LinkedHashMap<>();
        if (!Files.exists(path)) {
            return aliases;
        }

        try (InputStream is = Files.newInputStream(path)) {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Map<String, Object> root = yaml.load(is);
            if (root == null) {
                return aliases;
            }
            Object rawAliases = root.get("aliases");
            if (!(rawAliases instanceof Map<?, ?> map)) {
                return aliases;
            }
            map.forEach((key, value) -> {
            DatabaseConfig config = fromMap((Map<String, Object>) value);
            config.setAliasName(String.valueOf(key));
            aliases.put(String.valueOf(key), config);
        });
            return aliases;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load aliases config", e);
        }
    }

    public DatabaseConfig get(String name) {
        return loadAliases().get(name);
    }

    public void saveAlias(String name, DatabaseConfig config) {
        Map<String, DatabaseConfig> aliases = loadAliases();
        aliases.put(name, config);
        saveAll(aliases);
    }

    public void deleteAlias(String name) {
        Map<String, DatabaseConfig> aliases = loadAliases();
        aliases.remove(name);
        saveAll(aliases);
    }

    @SuppressWarnings("unchecked")
    private DatabaseConfig fromMap(Map<String, Object> map) {
        DatabaseConfig config = new DatabaseConfig();
        config.setType(asString(map.get("dbType")));
        config.setDriverRef(asString(map.get("driverRef")));
        config.setJdbcUrl(asString(map.get("url")));
        if ((config.getType() == null || config.getType().isBlank()) && config.getJdbcUrl() != null) {
            config.setType(JdbcUrlParser.inferDbType(config.getJdbcUrl()));
        }
        config.setUsername(asString(map.get("username")));
        config.setSecretRef(asString(map.get("secretRef")));
        config.setDescription(asString(map.get("description")));
        config.setDefaultSchema(asString(map.get("defaultSchema")));
        config.setAccessMode(asString(map.get("accessMode")));
        config.setYearningHost(asString(map.get("yearningHost")));
        config.setYearningIdc(asString(map.get("yearningIdc")));
        if (config.getYearningIdc() == null || config.getYearningIdc().isBlank()) {
            config.setYearningIdc(asString(map.get("yearningSource")));
        }
        config.setYearningDatabase(asString(map.get("yearningDatabase")));
        if (!"yearning".equalsIgnoreCase(config.getAccessMode())
                && (config.getJdbcUrl() == null || config.getJdbcUrl().isBlank())) {
            throw new IllegalArgumentException("url is required for JDBC alias");
        }
        config.setSm4Key(asString(map.get("sm4Key")));
        config.setSm4PrivateTag(asString(map.get("sm4PrivateTag")));
        config.setSm4Version(asString(map.get("sm4Version")));
        Object decryptColumns = map.get("decryptColumns");
        if (decryptColumns instanceof java.util.List<?> list) {
            java.util.List<String> values = new java.util.ArrayList<>();
            for (Object item : list) {
                values.add(String.valueOf(item));
            }
            config.setDecryptColumns(values);
        }
        Object readonly = map.get("readonly");
        if (readonly != null) {
            config.setReadonly(Boolean.parseBoolean(String.valueOf(readonly)));
        }
        readBoolean(map, "approveQuery", config::setApproveQuery);
        readBoolean(map, "approveUpdate", config::setApproveUpdate);
        readBoolean(map, "approveGraph", config::setApproveGraph);
        Object graphApproval = map.get("graphApproval");
        if (graphApproval != null) {
            config.setGraphApproval(String.valueOf(graphApproval));
        }
        Object params = map.get("params");
        if (params instanceof Map<?, ?> value) {
            Map<String, String> converted = new LinkedHashMap<>();
            value.forEach((k, v) -> converted.put(String.valueOf(k), String.valueOf(v)));
            config.setParams(converted);
        }
        return config;
    }

    private void saveAll(Map<String, DatabaseConfig> aliases) {
        try {
            Path path = SettingsConfig.configRoot().resolve(ALIASES_PATH);
            Files.createDirectories(path.getParent());
            Map<String, Object> root = new LinkedHashMap<>();
            Map<String, Object> aliasMap = new LinkedHashMap<>();
            aliases.forEach((name, config) -> aliasMap.put(name, toMap(config)));
            root.put("aliases", aliasMap);

            try (Writer writer = Files.newBufferedWriter(path)) {
                new Yaml(yamlOptions()).dump(root, writer);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to save aliases config", e);
        }
    }

    Map<String, Object> toMap(DatabaseConfig config) {
        Map<String, Object> map = new LinkedHashMap<>();
        putIfNotBlank(map, "dbType", effectiveDbType(config));
        putIfNotBlank(map, "driverRef", config.getDriverRef());
        if (!"yearning".equalsIgnoreCase(config.getAccessMode())) {
            putIfNotBlank(map, "url", config.buildBaseJdbcUrl());
        }
        putIfNotBlank(map, "username", config.getUsername());
        putIfNotBlank(map, "secretRef", config.getSecretRef());
        putIfNotBlank(map, "description", config.getDescription());
        putIfNotBlank(map, "defaultSchema", config.getDefaultSchema());
        if (config.getAccessMode() != null && !"jdbc".equalsIgnoreCase(config.getAccessMode())) {
            putIfNotBlank(map, "accessMode", config.getAccessMode());
        }
        putIfNotBlank(map, "yearningHost", config.getYearningHost());
        putIfNotBlank(map, "yearningIdc", config.getYearningIdc());
        putIfNotBlank(map, "yearningDatabase", config.getYearningDatabase());
        putIfNotBlank(map, "sm4Key", config.getSm4Key());
        putIfNotBlank(map, "sm4PrivateTag", config.getSm4PrivateTag());
        putIfNotBlank(map, "sm4Version", config.getSm4Version());
        if (config.getDecryptColumns() != null && !config.getDecryptColumns().isEmpty()) {
            map.put("decryptColumns", new java.util.ArrayList<>(config.getDecryptColumns()));
        }
        if (config.getReadonly() != null) {
            map.put("readonly", config.getReadonly());
        }
        putIfTrue(map, "approveQuery", config.getApproveQuery());
        putIfTrue(map, "approveUpdate", config.getApproveUpdate());
        putIfTrue(map, "approveGraph", config.getApproveGraph());
        putIfNotBlank(map, "graphApproval", config.getGraphApproval());
        if (config.getParams() != null && !config.getParams().isEmpty()) {
            map.put("params", new LinkedHashMap<>(config.getParams()));
        }
        return map;
    }

    private DumperOptions yamlOptions() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        return options;
    }

    private void putIfNotBlank(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    /** 关掉的开关不写进 yaml——默认关，写一堆 false 只是噪音。 */
    private void putIfTrue(Map<String, Object> map, String key, Boolean value) {
        if (Boolean.TRUE.equals(value)) {
            map.put(key, true);
        }
    }

    private void readBoolean(Map<String, Object> map, String key, java.util.function.Consumer<Boolean> setter) {
        Object value = map.get(key);
        if (value != null) {
            setter.accept(Boolean.parseBoolean(String.valueOf(value)));
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String effectiveDbType(DatabaseConfig config) {
        if (config.getType() != null && !config.getType().isBlank()) {
            return config.getType();
        }
        return JdbcUrlParser.inferDbType(config.getJdbcUrl());
    }
}
