package com.sqlcli.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * driver 配置持久化
 * 委托给 SettingsConfig（settings.yaml）统一管理
 */
public class DriverConfigStore {

    public Map<String, DriverConfig> loadDrivers() {
        return new LinkedHashMap<>(SettingsConfig.getInstance().getDrivers());
    }

    public Map<String, String> loadDefaults() {
        return SettingsConfig.getInstance().getDriverDefaults();
    }

    public DriverConfig get(String name) {
        return SettingsConfig.getInstance().getDriver(name);
    }

    public void saveDriver(DriverConfig config) {
        SettingsConfig settings = SettingsConfig.getInstance();
        settings.putDriver(config.getName(), config);
        settings.save();
    }

    public void deleteDriver(String name) {
        SettingsConfig settings = SettingsConfig.getInstance();
        settings.removeDriver(name);
        settings.removeDriverDefault(name);
        settings.save();
    }

    public void setDefault(String dbType, String driverRef) {
        SettingsConfig settings = SettingsConfig.getInstance();
        settings.putDriverDefault(dbType, driverRef);
        settings.save();
    }
}
