package com.sqlcli.connection;

import com.sqlcli.config.DatabaseConfig;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectionManagerTest {

    private DatabaseConfig config(String type) {
        DatabaseConfig config = new DatabaseConfig();
        config.setType(type);
        config.setHost("db.internal");
        config.setPort(3306);
        return config;
    }

    @Test
    void missingDriverJarBecomesTheStandardDriverMessage() {
        RuntimeException failure = new RuntimeException("Driver not found for class: com.mysql.cj.jdbc.Driver",
                new ClassNotFoundException("com.mysql.cj.jdbc.Driver"));

        String message = new ConnectionManager().standardErrorMessage(config("mysql"), failure);

        assertEquals("JDBC driver not found for mysql. Please add the driver JAR to the drivers/ "
                + "directory and configure it in settings.yaml.", message);
    }

    @Test
    void authenticationFailuresAreRecognisedPerDialect() {
        ConnectionManager manager = new ConnectionManager();

        assertEquals("Authentication failed for mysql. Please check your username and password.",
                manager.standardErrorMessage(config("mysql"),
                        new SQLException("Access denied for user 'app'@'10.0.0.1'", "28000", 1045)));
        assertEquals("Authentication failed for postgresql. Please check your username and password.",
                manager.standardErrorMessage(config("postgresql"),
                        new SQLException("password authentication failed", "28P01")));
        assertTrue(manager.standardErrorMessage(config("oracle"),
                new SQLException("ORA-01017: invalid username/password; logon denied", "72000", 1017))
                .startsWith("Authentication failed for oracle."));
        assertTrue(manager.standardErrorMessage(config("clickhouse"),
                new SQLException("Code: 516. DB::Exception: app: Authentication failed. (AUTHENTICATION_FAILED)"))
                .startsWith("Authentication failed for clickhouse."));
    }

    @Test
    void unreachableHostsBecomeTheStandardNetworkMessage() {
        ConnectionManager manager = new ConnectionManager();
        SQLException failure = new SQLException("Communications link failure", "08S01",
                new java.net.ConnectException("Connection refused"));

        assertEquals("Cannot connect to db.internal:3306. Please check the host, port, and network connectivity.",
                manager.standardErrorMessage(config("mysql"), failure));

        DatabaseConfig urlOnly = new DatabaseConfig();
        urlOnly.setType("mysql");
        assertNull(manager.standardErrorMessage(urlOnly, failure), "no host/port means no reliable message");
    }

    @Test
    void unrecognisedFailuresAreNotWrapped() {
        assertNull(new ConnectionManager().standardErrorMessage(config("mysql"),
                new SQLException("Table 'app.orders' doesn't exist", "42S02", 1146)));
    }

    @Test
    void serverVersionQueryHasABoundedTimeout() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT version()")).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString(1)).thenReturn("24.8");

        String version = new ConnectionManager().queryServerVersion(connection);

        assertEquals("24.8", version);
        verify(statement).setQueryTimeout(5);
    }
}
