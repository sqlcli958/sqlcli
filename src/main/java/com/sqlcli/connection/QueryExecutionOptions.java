package com.sqlcli.connection;

import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.crypto.Sm4Config;
import lombok.Getter;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 查询执行选项
 */
@Getter
public class QueryExecutionOptions {
    private static final Set<String> SUPPORTED_FORMATS = Set.of("csv", "json", "table");
    private final String format;
    private final Set<String> cipherColumns;   // 用于 SQL 改写加密（INSERT/UPDATE/WHERE）
    private final Set<String> decryptColumns;  // 用于 SELECT 结果解密显示
    private final Sm4Config sm4Config;
    private final boolean noDecrypt;

    public QueryExecutionOptions(String format, Set<String> decryptColumns, Sm4Config sm4Config) {
        this(format, decryptColumns, decryptColumns, sm4Config, false);
    }

    /**
     * 兼容构造函数：cipherColumns 和 decryptColumns 相同，noDecrypt=false
     */
    public QueryExecutionOptions(String format, Set<String> cipherColumns, Set<String> decryptColumns, Sm4Config sm4Config) {
        this(format, cipherColumns, decryptColumns, sm4Config, false);
    }

    public QueryExecutionOptions(String format, Set<String> cipherColumns, Set<String> decryptColumns,
                                  Sm4Config sm4Config, boolean noDecrypt) {
        String normalizedFormat = normalizeFormat(format);
        this.format = normalizedFormat;
        this.cipherColumns = normalizeColumns(cipherColumns);
        this.decryptColumns = normalizeColumns(decryptColumns);
        this.sm4Config = sm4Config;
        this.noDecrypt = noDecrypt;
    }

    /**
     * 按别名配置组装选项：别名的 {@code decryptColumns} 加上调用方额外指定的列
     * 共同构成加密改写范围；是否显示解密结果单独由 {@code noDecrypt} 控制。
     *
     * <p>CLI 的 {@code --decrypt-cols} 和 Web UI 历史重放走同一份逻辑——
     * 重放之前各写各的，UI 那份连 SM4 都没做，这里统一之后自动补上。
     */
    public static QueryExecutionOptions forAlias(DatabaseConfig config, String format,
                                                   Set<String> extraDecryptColumns, boolean noDecrypt) {
        Set<String> cipherColumns = new LinkedHashSet<>();
        if (config.getDecryptColumns() != null) {
            cipherColumns.addAll(config.getDecryptColumns());
        }
        if (extraDecryptColumns != null) {
            cipherColumns.addAll(extraDecryptColumns);
        }

        Sm4Config sm4Config = null;
        if (!cipherColumns.isEmpty()) {
            if (config.getSm4Key() == null || config.getSm4Key().isBlank()) {
                throw new IllegalArgumentException(
                        "Alias '" + config.getAliasName() + "' is missing sm4Key for decrypt columns");
            }
            String privateTag = config.getSm4PrivateTag() == null || config.getSm4PrivateTag().isBlank()
                    ? "ENC" : config.getSm4PrivateTag();
            String version = config.getSm4Version() == null || config.getSm4Version().isBlank()
                    ? "240606" : config.getSm4Version();
            sm4Config = new Sm4Config(config.getSm4Key(), privateTag, version);
        }
        return new QueryExecutionOptions(format, cipherColumns, cipherColumns, sm4Config, noDecrypt);
    }

    public static String normalizeFormat(String format) {
        String normalizedFormat = format == null || format.isBlank()
                ? "csv" : format.toLowerCase(Locale.ROOT);
        if (!SUPPORTED_FORMATS.contains(normalizedFormat)) {
            throw new IllegalArgumentException(
                    "Unsupported output format '" + format + "'. Expected one of: csv, json, table");
        }
        return normalizedFormat;
    }

    /**
     * 是否需要对 SQL 进行加密改写（INSERT/UPDATE/WHERE）
     */
    public boolean hasCipherColumns() {
        return sm4Config != null && !cipherColumns.isEmpty();
    }

    /**
     * 是否需要对 SELECT 结果进行解密显示
     */
    public boolean shouldDecrypt(String columnLabel) {
        if (noDecrypt) {
            return false;  // 禁用解密时直接返回 false
        }
        if (sm4Config == null || columnLabel == null) {
            return false;
        }
        return decryptColumns.contains(columnLabel.toLowerCase(Locale.ROOT));
    }

    private Set<String> normalizeColumns(Set<String> columns) {
        Set<String> normalized = new LinkedHashSet<>();
        if (columns == null) {
            return normalized;
        }
        for (String column : columns) {
            if (column != null && !column.isBlank()) {
                normalized.add(column.toLowerCase(Locale.ROOT));
            }
        }
        return normalized;
    }
}
