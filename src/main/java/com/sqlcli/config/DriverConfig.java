package com.sqlcli.config;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * JDBC驱动配置
 */
@Getter
@Setter
public class DriverConfig {
    private String name;
    private String dbType;
    private String driverClass;
    private List<String> jars = new ArrayList<>();

    public void setJars(List<String> jars) {
        this.jars = jars == null ? new ArrayList<>() : new ArrayList<>(jars);
    }
}
