package com.sqlcli.connection;

import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.config.JdbcUrlParser;
import com.sqlcli.secret.SecretResolver;
import com.sqlcli.strategy.DatabaseErrorMessages;
import com.sqlcli.strategy.DatabaseStrategies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * 数据库连接管理器
 */
public class ConnectionManager {
    private static final Logger log = LoggerFactory.getLogger(ConnectionManager.class);
    private final SecretResolver secretResolver = new SecretResolver();
    private final PooledDataSourceManager pooledDataSourceManager = new PooledDataSourceManager();
    private final DriverLoader driverLoader = new DriverLoader();

    /**
     * 获取数据库连接
     */
    public Connection getConnection(DatabaseConfig config) throws SQLException {
        String url = config.buildJdbcUrl();
        log.debug("Connecting via pool to: {}", JdbcUrlParser.redactSecrets(url));

        secretResolver.populatePassword(config);
        Connection connection;
        try {
            connection = pooledDataSourceManager.get(config).getConnection();
        } catch (SQLException e) {
            String standard = standardErrorMessage(config, e);
            // 无法可靠识别时原样抛出，避免误报
            if (standard == null) {
                throw e;
            }
            throw new SQLException(standard, e.getSQLState(), e.getErrorCode(), e);
        }
        // 切 schema 放在标准化包装之外：「Access denied for database」会被包装成「认证失败」，
        // 人就去改密码，而问题其实是默认 schema 配错了。
        return applyDefaultSchema(connection, config);
    }

    /**
     * 切到别名配置的默认 schema。
     *
     * 连接来自池，可能是上次用别的 schema 用过的，所以每次取出都要设一遍。
     * MySQL / ClickHouse 的 schema 是 JDBC 的 catalog，PostgreSQL / Oracle 才是 schema。
     *
     * <p>切不过去就报错，不退回 URL 里的库：默认 schema 是人明确配的，配错了应该当场看到
     * 「无法切换」，而不是 SQL 悄悄跑在另一个库里——那正是 #38 那种「表明明在、主键却查不到」
     * 的来源。
     */
    private Connection applyDefaultSchema(Connection connection, DatabaseConfig config) throws SQLException {
        String schema = config.getDefaultSchema();
        if (schema == null || schema.isBlank()) {
            return connection;
        }
        String dbType = config.getType() == null ? "" : config.getType().toLowerCase(java.util.Locale.ROOT);
        try {
            if ("mysql".equals(dbType) || "clickhouse".equals(dbType)) {
                connection.setCatalog(schema);
            } else {
                connection.setSchema(schema);
            }
            return connection;
        } catch (SQLException | RuntimeException | AbstractMethodError e) {
            connection.close();
            throw new SQLException("无法切换到别名配置的默认 schema「" + schema + "」：" + e.getMessage()
                    + "。请检查该 schema 是否存在、账号是否有权限，或在设置页清空默认 schema 沿用 URL 里的库。", e);
        }
    }

    public ConnectionTestResult testConnectionDetailed(DatabaseConfig config) {
        try {
            secretResolver.populatePassword(config);
            try (Connection conn = directConnect(config)) {
                if (!conn.isValid(5)) {
                    return ConnectionTestResult.failure("Connection validation failed", "", new ArrayList<>());
                }

                // Try to retrieve server version info
                String serverVersion = queryServerVersion(conn);
                ConnectionTestResult result = ConnectionTestResult.success();
                if (serverVersion != null) {
                    result = result.withServerVersion(serverVersion);
                }
                return result;
            }
        } catch (SQLException e) {
            log.error("Connection test failed ({}): {}", e.getClass().getSimpleName(),
                    JdbcUrlParser.redactSecrets(e.getMessage()));
            Throwable root = rootCause(e);
            return failure(config, e, root, JdbcUrlParser.redactSecrets(e.getMessage()), e);
        } catch (RuntimeException e) {
            log.error("Connection test failed ({}): {}", e.getClass().getSimpleName(),
                    JdbcUrlParser.redactSecrets(e.getMessage()));
            Throwable root = rootCause(e);
            return failure(config, e, root,
                    e.getMessage() == null ? "Runtime failure during connection test"
                            : JdbcUrlParser.redactSecrets(e.getMessage()),
                    new SQLException(e.getMessage(), e));
        }
    }

