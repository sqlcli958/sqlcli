package com.sqlcli.secret;

import com.sqlcli.config.SettingsConfig;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * 系统凭据存储
 * macOS: Keychain (security 命令)
 * Windows: DPAPI 加密本地文件（只有当前用户可解密）
 *
 * 安全性：加密数据只有当前登录用户可解密
 * 用户体验：首次输入后永久存储，后续自动读取
 */
public class KeyringSecretStore {
    private static final String SERVICE_NAME = "sql-cli";
    private static final String WINDOWS_STORE_PATH = "config/.secrets-win";

    public void put(String name, String secret) {
        if (isWindows()) {
            putWindows(name, secret);
        } else if (isMacOS()) {
            putMacOS(name, secret);
        } else {
            throw new UnsupportedOperationException("keyring not supported on this platform");
        }
    }

    public String get(String name) {
        if (isWindows()) {
            return getWindows(name);
        } else if (isMacOS()) {
            return getMacOS(name);
        } else {
            throw new UnsupportedOperationException("keyring not supported on this platform");
        }
    }

    public boolean exists(String name) {
        if (isWindows()) {
            return existsWindows(name);
        } else if (isMacOS()) {
            return existsMacOS(name);
        }
        return false;
    }

    public void delete(String name) {
        if (isWindows()) {
            deleteWindows(name);
        } else if (isMacOS()) {
            deleteMacOS(name);
        }
    }

    // ==================== macOS Keychain ====================

    private void putMacOS(String name, String secret) {
        deleteQuietly(name);
        execute(List.of(
                "security", "add-generic-password",
                "-a", name,
                "-s", SERVICE_NAME,
                "-w", secret,
                "-U"
        ), false);
    }

    private String getMacOS(String name) {
        return execute(List.of(
                "security", "find-generic-password",
                "-a", name,
                "-s", SERVICE_NAME,
                "-w"
        ), true).trim();
    }

