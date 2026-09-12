package com.sqlcli.cli;

import com.sqlcli.config.DriverConfig;
import com.sqlcli.config.DriverConfigStore;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

@Command(name = "driver",
         description = "Driver management",
         mixinStandardHelpOptions = true)
public class DriverCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "操作: list, show, add, update, remove, default")
    private String action;

    @Parameters(index = "1", arity = "0..1", description = "驱动名称")
    private String name;

    @Option(names = "--db-type")
    private String dbType;

    @Option(names = "--driver-class")
    private String driverClass;

    @Option(names = "--jar", split = ",")
    private String[] jars;

    @Option(names = "--driver-ref")
    private String driverRef;

    @Option(names = {"-j", "--json"},
            description = "JSON格式输出")
    private boolean jsonOutput;

    private final DriverConfigStore driverStore = new DriverConfigStore();

    @Override
    public Integer call() {
        if (action == null) {
            CommandLine.usage(this, System.out);
            return 0;
        }
        try {
            switch (action.toLowerCase()) {
                case "list" -> list();
                case "show" -> show();
                case "add" -> save(true);
                case "update" -> save(false);
                case "remove" -> remove();
                case "default" -> setDefault();
                default -> throw new IllegalArgumentException("Unknown action: " + action);
            }
            if (!"list".equalsIgnoreCase(action) && !"show".equalsIgnoreCase(action)) {
                AliasCommand.printDeprecationHint();
            }
            return 0;
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        } catch (RuntimeException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private void list() {
        var drivers = driverStore.loadDrivers();
        if (jsonOutput) {
            var data = drivers.entrySet().stream().map(entry -> {
                var value = new java.util.LinkedHashMap<String, Object>();
                DriverConfig config = entry.getValue();
                value.put("name", entry.getKey());
                value.put("dbType", config.getDbType());
                value.put("driverClass", config.getDriverClass());
                value.put("jars", config.getJars());
                return value;
            }).toList();
            CliJson.printSuccess(data);
        } else {
            drivers.forEach((driverName, config) ->
                    System.out.println(driverName + " -> " + config.getDbType() + " / " + config.getDriverClass()));
        }
    }

    private void show() {
        DriverConfig config = requireExisting();
        System.out.println("name: " + config.getName());
        System.out.println("dbType: " + config.getDbType());
        System.out.println("driverClass: " + config.getDriverClass());
        System.out.println("jars: " + String.join(", ", config.getJars()));
    }

    private void save(boolean create) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Please specify driver name");
        }
        DriverConfig config = create ? new DriverConfig() : requireExisting();
        config.setName(name);
        if (create || dbType != null) config.setDbType(dbType);
        if (create || driverClass != null) config.setDriverClass(driverClass);
        if (jars != null) config.setJars(java.util.Arrays.asList(jars));
        validate(config);
        driverStore.saveDriver(config);
        System.out.println((create ? "Driver created: " : "Driver updated: ") + name);
    }

    private void remove() {
        requireExisting();
        driverStore.deleteDriver(name);
        System.out.println("Driver removed: " + name);
    }

    private void setDefault() {
        if (dbType == null || dbType.isBlank()) {
            throw new IllegalArgumentException("--db-type is required");
        }
        if (driverRef == null || driverRef.isBlank()) {
            throw new IllegalArgumentException("--driver-ref is required");
        }
        if (driverStore.get(driverRef) == null) {
            throw new IllegalArgumentException("Unknown driver: " + driverRef);
        }
        driverStore.setDefault(dbType, driverRef);
        System.out.println("Default driver set: " + dbType + " -> " + driverRef);
    }

    private DriverConfig requireExisting() {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Please specify driver name");
        }
        DriverConfig config = driverStore.get(name);
        if (config == null) {
            throw new IllegalStateException("Unknown driver: " + name);
        }
        return config;
    }

    private void validate(DriverConfig config) {
        if (config.getDbType() == null || config.getDbType().isBlank()) {
            throw new IllegalArgumentException("dbType is required");
        }
        if (config.getDriverClass() == null || config.getDriverClass().isBlank()) {
            throw new IllegalArgumentException("driverClass is required");
        }
        if (config.getJars() == null || config.getJars().isEmpty()) {
            throw new IllegalArgumentException("at least one --jar is required");
        }
    }
}
