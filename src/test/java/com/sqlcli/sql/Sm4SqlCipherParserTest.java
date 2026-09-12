package com.sqlcli.sql;

import com.sqlcli.connection.QueryExecutionOptions;
import com.sqlcli.crypto.Sm4Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sm4SqlCipherParser 单元测试
 */
class Sm4SqlCipherParserTest {

    private static final String TEST_KEY = "88ED6EA3C8054CD9";
    private static final String PRIVATE_TAG = "ENC";
    private static final String VERSION = "240606";

    private Sm4SqlCipherParser parser;
    private Sm4Config sm4Config;

    @BeforeEach
    void setUp() {
        parser = new Sm4SqlCipherParser();
        sm4Config = new Sm4Config(TEST_KEY, PRIVATE_TAG, VERSION);
    }

    @Test
    @DisplayName("无加密列配置时，SQL 不被改写")
    void noCipherColumns_noRewrite() {
        QueryExecutionOptions options = new QueryExecutionOptions("csv", Set.of(), Set.of(), null);
        String sql = "SELECT * FROM users WHERE name = '张三'";

        String result = parser.rewrite(sql, options);
        assertEquals(sql, result);
    }

    @Test
    @DisplayName("SELECT WHERE 比较表达式 - 单列加密")
    void selectWhereComparison_singleColumn() {
        Set<String> cipherColumns = Set.of("phone");
        QueryExecutionOptions options = new QueryExecutionOptions("csv", cipherColumns, cipherColumns, sm4Config);

        String sql = "SELECT * FROM users WHERE phone = '13800138000'";
        String result = parser.rewrite(sql, options);

        // 验证结果包含 ENC# 前缀的加密值，不再是原始明文
        assertTrue(result.contains("ENC#" + VERSION + "#"));
        assertFalse(result.contains("'13800138000'"));
    }

    @Test
    @DisplayName("SELECT WHERE 比较表达式 - 多列加密")
    void selectWhereComparison_multipleColumns() {
        Set<String> cipherColumns = Set.of("phone", "account");
        QueryExecutionOptions options = new QueryExecutionOptions("csv", cipherColumns, cipherColumns, sm4Config);

        String sql = "SELECT * FROM users WHERE phone = '13800138000' AND account = 'test123'";
        String result = parser.rewrite(sql, options);

        assertTrue(result.contains("ENC#" + VERSION + "#"));
        assertFalse(result.contains("'13800138000'"));
        assertFalse(result.contains("'test123'"));
    }

    @Test
    @DisplayName("SELECT WHERE IN 列表加密")
    void selectWhereInList() {
        Set<String> cipherColumns = Set.of("phone");
        QueryExecutionOptions options = new QueryExecutionOptions("csv", cipherColumns, cipherColumns, sm4Config);

        String sql = "SELECT * FROM users WHERE phone IN ('13800138000', '13900139000')";
        String result = parser.rewrite(sql, options);

        assertTrue(result.contains("ENC#" + VERSION + "#"));
        assertFalse(result.contains("'13800138000'"));
        assertFalse(result.contains("'13900139000'"));
    }

    @Test
    @DisplayName("INSERT VALUES 加密")
    void insertValues() {
        Set<String> cipherColumns = Set.of("phone");
        QueryExecutionOptions options = new QueryExecutionOptions("csv", cipherColumns, cipherColumns, sm4Config);

        String sql = "INSERT INTO users (id, name, phone) VALUES (1, '张三', '13800138000')";
        String result = parser.rewrite(sql, options);

        assertTrue(result.contains("ENC#" + VERSION + "#"));
        assertFalse(result.contains("'13800138000'"));
        assertTrue(result.contains("'张三'")); // name 列不在加密列表中，应保持原样
    }

    @Test
    @DisplayName("INSERT VALUES 多行加密")
    void insertValues_multipleRows() {
        Set<String> cipherColumns = Set.of("phone");
        QueryExecutionOptions options = new QueryExecutionOptions("csv", cipherColumns, cipherColumns, sm4Config);

        String sql = "INSERT INTO users (id, name, phone) VALUES (1, '张三', '13800138000'), (2, '李四', '13900139000')";
        String result = parser.rewrite(sql, options);

        assertTrue(result.contains("ENC#" + VERSION + "#"));
        assertFalse(result.contains("'13800138000'"));
        assertFalse(result.contains("'13900139000'"));
    }

    @Test
    @DisplayName("INSERT VALUES 中包含函数与空字符串字面量时仍应正确加密目标列")
    void insertValues_withFunctionAndEmptyStringLiteral() {
        Set<String> cipherColumns = Set.of("phone", "account");
        QueryExecutionOptions options = new QueryExecutionOptions("csv", cipherColumns, cipherColumns, sm4Config);

        String sql = "INSERT INTO account_info_encrypt (id, phone, account_type, account, del) "
                + "VALUES (REPLACE(UUID(),'-',''), '13913802346', 'PHONE', '13913802346', 0)";
        String result = parser.rewrite(sql, options);

        assertTrue(result.contains("ENC#" + VERSION + "#"));
        assertFalse(result.contains("'13913802346'"));
        assertTrue(result.contains("REPLACE(UUID()"));
        assertTrue(result.contains("'PHONE'"));
    }