    private boolean existsMacOS(String name) {
        try {
            execute(List.of(
                    "security", "find-generic-password",
                    "-a", name,
                    "-s", SERVICE_NAME,
                    "-w"
            ), true);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void deleteMacOS(String name) {
        execute(List.of(
                "security", "delete-generic-password",
                "-a", name,
                "-s", SERVICE_NAME
        ), false);
    }

    private void deleteQuietly(String name) {
        try {
            delete(name);
        } catch (Exception ignored) {
        }
    }

    // ==================== Windows DPAPI ====================

    private Path getWindowsStorePath() {
        // 同 AliasConfigStore：相对路径挂 sqlcli.configRoot 覆盖，避免测试写进仓库 config/。
        return SettingsConfig.configRoot().resolve(WINDOWS_STORE_PATH);
    }

    private void putWindows(String name, String secret) {
        // 使用 DPAPI 加密
        String encrypted = encryptWithDPAPI(secret);
        saveToStore(name, encrypted);
    }

    private String getWindows(String name) {
        String encrypted = loadFromStore(name);
        if (encrypted == null || encrypted.isEmpty()) {
            throw new RuntimeException("Secret not found: " + name);
        }
        return decryptWithDPAPI(encrypted);
    }

    private boolean existsWindows(String name) {
        Path path = getWindowsStorePath();
        if (!Files.exists(path)) {
            return false;
        }
        try {
            Properties props = new Properties();
            props.load(Files.newInputStream(path));
            // 空值等同于不存在，否则损坏的条目会被报成"已配置"
            String value = props.getProperty(name);
            return value != null && !value.isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    private void deleteWindows(String name) {
        Path path = getWindowsStorePath();
        if (!Files.exists(path)) {
            return;
        }
        try {
            Properties props = new Properties();
            props.load(Files.newInputStream(path));
            props.remove(name);
            Files.createDirectories(path.getParent());
            props.store(Files.newOutputStream(path), "DPAPI encrypted secrets");
        } catch (Exception e) {
            // ignore
        }
    }

    private void saveToStore(String name, String encrypted) {
        Path path = getWindowsStorePath();
        try {
            Files.createDirectories(path.getParent());
            Properties props = new Properties();
            if (Files.exists(path)) {
                props.load(Files.newInputStream(path));
            }
            props.setProperty(name, encrypted);
            props.store(Files.newOutputStream(path), "DPAPI encrypted secrets");
        } catch (Exception e) {
            throw new RuntimeException("Failed to save secret", e);
        }
    }

    private String loadFromStore(String name) {
        Path path = getWindowsStorePath();
        if (!Files.exists(path)) {
            return null;
        }
        try {
            Properties props = new Properties();
            props.load(Files.newInputStream(path));
            return props.getProperty(name);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 使用 Windows DPAPI 加密数据
     * 加密后的数据只有当前用户可以解密
     * 通过 stdin 传入数据，避免命令注入
     */
    private String encryptWithDPAPI(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            throw new IllegalArgumentException("Cannot store an empty secret");
        }
        // 注意：不可使用 $input，它是 PowerShell 自动变量（管道输入枚举器），赋值不生效
        String script =
                "$stdinData = [Console]::In.ReadToEnd(); " +
                "Add-Type -AssemblyName System.Security; " +
                "if ($stdinData.Length -eq 0) { throw 'empty stdin' }; " +
                "$bytes = [System.Text.Encoding]::UTF8.GetBytes($stdinData); " +
                "$encrypted = [System.Security.Cryptography.ProtectedData]::Protect($bytes, $null, 'CurrentUser'); " +
                "[System.Convert]::ToBase64String($encrypted)";
        String encoded = executeWithStdin(List.of(
                "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script
        ), plaintext).trim();
        if (encoded.isEmpty()) {
            throw new RuntimeException("DPAPI encryption returned no data");
        }
        return encoded;
    }

    /**
     * 使用 Windows DPAPI 解密数据
     * 通过 stdin 传入数据，避免命令注入
     */
    private String decryptWithDPAPI(String encryptedBase64) {
        // 注意：不可使用 $input，它是 PowerShell 自动变量（管道输入枚举器），赋值不生效
        String script =
                "$stdinData = [Console]::In.ReadToEnd().Trim(); " +
                "Add-Type -AssemblyName System.Security; " +
                "if ($stdinData.Length -eq 0) { throw 'empty stdin' }; " +
                "$bytes = [System.Convert]::FromBase64String($stdinData); " +
                "$decrypted = [System.Security.Cryptography.ProtectedData]::Unprotect($bytes, $null, 'CurrentUser'); " +
                "[System.Text.Encoding]::UTF8.GetString($decrypted)";
        return executeWithStdin(List.of(
                "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script
        ), encryptedBase64).trim();
    }

    // ==================== 通用方法 ====================

    private String executeWithStdin(List<String> command, String stdinData) {
        try {
            ProcessBuilder pb = new ProcessBuilder(new ArrayList<>(command));
            pb.redirectErrorStream(false);
            Process process = pb.start();

            // 通过 stdin 传入数据，避免命令注入
            try (OutputStream os = process.getOutputStream()) {
                os.write(stdinData.getBytes(StandardCharsets.UTF_8));
            }

            String stdout = readStream(process.getInputStream());
            String stderr = readStream(process.getErrorStream());

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                String error = stderr.isBlank() ? stdout.trim() : stderr.trim();
                if (!error.isEmpty()) {
                    throw new RuntimeException(error);
                }
            }
            return stdout;
        } catch (Exception e) {
            throw new RuntimeException("Keyring operation failed: " + e.getMessage(), e);
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private boolean isMacOS() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    private String execute(List<String> command, boolean captureStdout) {
        try {
            ProcessBuilder pb = new ProcessBuilder(new ArrayList<>(command));
            pb.redirectErrorStream(false);
            Process process = pb.start();

            String stdout = readStream(process.getInputStream());
            String stderr = readStream(process.getErrorStream());

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                String error = stderr.isBlank() ? stdout.trim() : stderr.trim();
                if (!error.isEmpty()) {
                    throw new RuntimeException(error);
                }
            }
            return captureStdout ? stdout : "";
        } catch (Exception e) {
            throw new RuntimeException("Keyring operation failed: " + e.getMessage(), e);
        }
    }

    private String readStream(InputStream stream) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}