    /** 能识别出标准错误类型时用标准提示替换原始消息，原始细节保留在 rootCause 中。 */
    private ConnectionTestResult failure(DatabaseConfig config, Throwable failure, Throwable root,
                                         String rawMessage, SQLException forHints) {
        String standard = standardErrorMessage(config, failure);
        return ConnectionTestResult.failure(
                standard == null ? rawMessage : standard,
                root == null ? "" : root.getClass().getSimpleName() + ": "
                        + JdbcUrlParser.redactSecrets(root.getMessage()),
                buildHints(config, forHints, root)
        );
    }

    /**
     * Attempt to retrieve server version via "SELECT version()".
     * Returns null if the query fails or is unsupported.
     */
    String queryServerVersion(Connection conn) {
        try (Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(5);
            try (ResultSet rs = stmt.executeQuery("SELECT version()")) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        } catch (Exception e) {
            log.debug("Could not retrieve server version: {}", e.getMessage());
        }
        return null;
    }

    private Connection directConnect(DatabaseConfig config) throws SQLException {
        Driver driver = driverLoader.load(config);
        String url = config.buildJdbcUrl();
        Properties props = buildDriverProperties(config);
        if (config.getUsername() != null && !config.getUsername().isBlank()) {
            props.setProperty("user", config.getUsername());
        }
        if (config.getPassword() != null) {
            props.setProperty("password", config.getPassword());
        }
        Connection connection = driver.connect(url, props);
        if (connection == null) {
            throw new SQLException("Driver returned null connection for URL: "
                    + JdbcUrlParser.redactSecrets(url)
                    + ". Check URL format and driver compatibility.");
        }
        return connection;
    }

    private Properties buildDriverProperties(DatabaseConfig config) {
        Properties props = new Properties();
        DatabaseStrategies.resolve(config).applyConnectionProperties(config, props);
        return props;
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private List<String> buildHints(DatabaseConfig config, SQLException exception, Throwable root) {
        return DatabaseStrategies.resolve(config).buildConnectionHints(config, exception, root);
    }

    /**
     * 把常见的连接失败归类为 {@link DatabaseErrorMessages} 的标准提示。
     * 只识别有明确信号的三类（驱动缺失 / 认证失败 / 网络不可达），
     * 其余一律返回 null，由调用方保留原始消息——宁可不包装也不误报。
     */
    String standardErrorMessage(DatabaseConfig config, Throwable failure) {
        String dbType = config.getType() == null || config.getType().isBlank() ? "database" : config.getType();
        List<Throwable> chain = causeChain(failure);
        for (Throwable cause : chain) {
            if (cause instanceof ClassNotFoundException || cause instanceof NoClassDefFoundError) {
                return DatabaseErrorMessages.driverMissing(dbType);
            }
        }
        for (Throwable cause : chain) {
            if (isAuthFailure(cause)) {
                return DatabaseErrorMessages.authFailed(dbType);
            }
        }
        for (Throwable cause : chain) {
            if (isNetworkFailure(cause)) {
                // 只配置了 jdbcUrl 时没有可靠的 host/port，宁可不包装
                if (config.getHost() == null || config.getHost().isBlank() || config.getPort() <= 0) {
                    return null;
                }
                return DatabaseErrorMessages.networkUnreachable(config.getHost(), config.getPort());
            }
        }
        return null;
    }

    private boolean isAuthFailure(Throwable cause) {
        if (!(cause instanceof SQLException sql)) {
            return false;
        }
        // 28000 = invalid authorization (MySQL/通用)，28P01 = PostgreSQL invalid_password
        String state = sql.getSQLState();
        if ("28000".equals(state) || "28P01".equals(state)) {
            return true;
        }
        String message = sql.getMessage() == null ? "" : sql.getMessage().toLowerCase(java.util.Locale.ROOT);
        // ORA-01017 = invalid username/password；AUTHENTICATION_FAILED = ClickHouse code 516
        return message.contains("ora-01017") || message.contains("authentication_failed");
    }

    private boolean isNetworkFailure(Throwable cause) {
        if (cause instanceof java.net.UnknownHostException
                || cause instanceof java.net.NoRouteToHostException
                || cause instanceof java.net.ConnectException
                || cause instanceof java.net.SocketTimeoutException) {
            return true;
        }
        // 08S01 = communications link failure，08001 = 无法建立连接
        return cause instanceof SQLException sql
                && ("08S01".equals(sql.getSQLState()) || "08001".equals(sql.getSQLState()));
    }

    private List<Throwable> causeChain(Throwable throwable) {
        List<Throwable> chain = new ArrayList<>();
        Throwable current = throwable;
        while (current != null && !chain.contains(current)) {
            chain.add(current);
            current = current.getCause();
        }
        return chain;
    }
}
