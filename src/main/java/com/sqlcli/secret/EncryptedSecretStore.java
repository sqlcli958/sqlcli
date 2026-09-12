package com.sqlcli.secret;

import com.sqlcli.config.SettingsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 settings.yaml 的密文存储
 */
public class EncryptedSecretStore {
    private static final Logger log = LoggerFactory.getLogger(EncryptedSecretStore.class);
    private final CryptoService cryptoService = new CryptoService();

    public void put(String name, String plaintext, char[] masterPassword) {
        SecretRecord record = cryptoService.encrypt(name, plaintext, masterPassword);
        SettingsConfig.getInstance().saveSecret(name, record);
    }

    public String get(String name, char[] masterPassword) {
        SecretRecord record = SettingsConfig.getInstance().getSecret(name);
        if (record == null) {
            throw new IllegalArgumentException("Encrypted secret not found: " + name);
        }
        return cryptoService.decrypt(record, masterPassword);
    }

    public boolean exists(String name) {
        return SettingsConfig.getInstance().hasSecret(name);
    }

    public void delete(String name) {
        SettingsConfig.getInstance().deleteSecret(name);
    }
}