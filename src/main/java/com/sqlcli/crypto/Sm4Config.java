package com.sqlcli.crypto;

/**
 * SM4 运行时配置
 */
public class Sm4Config {
    private final String key;
    private final String privateTag;
    private final String version;

    public Sm4Config(String key, String privateTag, String version) {
        this.key = key;
        this.privateTag = privateTag;
        this.version = version;
    }

    public String getKey() {
        return key;
    }

    public String getPrivateTag() {
        return privateTag;
    }

    public String getVersion() {
        return version;
    }
}
