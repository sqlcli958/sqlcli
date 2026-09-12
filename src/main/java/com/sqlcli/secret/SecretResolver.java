package com.sqlcli.secret;

import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.config.SettingsConfig;

/**
 * 运行时密码解析器
 *
 * 主密码存储方案：
 * - macOS: Keychain（系统级安全存储）
 * - Windows: DPAPI 加密文件（只有当前用户可解密）
 *
 * 用户首次输入主密码后自动存储，后续启动自动读取，无需再次输入
 */
public class SecretResolver {
    private static final String MASTER_KEYRING_NAME = "master";

    private final EncryptedSecretStore encryptedSecretStore = new EncryptedSecretStore();
    private final KeyringSecretStore keyringSecretStore = new KeyringSecretStore();

    /**
     * 通用 secretRef 解析方法
     * 支持 env:, encrypted:, keyring: 三种格式
     */
    public String resolveSecret(String secretRef) {
        if (secretRef == null || secretRef.isBlank()) {
            return null;
        }

        if (secretRef.startsWith("env:")) {
            String envName = secretRef.substring("env:".length());
            String value = System.getenv(envName);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Environment secret not found: " + envName);
            }
            return value;
        }

        if (secretRef.startsWith("encrypted:")) {
            String name = secretRef.substring("encrypted:".length());
            char[] masterPassword = readMasterPassword();
            return encryptedSecretStore.get(name, masterPassword);
        }

        if (secretRef.startsWith("keyring:")) {
            String name = secretRef.substring("keyring:".length());
            return keyringSecretStore.get(name);
        }

        throw new IllegalArgumentException("Unsupported secretRef: " + secretRef);
    }

    public void populatePassword(DatabaseConfig config) {
        if (config.getPassword() != null && !config.getPassword().isBlank()) {
            return;
        }

        String secretRef = config.getSecretRef();
        if (secretRef == null || secretRef.isBlank()) {
            return;
        }

        if (secretRef.startsWith("env:")) {
            String envName = secretRef.substring("env:".length());
            String password = System.getenv(envName);
            if (password == null || password.isBlank()) {
                throw new IllegalArgumentException("Environment secret not found: " + envName);
            }
            config.setPassword(password);
            return;
        }

        if (secretRef.startsWith("encrypted:")) {
            String name = secretRef.substring("encrypted:".length());
            char[] masterPassword = readMasterPassword();
            config.setPassword(encryptedSecretStore.get(name, masterPassword));
            return;
        }

        if (secretRef.startsWith("keyring:")) {
            String name = secretRef.substring("keyring:".length());
            config.setPassword(keyringSecretStore.get(name));
            return;
        }

        throw new IllegalArgumentException("Unsupported secretRef: " + secretRef);
    }

    public void storeEncryptedSecret(String name, String password, char[] masterPassword) {
        encryptedSecretStore.put(name, password, masterPassword);
    }

    /** Store an encrypted secret using the configured or interactively initialized master password. */
    public void storeEncryptedSecret(String name, String password) {
        encryptedSecretStore.put(name, password, readMasterPassword());
    }

    public String getEncryptedSecret(String name, char[] masterPassword) {
        return encryptedSecretStore.get(name, masterPassword);
    }

    public void deleteEncryptedSecret(String name) {
        encryptedSecretStore.delete(name);
    }

    public boolean encryptedSecretExists(String name) {
        return encryptedSecretStore.exists(name);
    }

    public void storePlaintextSecret(String name, String value) {
        SecretRecord record = new SecretRecord();
        record.setName(name);
        record.setType("plaintext");
        record.setValue(value);
        SettingsConfig.getInstance().saveSecret(name, record);
    }

    public String getPlaintextSecret(String name) {
        SecretRecord record = SettingsConfig.getInstance().getSecret(name);
        if (record == null) {
            return null;
        }
        if (!"plaintext".equalsIgnoreCase(record.getType())) {
            throw new IllegalArgumentException("Secret is not plaintext: " + name);
        }
        return record.getValue();
    }

    public boolean plaintextSecretExists(String name) {
        SecretRecord record = SettingsConfig.getInstance().getSecret(name);
        return record != null && "plaintext".equalsIgnoreCase(record.getType());
    }

    public void storeKeyringSecret(String name, String password) {
        keyringSecretStore.put(name, password);
    }

    public void deleteKeyringSecret(String name) {
        keyringSecretStore.delete(name);
    }

    public boolean keyringSecretExists(String name) {
        return keyringSecretStore.exists(name);
    }

    /**
     * 读取主密码
     *
     * 优先级：
     * 1. 系统凭据存储 (Keychain/DPAPI) - 首次输入后自动存储
     * 2. 环境变量 (可选，用于 CI/CD 场景)
     * 3. 交互式输入（首次使用时提示）
     *
     * 存储后无需再次输入，直到用户主动删除
     */
    private char[] readMasterPassword() {
        // 1. 从系统凭据存储读取（优先）
        if (keyringSecretStore.exists(MASTER_KEYRING_NAME)) {
            return keyringSecretStore.get(MASTER_KEYRING_NAME).toCharArray();
        }

        // 2. 从环境变量读取（可选，用于 CI/CD）
        String masterPasswordEnv = SettingsConfig.getInstance().getMasterPasswordEnv();
        String envPassword = System.getenv(masterPasswordEnv);
        if (envPassword != null && !envPassword.isBlank()) {
            // 存储到系统凭据，下次无需环境变量
            keyringSecretStore.put(MASTER_KEYRING_NAME, envPassword);
            return envPassword.toCharArray();
        }

        // 3. 交互式输入（首次使用）
        System.out.println("首次使用，请输入主密码（将安全存储，后续无需再次输入）");
        char[] password = ConsolePrompts.readPassword("Master password: ");

        // 存储到系统凭据
        keyringSecretStore.put(MASTER_KEYRING_NAME, new String(password));
        System.out.println("主密码已安全存储，后续连接将自动读取");

        return password;
    }

}
