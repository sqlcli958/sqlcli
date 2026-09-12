package com.sqlcli.secret;

import lombok.Getter;
import lombok.Setter;

/**
 * 密文记录
 */
@Getter
@Setter
public class SecretRecord {
    private String name;
    private String type;
    private String salt;
    private String iv;
    private String ciphertext;
    private String value;

}
