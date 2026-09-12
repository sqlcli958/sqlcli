package com.sqlcli.graph.ui.api;

import com.sqlcli.config.AliasConfigStore;
import com.sqlcli.config.AliasResolver;
import com.sqlcli.config.AliasConfigValidator;
import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.connection.ConnectionManager;
import com.sqlcli.connection.ConnectionTestResult;
import com.sqlcli.graph.ui.JsonHttpSupport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个别名的管理端点：编辑 / 删除 / 连接测试 / 写密码。
 *
 * <p>鉴权跟随 {@code POST /api/aliases}：只校验 Origin（本地 UI 服务器不给别名管理发 session token）。
 * 密码值只进不出——任何响应体都不回显 value。
 */
public class AliasAdminController implements HttpHandler {
    private static final String ROOT = "/api/aliases/";

    private final Map<String, DatabaseConfig> aliases;
    private final String allowedOrigin;
    private final JsonHttpSupport json;
    private final AliasConfigStore store = new AliasConfigStore();

    public AliasAdminController(Map<String, DatabaseConfig> aliases, String allowedOrigin, JsonHttpSupport json) {
        this.aliases = aliases;
        this.allowedOrigin = allowedOrigin;
        this.json = json;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String suffix = path.startsWith(ROOT) ? path.substring(ROOT.length()) : "";
        String action = "";
        int slash = suffix.indexOf('/');
        if (slash >= 0) {
            action = suffix.substring(slash + 1);
            suffix = suffix.substring(0, slash);
        }
        String name = URLDecoder.decode(suffix, StandardCharsets.UTF_8);
        String method = exchange.getRequestMethod();
        try {
            if (name.isBlank()) {
                json.writeNotFound(exchange, "API endpoint not found: " + path);
                return;
            }
            if (!"GET".equals(method) && !json.requireAllowedOrigin(exchange, allowedOrigin)) {
                return;
            }
            DatabaseConfig config = store.get(name);
            if (config == null) {
                json.writeNotFound(exchange, "Unknown alias: " + name);
                return;
            }
            config.setAliasName(name);

            if (action.isEmpty() && "GET".equals(method)) {
                json.writeOk(exchange, detail(name, config));
                return;
            }
            if (action.isEmpty() && "PATCH".equals(method)) {
                handlePatch(exchange, name, config);
                return;
            }
            if (action.isEmpty() && "DELETE".equals(method)) {
                store.deleteAlias(name);
                aliases.remove(name);
                json.writeOk(exchange, Map.of("name", name, "status", "deleted",
                        "note", "图谱工作区与已存密码未删除；如需清理请手工处理"));
                return;
            }
            if ("test".equals(action) && "POST".equals(method)) {
                handleTest(exchange, name, config);
                return;
            }
            if ("secret".equals(action) && "PUT".equals(method)) {
                handleSecret(exchange, name, config);
                return;
            }
            exchange.sendResponseHeaders(405, -1);
        } catch (Exception e) {
            json.writeError(exchange, e);
        }
    }

