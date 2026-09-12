package com.sqlcli.secret;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 使用PBKDF2 + AES-GCM进行密文存储
 */
public class CryptoService {
    private static final String KEY_DERIVATION_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String ENCRYPTION_ALGORITHM = "AES/GCM/NoPadding";
    private static final int ITERATIONS = 65536;
    private static final int KEY_LENGTH = 256;
    private static final int SALT_LENGTH = 16;
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 128;

    private final SecureRandom secureRandom = new SecureRandom();

    public SecretRecord encrypt(String name, String plaintext, char[] masterPassword) {
        try {
            byte[] salt = randomBytes(SALT_LENGTH);
            byte[] iv = randomBytes(IV_LENGTH);
            SecretKey key = deriveKey(masterPassword, salt);

            Cipher cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            SecretRecord record = new SecretRecord();
            record.setName(name);
            record.setType("aes-gcm");
            record.setSalt(Base64.getEncoder().encodeToString(salt));
            record.setIv(Base64.getEncoder().encodeToString(iv));
            record.setCiphertext(Base64.getEncoder().encodeToString(ciphertext));
            return record;
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt secret", e);
        }
    }

    public String decrypt(SecretRecord record, char[] masterPassword) {
        try {
            byte[] salt = Base64.getDecoder().decode(record.getSalt());
            byte[] iv = Base64.getDecoder().decode(record.getIv());
            byte[] ciphertext = Base64.getDecoder().decode(record.getCiphertext());
            SecretKey key = deriveKey(masterPassword, salt);

            Cipher cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt secret", e);
        }
    }

    private SecretKey deriveKey(char[] masterPassword, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(masterPassword, salt, ITERATIONS, KEY_LENGTH);
        SecretKeyFactory factory = SecretKeyFactory.getInstance(KEY_DERIVATION_ALGORITHM);
        byte[] encoded = factory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(encoded, "AES");
    }

    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        secureRandom.nextBytes(bytes);
        return bytes;
    }
}
