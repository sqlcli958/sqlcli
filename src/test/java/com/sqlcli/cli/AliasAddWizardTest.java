package com.sqlcli.cli;

import com.sqlcli.config.DriverConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AliasAddWizardTest {

    @Test
    void createsJdbcDraftWithExistingDriverWithoutWritingFiles() {
        DriverConfig mysql = driver("mysql8", "mysql", "com.mysql.cj.jdbc.Driver", "./drivers/mysql.jar");
        FakePrompt prompt = new FakePrompt(
                "1", // JDBC
                "1", // MySQL
                "",  // default driver
                "db.example.com", "", "application", "app_user",
                "2", "APP_DB_PASSWORD", // env secret
                "应用数据库", "y", "n" // description, readonly, SM4
        );

        AliasAddWizard.Result result = new AliasAddWizard(prompt,
                Map.of("mysql8", mysql), Map.of("mysql", "mysql8"))
                .run("my-db").orElseThrow();

        assertEquals("mysql8", result.config().getDriverRef());
        assertEquals("jdbc:mysql://db.example.com:3306/application", result.config().buildJdbcUrl());
        assertEquals("env:APP_DB_PASSWORD", result.config().getSecretRef());
        assertNull(result.secretValue());
        assertTrue(result.config().getReadonly());
        assertNull(result.customDriver());
    }

    @Test
    void createsReusableCustomDriverDraftFromAnExternalJar(@TempDir Path tempDir) throws Exception {
        Path jar = Files.writeString(tempDir.resolve("vendor-driver.jar"), "test");
        FakePrompt prompt = new FakePrompt(
                "1", "1", "1", // JDBC, MySQL, only choice is custom driver
                "", "", jar.toString(), "n", "2", // defaults, one jar, direct path
                "localhost", "3307", "orders", "root",
                "1", // keyring
                "secret", "secret", // password twice
                "", "n", "n" // description, readonly, SM4
        );

        AliasAddWizard.Result result = new AliasAddWizard(prompt, Map.of(), Map.of())
                .run("orders-db").orElseThrow();

        assertEquals("orders-db-driver", result.customDriver().config().getName());
        assertEquals(List.of(jar.toAbsolutePath().normalize().toString()),
                result.customDriver().config().getJars());
        assertFalse(result.customDriver().copyJars());
        assertEquals("orders-db-driver", result.config().getDriverRef());
        assertEquals("keyring:orders-db", result.config().getSecretRef());
        assertEquals("secret", result.secretValue());
    }

    @Test
    void createsSqliteDraftWithOnlyAFilePath(@TempDir Path tempDir) throws Exception {
        Path dbFile = Files.writeString(tempDir.resolve("shop.db"), "sqlite-ish");
        FakePrompt prompt = new FakePrompt(
                "1",              // JDBC
                "5",              // SQLite
                dbFile.toString(), // 已存在的文件，直接通过，不触发「新建空库」确认
                "",  "n", "n"      // description, readonly, SM4
        );

        AliasAddWizard.Result result = new AliasAddWizard(prompt, Map.of(), Map.of())
                .run("shop-local").orElseThrow();

        assertEquals("sqlite", result.config().getType());
        assertEquals(dbFile.toAbsolutePath().normalize().toString().replace('\\', '/'),
                result.config().getDatabase());
        assertNull(result.config().getJdbcUrl());
        // 文件已存在——不需要「新建时放行」这个开关
        assertNull(result.config().getParams().get("createIfMissing"));
        // sqlite 没有账号密码，也不用选驱动——三者都不该被问到、不该落进配置
        assertNull(result.config().getDriverRef());
        assertNull(result.config().getUsername());
        assertNull(result.config().getSecretRef());
        assertNull(result.secretValue());
        assertNull(result.customDriver());
    }

    @Test
    void sqliteRepromptsUntilAMissingPathIsConfirmedOrReplaced(@TempDir Path tempDir) throws Exception {
        Path missing = tempDir.resolve("does-not-exist.db");
        Path existing = Files.writeString(tempDir.resolve("shop.db"), "sqlite-ish");
        FakePrompt prompt = new FakePrompt(
                "1", "5",
                missing.toString(), "n", // 路径不存在，用户拒绝「新建空库」——不能把这个坏路径存进配置
                existing.toString(),     // 重新输入一个已存在的路径
                "", "n", "n"
        );

        AliasAddWizard.Result result = new AliasAddWizard(prompt, Map.of(), Map.of())
                .run("shop-local").orElseThrow();

        assertEquals(existing.toAbsolutePath().normalize().toString().replace('\\', '/'),
                result.config().getDatabase());
        assertNull(result.config().getParams().get("createIfMissing"));
    }

    /**
     * 用户确认新建时，向导只把 params.createIfMissing 存进配置，不碰文件系统——
     * 真正建库是 sqlite 驱动第一次连接时做的事。没有这个开关，
     * {@link com.sqlcli.strategy.SqliteDatabaseStrategy} 会在真正连接时因为文件还不存在而拒绝，
     * 这里用 buildJdbcUrl() 间接验证开关确实生效（不抛异常）。
     */
    @Test
    void sqliteConfirmingANewFileSetsCreateIfMissingWithoutTouchingDisk(@TempDir Path tempDir) throws Exception {
        Path missing = tempDir.resolve("brand-new.db");
        FakePrompt prompt = new FakePrompt(
                "1", "5",
                missing.toString(), "y", // 路径不存在，用户确认新建
                "", "n", "n"
        );

        AliasAddWizard.Result result = new AliasAddWizard(prompt, Map.of(), Map.of())
                .run("shop-local").orElseThrow();

        assertEquals("true", result.config().getParams().get("createIfMissing"));
        assertFalse(Files.exists(missing), "向导只收集配置，不该在这一步真的建文件");
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> result.config().buildJdbcUrl());
    }

    @Test
    void cancellationBeforeCreationStopsCollection() {
        DriverConfig mysql = driver("mysql8", "mysql", "com.mysql.cj.jdbc.Driver", "./drivers/mysql.jar");
        FakePrompt prompt = new FakePrompt(
                "1", "1", "", "", "", "app", "root",
                "2", "APP_PASSWORD", "", "n", ":quit"
        );

        assertThrows(AliasAddWizard.CancelledException.class,
                () -> new AliasAddWizard(prompt, Map.of("mysql8", mysql), Map.of("mysql", "mysql8"))
                        .run("cancelled-db"));
    }

    private static DriverConfig driver(String name, String type, String driverClass, String jar) {
        DriverConfig config = new DriverConfig();
        config.setName(name);
        config.setDbType(type);
        config.setDriverClass(driverClass);
        config.setJars(List.of(jar));
        return config;
    }

    private static final class FakePrompt implements AliasAddWizard.Prompt {
        private final ArrayDeque<String> answers;

        private FakePrompt(String... answers) {
            this.answers = new ArrayDeque<>(List.of(answers));
        }

        @Override
        public String readLine(String prompt) {
            return answers.removeFirst();
        }

        @Override
        public char[] readPassword(String prompt) {
            return answers.removeFirst().toCharArray();
        }

        @Override
        public void println(String message) {
            // Output is intentionally ignored in collection tests.
        }
    }
}