    private Map<String, Object> detail(String name, DatabaseConfig config) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("dbType", config.getType());
        item.put("driverRef", config.getDriverRef());
        item.put("jdbcUrl", config.getJdbcUrl());
        // sqlite 的「连接目标」是这个文件路径，不是 jdbcUrl——编辑表单要能回填它。
        item.put("database", config.getDatabase());
        item.put("sqliteCreateIfMissing", "true".equalsIgnoreCase(
                config.getParams() == null ? null : config.getParams().get("createIfMissing")));
        item.put("username", config.getUsername());
        item.put("secretRef", maskSecretRef(config.getSecretRef()));
        item.put("secretScheme", scheme(config.getSecretRef()));
        item.put("description", config.getDescription());
        item.put("defaultSchema", config.getDefaultSchema());
        item.put("accessMode", config.getAccessMode());
        item.put("yearningHost", config.getYearningHost());
        item.put("yearningIdc", config.getYearningIdc());
        item.put("yearningDatabase", config.getYearningDatabase());
        item.put("readonly", Boolean.TRUE.equals(config.getReadonly()));
        item.put("approveQuery", Boolean.TRUE.equals(config.getApproveQuery()));
        item.put("approveUpdate", Boolean.TRUE.equals(config.getApproveUpdate()));
        // approveGraph 仍然返回：老 UI / 老脚本读它。真正的判据是 graphApproval。
        item.put("approveGraph", com.sqlcli.approval.ApprovalGate.graphMode(config)
                == com.sqlcli.approval.ApprovalGate.GraphMode.manual);
        item.put("graphApproval", com.sqlcli.approval.ApprovalGate.graphMode(config).name());
        item.put("sm4PrivateTag", config.getSm4PrivateTag());
        item.put("sm4Version", config.getSm4Version());
        item.put("decryptColumns", config.getDecryptColumns() == null
                ? List.of() : new ArrayList<>(config.getDecryptColumns()));
        return item;
    }

    @SuppressWarnings("unchecked")
    private void handlePatch(HttpExchange exchange, String name, DatabaseConfig config) throws IOException {
        Map<String, Object> body = json.readBody(exchange, Map.class);
        if (body == null || body.isEmpty()) {
            throw new IllegalArgumentException("request body is required");
        }
        apply(body, "dbType", config::setType);
        apply(body, "driverRef", config::setDriverRef);
        apply(body, "jdbcUrl", config::setJdbcUrl);
        apply(body, "host", config::setHost);
        apply(body, "database", config::setDatabase);
        apply(body, "serviceName", config::setServiceName);
        apply(body, "sid", config::setSid);
        apply(body, "username", config::setUsername);
        apply(body, "secretRef", config::setSecretRef);
        apply(body, "description", config::setDescription);
        apply(body, "defaultSchema", config::setDefaultSchema);
        apply(body, "accessMode", config::setAccessMode);
        apply(body, "yearningHost", config::setYearningHost);
        apply(body, "yearningIdc", config::setYearningIdc);
        apply(body, "yearningDatabase", config::setYearningDatabase);
        apply(body, "sm4Key", config::setSm4Key);
        apply(body, "sm4PrivateTag", config::setSm4PrivateTag);
        apply(body, "sm4Version", config::setSm4Version);
        if (body.containsKey("port")) {
            Object port = body.get("port");
            config.setPort(port == null ? 0 : Integer.parseInt(String.valueOf(port).trim()));
        }
        if (body.containsKey("readonly")) {
            Object readonly = body.get("readonly");
            config.setReadonly(readonly == null ? null : Boolean.valueOf(String.valueOf(readonly)));
        }
        applyBoolean(body, "approveQuery", config::setApproveQuery);
        applyBoolean(body, "approveUpdate", config::setApproveUpdate);
        // 布尔开关和 auto|manual 是同一件事的两种写法，收敛到后者存盘，
        // 否则同一个别名上会同时留下两个来源不一致的配置项。
        if (body.containsKey("approveGraph")) {
            config.setGraphApproval(Boolean.TRUE.equals(body.get("approveGraph"))
                    || "true".equalsIgnoreCase(String.valueOf(body.get("approveGraph")))
                    ? "manual" : "auto");
            config.setApproveGraph(null);
        }
        apply(body, "graphApproval", config::setGraphApproval);
        if (body.containsKey("decryptColumns")) {
            config.setDecryptColumns(toStringList(body.get("decryptColumns")));
        }
        if (body.containsKey("sqliteCreateIfMissing")) {
            // 见 GraphUiApiRouter.handleAddAlias 里同名字段的注释：这是策略层
            // 「文件不存在时拒绝连接」检查的显式开关，持久化进 params 才对后续连接生效。
            if (Boolean.TRUE.equals(body.get("sqliteCreateIfMissing"))
                    || "true".equalsIgnoreCase(String.valueOf(body.get("sqliteCreateIfMissing")))) {
                config.getParams().put("createIfMissing", "true");
            } else {
                config.getParams().remove("createIfMissing");
            }
        }
        AliasConfigValidator.inferDbType(config);
        AliasConfigValidator.validate(config);
        store.saveAlias(name, config);
        refreshLiveConfig(aliases, name);
        json.writeOk(exchange, detail(name, config));
    }

    private void handleTest(HttpExchange exchange, String name, DatabaseConfig config) throws IOException {
        // AliasConfigStore 是裸配置（driverRef/secretRef 未展开），连接测试必须走
        // AliasResolver 的完整解析，否则报 "Driver class is not configured"。
        DatabaseConfig resolved = new com.sqlcli.config.AliasResolver().resolve(name);
        ConnectionTestResult result = new ConnectionManager().testConnectionDetailed(resolved);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("success", result.isSuccess());
        body.put("message", result.getMessage());
        body.put("rootCause", result.getRootCause());
        body.put("hints", result.getHints() == null ? List.of() : result.getHints());
        body.put("serverVersion", result.getServerVersion());
        json.writeOk(exchange, body);
    }

    @SuppressWarnings("unchecked")
    private void handleSecret(HttpExchange exchange, String name, DatabaseConfig config) throws IOException {
        Map<String, Object> body = json.readBody(exchange, Map.class);
        Object raw = body == null ? null : body.get("value");
        String value = raw == null ? null : String.valueOf(raw);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("value is required");
        }
        String secretRef = config.getSecretRef();
        if (secretRef == null || secretRef.isBlank()) {
            throw new IllegalArgumentException("Alias secretRef is required");
        }
        String secretName = secretRef.substring(secretRef.indexOf(':') + 1);
        if (secretName.isBlank()) {
            throw new IllegalArgumentException("secretRef name is required");
        }
        com.sqlcli.secret.SecretResolver resolver = new com.sqlcli.secret.SecretResolver();
        if (secretRef.startsWith("keyring:")) {
            resolver.storeKeyringSecret(secretName, value);
        } else if (secretRef.startsWith("encrypted:")) {
            // ponytail: 复用 SecretResolver 的主密码解析（环境变量优先）；主密码缺失时它会尝试交互式读取，
            // 服务进程里表现为失败——需要非交互时给 masterPasswordEnv 配好环境变量。
            resolver.storeEncryptedSecret(secretName, value);
        } else {
            throw new IllegalArgumentException("Unsupported secretRef: " + maskSecretRef(secretRef));
        }
        json.writeOk(exchange, Map.of("name", name, "secretRef", maskSecretRef(secretRef), "status", "stored"));
    }

    private void apply(Map<String, Object> body, String key, java.util.function.Consumer<String> setter) {
        if (!body.containsKey(key)) return;
        Object value = body.get(key);
        String text = value == null ? null : String.valueOf(value).trim();
        setter.accept(text == null || text.isEmpty() ? null : text);
    }

    /**
     * 把刚存进 yaml 的别名重新解析一遍，替换掉服务器进程里的那份。
     *
     * <p>{@link AliasConfigStore} 给的是<b>裸配置</b>——`driverRef` 还没展开成
     * driverClass 和 jar 列表。直接把它塞进运行中的别名表，之后每一次连接都会报
     * "Driver class is not configured for: mysql"，而且只有重启才好——
     * 改一次别名（包括勾一下审批开关）就足以毁掉这个进程的连接能力。
     * {@link AliasResolver} 才是唯一会做驱动展开的入口，所以刷新必须走它。
     */
    static void refreshLiveConfig(Map<String, DatabaseConfig> aliases, String name) {
        try {
            DatabaseConfig resolved = new AliasResolver().resolve(name);
            resolved.setAliasName(name);
            aliases.put(name, resolved);
        } catch (RuntimeException e) {
            // 保存已经成功了，但新配置解析不出来（比如引用了不存在的驱动）。
            // 与其继续用内存里那份旧的、和 yaml 已经对不上的配置，不如摘掉——
            // 下一次请求报 "Unknown alias" 比用错配置连上另一个库安全。
            aliases.remove(name);
            throw new IllegalStateException("别名 " + name + " 已保存，但无法加载：" + e.getMessage(), e);
        }
    }

    private void applyBoolean(Map<String, Object> body, String key,
                              java.util.function.Consumer<Boolean> setter) {
        if (!body.containsKey(key)) return;
        Object value = body.get(key);
        setter.accept(value == null ? null : Boolean.valueOf(String.valueOf(value)));
    }

    private List<String> toStringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) result.add(String.valueOf(item).trim());
            }
        } else if (value != null) {
            for (String item : String.valueOf(value).split(",")) {
                if (!item.isBlank()) result.add(item.trim());
            }
        }
        return result;
    }

    private String scheme(String secretRef) {
        if (secretRef == null || secretRef.isBlank()) return "";
        int idx = secretRef.indexOf(':');
        return idx < 0 ? "" : secretRef.substring(0, idx);
    }

    /** 与 AliasCommand.maskSecretRef 同语义：只保留 scheme，无 scheme 时整体遮蔽。 */
    private String maskSecretRef(String value) {
        if (value == null || value.isBlank()) return "";
        int idx = value.indexOf(':');
        return idx < 0 ? "***" : value.substring(0, idx + 1) + "***";
    }
}