    @Test
    @DisplayName("UPDATE SET 子句加密")
    void updateSet() {
        Set<String> cipherColumns = Set.of("phone");
        QueryExecutionOptions options = new QueryExecutionOptions("csv", cipherColumns, cipherColumns, sm4Config);

        String sql = "UPDATE users SET phone = '13800138000' WHERE id = 1";
        String result = parser.rewrite(sql, options);

        assertTrue(result.contains("ENC#" + VERSION + "#"));
        assertFalse(result.contains("'13800138000'"));
        assertTrue(result.contains("WHERE id = 1"));
    }

    @Test
    @DisplayName("UPDATE SET 多列加密")
    void updateSet_multipleColumns() {
        Set<String> cipherColumns = Set.of("phone", "account");
        QueryExecutionOptions options = new QueryExecutionOptions("csv", cipherColumns, cipherColumns, sm4Config);

        String sql = "UPDATE users SET phone = '13800138000', account = 'test123', name = '张三' WHERE id = 1";
        String result = parser.rewrite(sql, options);

        assertTrue(result.contains("ENC#" + VERSION + "#"));
        assertFalse(result.contains("'13800138000'"));
        assertFalse(result.contains("'test123'"));
        assertTrue(result.contains("'张三'")); // name 列不在加密列表中
    }

    @Test
    @DisplayName("UPDATE SET 无 WHERE 子句")
    void updateSet_noWhere() {
        Set<String> cipherColumns = Set.of("phone");
        QueryExecutionOptions options = new QueryExecutionOptions("csv", cipherColumns, cipherColumns, sm4Config);

        String sql = "UPDATE users SET phone = '13800138000'";
        String result = parser.rewrite(sql, options);

        assertTrue(result.contains("ENC#" + VERSION + "#"));
        assertFalse(result.contains("'13800138000'"));
    }

    @Test
    @DisplayName("混合 SQL：UPDATE 的 WHERE 条件和 SET 子句都加密")
    void updateSetAndWhere() {
        Set<String> cipherColumns = Set.of("phone");
        QueryExecutionOptions options = new QueryExecutionOptions("csv", cipherColumns, cipherColumns, sm4Config);

        String sql = "UPDATE users SET phone = '13900139000' WHERE phone = '13800138000'";
        String result = parser.rewrite(sql, options);

        // SET 子句和 WHERE 子句都应该被加密
        assertTrue(result.contains("ENC#" + VERSION + "#"));
        assertFalse(result.contains("'13800138000'"));
        assertFalse(result.contains("'13900139000'"));
    }

    @Test
    @DisplayName("LIKE 模式加密")
    void selectWhereLike() {
        Set<String> cipherColumns = Set.of("phone");
        QueryExecutionOptions options = new QueryExecutionOptions("csv", cipherColumns, cipherColumns, sm4Config);

        String sql = "SELECT * FROM users WHERE phone LIKE '138%'";
        String result = parser.rewrite(sql, options);

        assertTrue(result.contains("ENC#" + VERSION + "#"));
        assertFalse(result.contains("'138%'"));
    }

    @Test
    @DisplayName("列名带表别名前缀")
    void columnWithTableAlias() {
        Set<String> cipherColumns = Set.of("phone");
        QueryExecutionOptions options = new QueryExecutionOptions("csv", cipherColumns, cipherColumns, sm4Config);

        String sql = "SELECT * FROM users u WHERE u.phone = '13800138000'";
        String result = parser.rewrite(sql, options);

        assertTrue(result.contains("ENC#" + VERSION + "#"));
        assertFalse(result.contains("'13800138000'"));
    }

    @Test
    @DisplayName("非加密列保持原样")
    void nonEncryptColumnUnchanged() {
        Set<String> cipherColumns = Set.of("phone");
        QueryExecutionOptions options = new QueryExecutionOptions("csv", cipherColumns, cipherColumns, sm4Config);

        String sql = "SELECT * FROM users WHERE name = '张三' AND id = 1";
        String result = parser.rewrite(sql, options);

        assertEquals(sql, result); // 没有加密列，SQL 不应被修改
    }

    @Test
    @DisplayName("--no-decrypt 模式下，SQL 加密改写仍然生效")
    void noDecrypt_sqlStillEncrypted() {
        Set<String> cipherColumns = Set.of("phone");
        Set<String> decryptColumns = Set.of();  // 空集合，模拟 --no-decrypt
        QueryExecutionOptions options = new QueryExecutionOptions("csv", cipherColumns, decryptColumns, sm4Config, true);

        // SELECT WHERE 加密改写仍然生效
        String selectSql = "SELECT * FROM users WHERE phone = '13800138000'";
        String selectResult = parser.rewrite(selectSql, options);
        assertTrue(selectResult.contains("ENC#" + VERSION + "#"));

        // INSERT 加密改写仍然生效
        String insertSql = "INSERT INTO users (id, name, phone) VALUES (1, '张三', '13800138000')";
        String insertResult = parser.rewrite(insertSql, options);
        assertTrue(insertResult.contains("ENC#" + VERSION + "#"));
    }
}
