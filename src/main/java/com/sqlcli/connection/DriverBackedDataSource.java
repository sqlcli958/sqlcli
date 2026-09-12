package com.sqlcli.connection;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * 基于JDBC Driver对象的数据源，供Hikari包装
 */
public class DriverBackedDataSource implements DataSource {
    private final Driver driver;
    private final String url;
    private final String username;
    private final String password;
    private final Properties baseProperties;

    public DriverBackedDataSource(Driver driver, String url, String username, String password) {
        this(driver, url, username, password, new Properties());
    }

    public DriverBackedDataSource(Driver driver,
                                  String url,
                                  String username,
                                  String password,
                                  Properties extraProperties) {
        this.driver = driver;
        this.url = url;
        this.username = username;
        this.password = password;
        this.baseProperties = new Properties();
        if (extraProperties != null) {
            this.baseProperties.putAll(extraProperties);
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        return driver.connect(url, buildProperties());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        return driver.connect(url, properties);
    }

    private Properties buildProperties() {
        Properties properties = new Properties();
        properties.putAll(baseProperties);
        if (username != null) {
            properties.setProperty("user", username);
        }
        if (password != null) {
            properties.setProperty("password", password);
        }
        return properties;
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return Logger.getGlobal();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        throw new SQLException("Not a wrapper");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return false;
    }
}
