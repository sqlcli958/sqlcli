package com.sqlcli.config;

import com.sqlcli.strategy.DatabaseStrategies;
import lombok.Getter;
import lombok.Setter;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据库配置
 */
@Getter
@Setter
public class DatabaseConfig {
    private String aliasName;   // 别名名称（运行时设置）
    private String type;       // mysql, oracle
    private String driverRef;
    private String driverClass;
    private List<String> driverJars = new ArrayList<>();
    private String secretRef;
    private String jdbcUrl;
    private String host;
    private int port;
    private String database;
    /**
     * 默认 schema：SQL 里不写 schema 前缀时用它。
     *
     * 留空就沿用 JDBC URL 里带的那个。配置在别名上而不是做成 UI 会话级切换——
     * CLI 与 UI 必须是同一行为，否则同一条 SQL 两边跑出不同结果。
     */
    private String defaultSchema;
    private String serviceName;
    private String sid;
    private String username;
    private String password;
    private String description;  // 数据库描述
    private String accessMode = "jdbc";
    private String yearningHost;
    private String yearningIdc;
    private String yearningDatabase;
    private Boolean readonly;
    /**
     * 三个审批开关。打开后对应操作在执行前停住，等人在 Web UI 的评审页放行。
     *
     * <p>放在别名上而不是全局：生产库严、测试库松是同一个人的日常，
     * 开关的自然作用域就是"这个数据源"。
     */
    private Boolean approveQuery;
    private Boolean approveUpdate;
    /**
     * @deprecated 已被 {@link #graphApproval} 取代（true → manual）。老配置仍然读得懂，
     *             新配置请写 {@code graphApproval: auto | manual}。
     */
    @Deprecated
    private Boolean approveGraph;
    /**
     * 图谱写入的两个结尾：{@code auto}（建条目即批准并落地）/ {@code manual}（进队列等人）。
     *
     * <p>没配 = auto。这不是「不审批」——两种模式留底完全一致，区别只是要不要等人。
     * 见 {@code ApprovalGate.graphMode}。
     */
    private String graphApproval;
    private String sm4Key;
    private String sm4PrivateTag;
    private String sm4Version;
    private List<String> decryptColumns = new ArrayList<>();
    private Map<String, String> params = new LinkedHashMap<>();
    private int maximumPoolSize = 3;
    private int minimumIdle = 0;
    private long connectionTimeoutMs = 5000;
    private long idleTimeoutMs = 120000;
    private long maxLifetimeMs = 600000;
    private long keepaliveTimeMs = 0;
    private int defaultQueryLimit = 100;
    /** 查询超时秒数；0 表示不限制。 */
    private int queryTimeoutSeconds = 30;

    public void setDriverJars(List<String> driverJars) {
        this.driverJars = driverJars == null ? new ArrayList<>() : new ArrayList<>(driverJars);
    }

    public void setAccessMode(String accessMode) {
        this.accessMode = accessMode == null || accessMode.isBlank() ? "jdbc" : accessMode;
    }

    public void setDecryptColumns(List<String> decryptColumns) {
        this.decryptColumns = decryptColumns == null ? new ArrayList<>() : new ArrayList<>(decryptColumns);
    }

    public void setParams(Map<String, String> params) {
        this.params = params == null ? new LinkedHashMap<>() : new LinkedHashMap<>(params);
    }

    /**
     * 构建JDBC URL
     */
    public String buildJdbcUrl() {
        return appendParamsToUrl(buildBaseJdbcUrl());
    }

    /**
     * 构建不包含 params 的规范 JDBC URL，供配置持久化使用。
     */
    public String buildBaseJdbcUrl() {
        if (jdbcUrl != null && !jdbcUrl.isBlank()) {
            return JdbcUrlParser.normalize(jdbcUrl);
        }
        return JdbcUrlParser.normalize(DatabaseStrategies.resolve(this).buildJdbcUrl(this));
    }

    private String appendParamsToUrl(String url) {
        if (params == null || params.isEmpty()) {
            return url;
        }
        // 过滤掉连接池参数
        List<String> queryParams = params.entrySet().stream()
                .filter(e -> e.getKey() != null && !e.getKey().isBlank())
                .filter(e -> !isPoolParam(e.getKey()))
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(java.util.stream.Collectors.toList());

        if (queryParams.isEmpty()) {
            return url;
        }

        String queryString = String.join("&", queryParams);
        // 检查 URL 是否已经有参数
        if (url.contains("?")) {
            return url + "&" + queryString;
        } else {
            return url + "?" + queryString;
        }
    }

    private boolean isPoolParam(String key) {
        return "maximumPoolSize".equals(key)
                || "minimumIdle".equals(key)
                || "connectionTimeoutMs".equals(key)
                || "idleTimeoutMs".equals(key)
                || "maxLifetimeMs".equals(key)
                || "keepaliveTimeMs".equals(key)
                || "defaultQueryLimit".equals(key);
    }
}
