package com.sqlcli.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守卫测试：断言测试进程解析出的配置路径不落在仓库根的 config/ 下。
 *
 * <p>这条比它守卫的那个修复本身更重要——它把「以后有人新写个测试，
 * 以仓库根为 CWD 跑起来，忘了隔离 config/」变成一条会红的用例，而不是
 * 等到 config/aliases.yaml（里面是生产库地址 + secretRef）被覆盖之后才追查到这里。
 */
class SettingsConfigTest {

    @Test
    void resolvedPathsStayOutsideRepoConfigDir() {
        Path repoConfigDir = Path.of("config").toAbsolutePath().normalize();

        Path settingsFile = SettingsConfig.configRoot().resolve("config/settings.yaml")
                .toAbsolutePath().normalize();
        Path aliasesFile = SettingsConfig.getInstance().resolveAliasesPath().toAbsolutePath().normalize();
        Path schemaGraphDir = SettingsConfig.getInstance().resolveSchemaGraphPath().toAbsolutePath().normalize();

        assertFalse(settingsFile.startsWith(repoConfigDir),
                "settings.yaml 解析路径落在仓库 config/ 下，说明 sqlcli.configRoot 没生效: " + settingsFile);
        assertFalse(aliasesFile.startsWith(repoConfigDir),
                "aliases.yaml 解析路径落在仓库 config/ 下，说明 sqlcli.configRoot 没生效: " + aliasesFile);
        assertFalse(schemaGraphDir.startsWith(repoConfigDir),
                "schema-graphs 解析路径落在仓库 config/ 下，说明 sqlcli.configRoot 没生效: " + schemaGraphDir);

        // 正向断言：确认覆盖开关确实被设置了（pom.xml surefire 的职责），
        // 不是因为巧合（比如 CWD 本来就不在仓库根）才没落进仓库。
        assertTrue(!System.getProperty(SettingsConfig.CONFIG_ROOT_PROPERTY, "").isBlank(),
                "本应由 pom.xml surefire 的 systemPropertyVariables 设置 " + SettingsConfig.CONFIG_ROOT_PROPERTY);
    }

    @Test
    void configRootDefaultsToTheHomeDirectoryNotTheWorkingDirectory() {
        String previous = System.getProperty(SettingsConfig.CONFIG_ROOT_PROPERTY);
        System.clearProperty(SettingsConfig.CONFIG_ROOT_PROPERTY);
        try {
            java.nio.file.Path root = SettingsConfig.configRoot();

            // 默认按当前目录解析时踩过两次：换个工作目录启动就读不到图谱（报「工作区不存在」
            // 并在仓库里拉出一个空的 config/schema-graphs），而以仓库根为 CWD 时
            // secret set 会把密钥写进版本控制里的 config/settings.yaml
            assertEquals(
                    java.nio.file.Path.of(System.getProperty("user.home"), ".sql-cli"),
                    root);
            assertTrue(root.isAbsolute(), "基准目录必须是绝对路径，否则又变成跟着 CWD 走");
        } finally {
            if (previous == null) System.clearProperty(SettingsConfig.CONFIG_ROOT_PROPERTY);
            else System.setProperty(SettingsConfig.CONFIG_ROOT_PROPERTY, previous);
        }
    }
}
