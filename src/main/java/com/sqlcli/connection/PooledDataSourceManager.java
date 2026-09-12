package com.sqlcli.connection;

import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.strategy.DatabaseStrategies;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内连接池管理器
 */
public class PooledDataSourceManager {
    private static final Map<String, HikariDataSource> DATA_SOURCES = new ConcurrentHashMap<>();
    private static final DriverLoader DRIVER_LOADER = new DriverLoader();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> DATA_SOURCES.values().forEach(HikariDataSource::close)));
    }

    public HikariDataSource get(DatabaseConfig config) {
        String key = fingerprint(config);
        return DATA_SOURCES.computeIfAbsent(key, ignored -> create(config));
    }

    private HikariDataSource create(DatabaseConfig config) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName(poolName(config));
        hikari.setMaximumPoolSize(config.getMaximumPoolSize());
        hikari.setMinimumIdle(config.getMinimumIdle());
        hikari.setConnectionTimeout(config.getConnectionTimeoutMs());
        hikari.setIdleTimeout(config.getIdleTimeoutMs());
        hikari.setMaxLifetime(config.getMaxLifetimeMs());
        if (config.getKeepaliveTimeMs() > 0) {
            hikari.setKeepaliveTime(config.getKeepaliveTimeMs());
        }
        hikari.setValidationTimeout(Math.min(3000, config.getConnectionTimeoutMs()));
        // 取 -1 会跳过建池时的连接校验，真实的 SQLException（如认证失败）被后续的
        // "Connection is not available, request timed out" 掩盖，排查时看不到根因。
        hikari.setInitializationFailTimeout(config.getConnectionTimeoutMs());
        hikari.setDataSource(new DriverBackedDataSource(
                DRIVER_LOADER.load(config),
                config.buildJdbcUrl(),
                config.getUsername(),
                config.getPassword(),
                buildDriverProperties(config)
        ));
        return new HikariDataSource(hikari);
    }

    private Properties buildDriverProperties(DatabaseConfig config) {
        Properties props = new Properties();
        Map<String, String> params = config.getParams();
        if (params != null && !params.isEmpty()) {
            params.forEach((k, v) -> {
                if (k == null || k.isBlank() || v == null) {
                    return;
                }
                if (isPoolParam(k)) {
                    return;
                }
                props.setProperty(k, v);
            });
        }
        DatabaseStrategies.resolve(config).applyConnectionProperties(config, props);
        return props;
    }

    private boolean isPoolParam(String key) {
        return "maximumPoolSize".equals(key)
                || "minimumIdle".equals(key)
                || "connectionTimeoutMs".equals(key)
                || "idleTimeoutMs".equals(key)
                || "maxLifetimeMs".equals(key)
                || "keepaliveTimeMs".equals(key)
                || "defaultQueryLimit".equals(key);
    }

    private String poolName(DatabaseConfig config) {
        return "sql-cli-" + config.getType() + "-" + Integer.toHexString(fingerprint(config).hashCode());
    }

    private String fingerprint(DatabaseConfig config) {
        Map<String, String> values = new TreeMap<>();
        values.put("dbType", value(config.getType()));
        values.put("driverRef", value(config.getDriverRef()));
        values.put("driverClass", value(config.getDriverClass()));
        values.put("host", value(config.getHost()));
        values.put("port", String.valueOf(config.getPort()));
        values.put("database", value(config.getDatabase()));
        values.put("serviceName", value(config.getServiceName()));
        values.put("sid", value(config.getSid()));
        values.put("username", value(config.getUsername()));
        values.put("secretRef", value(config.getSecretRef()));
        // secretRef 不随密码轮换而改变，只按 secretRef 缓存会让改过密码的连接继续复用旧池，
        // 表现为 Hikari 连接超时而不是认证失败；因此把密码摘要一并纳入指纹。
        values.put("secretDigest", secretDigest(config.getPassword()));
        values.put("maxPool", String.valueOf(config.getMaximumPoolSize()));
        values.put("minIdle", String.valueOf(config.getMinimumIdle()));
        values.put("connTimeout", String.valueOf(config.getConnectionTimeoutMs()));
        values.put("idleTimeout", String.valueOf(config.getIdleTimeoutMs()));
        values.put("maxLifetime", String.valueOf(config.getMaxLifetimeMs()));
        values.put("keepalive", String.valueOf(config.getKeepaliveTimeMs()));
        values.put("params", String.valueOf(config.getParams()));
        return values.toString();
    }

    private String value(String input) {
        return input == null ? "" : input;
    }

    /** 只用于连接池缓存键，绝不输出到日志或池名。 */
    private String secretDigest(String password) {
        if (password == null || password.isEmpty()) {
            return "";
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required to key the connection pool", e);
        }
    }
}
