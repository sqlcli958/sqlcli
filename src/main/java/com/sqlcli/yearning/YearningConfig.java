package com.sqlcli.yearning;

import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.secret.SecretResolver;
import lombok.Getter;

@Getter
public class YearningConfig {
    private final String aliasName;
    private final String host;
    private final String idc;
    private final String database;
    private final String username;
    private final String credential;
    private final String tokenSecretName;

    public YearningConfig(String aliasName, String host, String idc, String database, String username, String credential) {
        this.aliasName = normalizeOptional(aliasName);
        this.host = trimTrailingSlash(host);
        this.idc = required(idc, "yearningIdc");
        this.database = database;
        this.username = normalizeOptional(username);
        this.credential = required(credential, "Yearning credential");
        this.tokenSecretName = buildTokenSecretName(this.aliasName, host, username, database);
    }

    public static YearningConfig from(DatabaseConfig config, SecretResolver secretResolver) {
        String credential = secretResolver.resolveSecret(config.getSecretRef());
        return new YearningConfig(
                config.getAliasName(),
                required(config.getYearningHost(), "yearningHost"),
                required(config.getYearningIdc(), "yearningIdc"),
                required(config.getYearningDatabase(), "yearningDatabase"),
                config.getUsername(),
                credential
        );
    }

    public boolean usesLogin() {
        return username != null && !username.isBlank();
    }

    public String getPassword() {
        return credential;
    }

    public String getToken() {
        return normalizeToken(credential);
    }

    public YearningConfig withDatabase(String database) {
        return new YearningConfig(
                aliasName,
                host,
                idc,
                required(database, "yearningDatabase"),
                username,
                credential
        );
    }

    private static String trimTrailingSlash(String value) {
        String result = required(value, "yearningHost");
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String normalizeToken(String value) {
        String token = required(value, "Yearning token");
        if (token.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            return token;
        }
        return "Bearer " + token;
    }

    private static String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String buildTokenSecretName(String aliasName, String host, String username, String database) {
        String base = normalizeOptional(aliasName);
        if (base == null) {
            base = required(host, "yearningHost") + "-" + normalizeOptional(username) + "-" + required(database, "yearningDatabase");
        }
        return "yearning-token-" + base.replaceAll("[^A-Za-z0-9_.-]", "-");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
