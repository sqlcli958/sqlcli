package com.sqlcli.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * sqlite 分支：没有 host/port/账号密码，也不需要 driverRef。
 * 文件路径认 {@code database}（策略读的就是这个字段），也接受手写的完整 jdbcUrl。
 * 其余库类型的校验规则本测试不重复覆盖（没有改动）。
 */
class AliasConfigValidatorTest {

    @Test
    void sqliteAcceptsADatabasePathWithoutHostPortOrCredentials() {
        DatabaseConfig config = new DatabaseConfig();
        config.setAccessMode("jdbc");
        config.setType("sqlite");
        config.setDatabase("D:/data/shop.db");

        assertDoesNotThrow(() -> AliasConfigValidator.validate(config));
    }

    @Test
    void sqliteAlsoAcceptsAHandWrittenJdbcUrl() {
        DatabaseConfig config = new DatabaseConfig();
        config.setAccessMode("jdbc");
        config.setType("sqlite");
        config.setJdbcUrl("jdbc:sqlite:D:/data/shop.db");

        assertDoesNotThrow(() -> AliasConfigValidator.validate(config));
    }

    @Test
    void sqliteRejectsNeitherDatabaseNorJdbcUrl() {
        DatabaseConfig config = new DatabaseConfig();
        config.setAccessMode("jdbc");
        config.setType("sqlite");

        assertThrows(IllegalArgumentException.class, () -> AliasConfigValidator.validate(config));
    }

    @Test
    void sqliteRejectsAUrlThatIsNotAnActualSqliteUrl() {
        DatabaseConfig config = new DatabaseConfig();
        config.setAccessMode("jdbc");
        config.setType("sqlite");
        config.setJdbcUrl("jdbc:mysql://localhost:3306/app");

        assertThrows(IllegalArgumentException.class, () -> AliasConfigValidator.validate(config));
    }
}
