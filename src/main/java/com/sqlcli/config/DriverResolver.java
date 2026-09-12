package com.sqlcli.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 驱动配置解析器
 * 从 SettingsConfig 加载驱动配置
 */
public class DriverResolver {
    private static final Logger log = LoggerFactory.getLogger(DriverResolver.class);
    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?}");

    public void enrich(DatabaseConfig config) {
        String dbType = config.getType();
        if (dbType == null || dbType.isBlank()) {
            throw new IllegalArgumentException("Database type is required");
        }

        SettingsConfig settings = SettingsConfig.getInstance();
        Map<String, String> driverDefaults = settings.getDriverDefaults();

        String driverRef = config.getDriverRef();
        if (driverRef == null || driverRef.isBlank()) {
            driverRef = driverDefaults.get(dbType.toLowerCase());
            config.setDriverRef(driverRef);
        }

        if (driverRef == null || driverRef.isBlank()) {
            applyBuiltinDefaults(config);
            return;
        }

        DriverConfig driverConfig = settings.getDriver(driverRef);
        if (driverConfig == null) {
            throw new IllegalArgumentException("Unknown driverRef: " + driverRef);
        }
        if (!dbType.equalsIgnoreCase(driverConfig.getDbType())) {
            throw new IllegalArgumentException(
                    String.format("Driver %s does not match dbType %s", driverRef, dbType));
        }

        config.setDriverClass(driverConfig.getDriverClass());
        config.setDriverJars(resolveJarPaths(driverConfig.getJars()));
    }

    public Map<String, DriverConfig> listAll() {
        return SettingsConfig.getInstance().getDrivers();
    }

    private void applyBuiltinDefaults(DatabaseConfig config) {
        switch (config.getType().toLowerCase()) {
            case "mysql":
                config.setDriverClass("com.mysql.cj.jdbc.Driver");
                break;
            case "oracle":
                config.setDriverClass("oracle.jdbc.OracleDriver");
                break;
            case "postgresql":
                config.setDriverClass("org.postgresql.Driver");
                break;
            case "clickhouse":
                config.setDriverClass("com.clickhouse.jdbc.ClickHouseDriver");
                break;
            case "sqlite":
                config.setDriverClass("org.sqlite.JDBC");
                break;
            default:
                throw new IllegalArgumentException("Unsupported database type: " + config.getType());
        }
    }

    /**
     * 驱动 jar 路径。相对路径按 {@link SettingsConfig#configRoot()} 解析，**不按当前目录**。
     *
     * <p>settings.yaml 里写的是 {@code ./drivers/mysql/xxx.jar}，而它跟 settings.yaml
     * 同在 {@code ~/.sql-cli} 下。原来按 CWD 解析，意味着**换个目录启动就找不到驱动**——
     * 启动脚本靠 {@code cd} 兜着，谁绕过脚本直接跑 jar 就会撞上「驱动加载失败」，
     * 而报错说的是驱动类找不到，跟「你在哪个目录」这个真正的原因八竿子打不着。
     */
    private List<String> resolveJarPaths(List<String> jarPaths) {
        List<String> resolved = new ArrayList<>();
        for (String jarPath : jarPaths) {
            Path path = Paths.get(resolveEnvVar(jarPath));
            Path base = path.isAbsolute() ? path : SettingsConfig.configRoot().resolve(path);
            resolved.add(base.normalize().toAbsolutePath().toString());
        }
        return resolved;
    }

    private String resolveEnvVar(String value) {
        if (value == null) {
            return "";
        }
        Matcher matcher = ENV_PATTERN.matcher(value);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String envName = matcher.group(1);
            String defaultValue = matcher.group(2);
            String envValue = System.getenv(envName);
            String replacement = envValue != null ? envValue : (defaultValue != null ? defaultValue : "");
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}