package com.sqlcli.connection;

import com.sqlcli.config.DatabaseConfig;

import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Driver;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JDBC Driver 加载器，支持外部jar多版本并存
 */
public class DriverLoader {
    private static final Map<String, Driver> LOADED_DRIVERS = new ConcurrentHashMap<>();

    public Driver load(DatabaseConfig config) {
        String driverClass = config.getDriverClass();
        if (driverClass == null || driverClass.isBlank()) {
            throw new RuntimeException("Driver class is not configured for: " + config.getType());
        }

        String key = driverClass + "|" + String.join(";", config.getDriverJars());
        Driver cached = LOADED_DRIVERS.get(key);
        if (cached != null) {
            return cached;
        }

        Driver driver = config.getDriverJars().isEmpty()
                ? loadFromClasspath(driverClass)
                : loadFromJars(driverClass, config.getDriverJars());
        LOADED_DRIVERS.putIfAbsent(key, driver);
        return LOADED_DRIVERS.get(key);
    }

    private Driver loadFromClasspath(String driverClass) {
        try {
            return (Driver) Class.forName(driverClass)
                    .getDeclaredConstructor()
                    .newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Driver not found for class: " + driverClass
                    + ". Configure the JDBC jar path in config/drivers.yaml.", e);
        }
    }

    private Driver loadFromJars(String driverClass, List<String> driverJars) {
        try {
            URL[] urls = new URL[driverJars.size()];
            for (int i = 0; i < driverJars.size(); i++) {
                urls[i] = new java.io.File(driverJars.get(i)).toURI().toURL();
            }
            URLClassLoader classLoader = new URLClassLoader(urls, getClass().getClassLoader());
            return (Driver) Class.forName(driverClass, true, classLoader)
                    .getDeclaredConstructor()
                    .newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to load driver " + driverClass
                    + " from configured jars: " + driverJars, e);
        }
    }
}
