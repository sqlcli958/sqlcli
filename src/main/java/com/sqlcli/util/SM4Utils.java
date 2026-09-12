package com.sqlcli.util;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.util.encoders.Hex;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.Security;

public class SM4Utils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SM4Utils.class);

    static {
        if (Security.getProvider("BC") != null) {
            Security.removeProvider("BC");
        }
        Security.addProvider(new BouncyCastleProvider());
    }

    private static final String ALGORITHM = "SM4/ECB/PKCS5Padding";

    public static String encrypt(String key, String privateTagValue, String versionValue, String data) {
        if(null == key || key.isBlank()) {
            throw new IllegalArgumentException("SM4 key is required");
        }
        if(null == data || data.startsWith(privateTagValue)) {
            return data;
        }
        return privateTagValue + "#" + versionValue + "#" + Hex.toHexString(
                encrypt(key.getBytes(StandardCharsets.UTF_8), data.getBytes(StandardCharsets.UTF_8)));
    }

    public static byte[] encrypt(byte[] key, byte[] data) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(key, ALGORITHM);
            Cipher cipher = Cipher.getInstance(ALGORITHM, "BC");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            return cipher.doFinal(data);
        } catch (Exception e) {
            log.debug("SM4 encrypt failed", e);
            throw new RuntimeException("SM4 encrypt failed", e);
        }
    }

    public static String decrypt(String key, String privateTagValue, String versionValue, String data) {
        if(null == key || key.isBlank()) {
            throw new IllegalArgumentException("SM4 key is required");
        }
        if(null == data) {
            return null;
        }
        if(!data.startsWith(privateTagValue + "#" + versionValue + "#")) {
            return data;
        } else {
            data = data.substring((privateTagValue + "#" + versionValue + "#").length());
        }
        return new String(decrypt(key.getBytes(StandardCharsets.UTF_8), Hex.decode(data)), StandardCharsets.UTF_8);
    }

    public static byte[] decrypt(byte[] key, byte[] data) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(key, ALGORITHM);
            Cipher cipher = Cipher.getInstance(ALGORITHM, "BC");
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            return cipher.doFinal(data);
        } catch (Exception e) {
            log.debug("SM4 decrypt failed", e);
            throw new RuntimeException("SM4 decrypt failed", e);
        }
    }

}
