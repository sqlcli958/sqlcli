package com.sqlcli.graph.ui.api;

import com.sqlcli.config.AliasConfigStore;
import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.config.DriverConfig;
import com.sqlcli.config.DriverConfigStore;
import com.sqlcli.graph.ui.JsonHttpSupport;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 驱动管理。
 *
 * <ul>
 *   <li>GET    /api/drivers            - 驱动列表，含 jar 可用性与被引用情况</li>
 *   <li>POST   /api/drivers            - 新建</li>
 *   <li>PATCH  /api/drivers/{name}     - 修改（名称不可改，别名靠它引用）</li>
 *   <li>DELETE /api/drivers/{name}     - 删除，被引用时拒绝</li>
 *   <li>PUT    /api/drivers/{name}/default - 设为该 dbType 的默认驱动</li>
 * </ul>
 */
public class DriverAdminController {

    /** 新建驱动时按类型预填，省掉用户去查驱动类全名。 */
    private static final Map<String, String> DEFAULT_DRIVER_CLASS = Map.of(
            "mysql", "com.mysql.cj.jdbc.Driver",
            "postgresql", "org.postgresql.Driver",
            "oracle", "oracle.jdbc.OracleDriver",
            "clickhouse", "com.clickhouse.jdbc.ClickHouseDriver");

    private final String allowedOrigin;
    private final JsonHttpSupport json;
    private final DriverConfigStore driverStore = new DriverConfigStore();
    private final AliasConfigStore aliasStore = new AliasConfigStore();

    public DriverAdminController(String allowedOrigin, JsonHttpSupport json) {
        this.allowedOrigin = allowedOrigin;
        this.json = json;
    }

    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        if ("/api/drivers".equals(path)) {
            switch (method) {
                case "GET" -> list(exchange);
                case "POST" -> create(exchange);
                default -> exchange.sendResponseHeaders(405, -1);
            }
            return;
        }

        if ("/api/drivers/upload".equals(path)) {
            if ("POST".equals(method)) {
                upload(exchange);
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
            return;
        }

        String rest = path.substring("/api/drivers/".length());
        if (rest.endsWith("/default")) {
            String name = decode(rest.substring(0, rest.length() - "/default".length()));
            if ("PUT".equals(method)) {
                setDefault(exchange, name);
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
            return;
        }

        String name = decode(rest);
        switch (method) {
            case "PATCH" -> update(exchange, name);
            case "DELETE" -> delete(exchange, name);
            default -> exchange.sendResponseHeaders(405, -1);
        }
    }

    // ── 列表 ──

    private void list(HttpExchange exchange) throws IOException {
        Map<String, String> defaults = driverStore.loadDefaults();
        Map<String, DatabaseConfig> aliases = aliasStore.loadAliases();

        List<Map<String, Object>> items = new ArrayList<>();
        driverStore.loadDrivers().forEach((name, driver) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("dbType", driver.getDbType());
            item.put("driverClass", driver.getDriverClass());
            item.put("jars", driver.getJars());
            item.put("isDefault", name.equals(defaults.get(driver.getDbType())));

            List<String> usedBy = aliases.entrySet().stream()
                    .filter(e -> name.equals(e.getValue().getDriverRef()))
                    .map(Map.Entry::getKey)
                    .toList();
            item.put("usedBy", usedBy);

            JarCheck check = checkJars(driver);
            item.put("status", check.status);
            item.put("statusDetail", check.detail);
            items.add(item);
        });
        json.writeOk(exchange, items);
    }

    /**
     * jar 可用性检查。
     *
     * 这是驱动列表最有价值的一列：jar 路径写错时，用户现在看到的是「连接失败」，
     * 离根因隔了十万八千里。这里不连数据库，只看文件在不在、驱动类能不能加载，毫秒级。
     */
    private JarCheck checkJars(DriverConfig driver) {
        List<String> jars = driver.getJars();
        if (jars == null || jars.isEmpty()) {
            // 没配 jar 就得指望驱动在 classpath 里
            return tryLoadClass(driver.getDriverClass(), null)
                    ? new JarCheck("ok", "使用 classpath 中的驱动")
                    : new JarCheck("bad", "未配置 jar，且 classpath 中没有该驱动类");
        }

        List<String> missing = new ArrayList<>();
        List<URL> urls = new ArrayList<>();
        for (String jar : jars) {
            Path path = Paths.get(expandEnv(jar)).normalize().toAbsolutePath();
            if (!Files.isRegularFile(path)) {
                missing.add(jar);
                continue;
            }
            try {
                urls.add(path.toUri().toURL());
            } catch (Exception e) {
                missing.add(jar);
            }
        }
        if (!missing.isEmpty()) {
            return new JarCheck("bad", "jar 文件不存在：" + String.join("、", missing));
        }
        return tryLoadClass(driver.getDriverClass(), urls.toArray(new URL[0]))
                ? new JarCheck("ok", "jar 可用")
                : new JarCheck("warn", "jar 存在但无法加载驱动类 " + driver.getDriverClass()
                        + "，可能是类名写错或 jar 版本不对");
    }

    private boolean tryLoadClass(String driverClass, URL[] urls) {
        if (driverClass == null || driverClass.isBlank()) {
            return false;
        }
        if (urls == null) {
            try {
                Class.forName(driverClass);
                return true;
            } catch (Throwable e) {
                return false;
            }
        }
        try (URLClassLoader loader = new URLClassLoader(urls, getClass().getClassLoader())) {
            Class.forName(driverClass, false, loader);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private record JarCheck(String status, String detail) {
    }

    /** jar 上传上限：Oracle 的 ojdbc + orai18n 加起来也就十几兆，64MB 足够且能挡住误传。 */
    private static final long MAX_JAR_BYTES = 64L * 1024 * 1024;

    /**
     * 上传驱动 jar。
     *
     * 请求体是文件原始字节，文件名和类型走 query 参数——Java 内置 HttpServer 没有
     * multipart 解析，手写一个只为传单个文件不划算。
     *
     * 落到 drivers/{dbType}/ 下，与配置里的 ./drivers/... 相对路径保持一致
     * （启动脚本会 cd 到 sql-cli 安装目录，相对路径以那里为基准）。
     */
    private void upload(HttpExchange exchange) throws IOException {
        if (originRejected(exchange)) {
            return;
        }
        Map<String, String> params = json.parseQueryParams(exchange);
        String dbType = sanitizeSegment(params.get("dbType"));
        String rawName = params.get("filename");
        if (dbType == null) {
            json.writeError(exchange, new IllegalArgumentException("dbType is required"));
            return;
        }
        String fileName = safeJarName(rawName);
        if (fileName == null) {
            json.writeError(exchange, new IllegalArgumentException(
                    "文件名不合法，只接受 .jar 且不能包含路径分隔符"));
            return;
        }

        String lengthHeader = exchange.getRequestHeaders().getFirst("Content-Length");
        if (lengthHeader != null) {
            try {
                if (Long.parseLong(lengthHeader) > MAX_JAR_BYTES) {
                    json.writeError(exchange, new IllegalArgumentException("jar 超过 64MB 上限"));
                    return;
                }
            } catch (NumberFormatException ignored) {
                // 头不可信时下面按实际读取字节数再兜一次
            }
        }

        Path dir = Paths.get("drivers", dbType).toAbsolutePath().normalize();
        Path target = dir.resolve(fileName).normalize();
        // resolve 之后再确认一次没跑出 drivers 目录——文件名已经清洗过，这里是第二道闸
        if (!target.startsWith(dir)) {
            json.writeError(exchange, new IllegalArgumentException("非法的目标路径"));
            return;
        }

        Files.createDirectories(dir);
        Path temp = Files.createTempFile(dir, ".upload-", ".part");
        long written = 0;
        try (InputStream in = exchange.getRequestBody();
             OutputStream out = Files.newOutputStream(temp)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                written += read;
                if (written > MAX_JAR_BYTES) {
                    throw new IOException("jar 超过 64MB 上限");
                }
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            json.writeError(exchange, new RuntimeException("上传失败：" + e.getMessage(), e));
            return;
        }

        if (!looksLikeJar(temp)) {
            Files.deleteIfExists(temp);
            json.writeError(exchange, new IllegalArgumentException(
                    "这不是有效的 jar 文件（缺少 zip 文件头），可能是下载失败的残缺文件"));
            return;
        }

        Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        // 回相对路径：配置里存绝对路径会让换机器就失效
        String relative = "./drivers/" + dbType + "/" + fileName;
        json.writeJson(exchange, 201, Map.of(
                "path", relative,
                "size", written,
                "fileName", fileName));
    }

    /**
     * 文件名校验。
     *
     * 含路径成分的直接拒绝，而不是悄悄取 basename 存下来——
     * 「我传的是 ../../x.jar，结果存成了 x.jar」这种静默修正会让人搞不清到底发生了什么，
     * 正常上传也不会带路径。
     */
    private static String safeJarName(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String name = raw.trim();
        if (!name.equals(Paths.get(name).getFileName().toString())) {
            return null;
        }
        if (name.startsWith(".")
                || !name.toLowerCase(Locale.ROOT).endsWith(".jar")
                || !name.matches("[A-Za-z0-9._+-]{1,120}")) {
            return null;
        }
        return name;
    }

    private static String sanitizeSegment(String raw) {
        if (raw == null || !raw.matches("[A-Za-z0-9_-]{1,32}")) {
            return null;
        }
        return raw.toLowerCase(Locale.ROOT);
    }

    /**
     * 真的是 jar 吗。
     *
     * 只查 zip 魔数（PK）。列表页那个 110 字节的 ojdbc8 就是反例——
     * 下载失败留下的占位文件，扩展名对但内容不是 zip，等到连数据库时才报错太晚了。
     */
    private static boolean looksLikeJar(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(4);
            return head.length == 4 && head[0] == 0x50 && head[1] == 0x4B
                    && head[2] == 0x03 && head[3] == 0x04;
        } catch (IOException e) {
            return false;
        }
    }

    // ── 写操作 ──

    private void create(HttpExchange exchange) throws IOException {
        if (originRejected(exchange)) {
            return;
        }
        Map<String, Object> body = json.readBody(exchange, Map.class);
        String name = str(body.get("name"));
        if (name == null || name.isBlank()) {
            json.writeError(exchange, new IllegalArgumentException("name is required"));
            return;
        }
        if (driverStore.get(name) != null) {
            json.writeError(exchange, new IllegalArgumentException("驱动已存在：" + name));
            return;
        }
        DriverConfig config = new DriverConfig();
        config.setName(name);
        config.setDbType(str(body.get("dbType")));
        String driverClass = str(body.get("driverClass"));
        if (driverClass == null || driverClass.isBlank()) {
            driverClass = DEFAULT_DRIVER_CLASS.get(
                    config.getDbType() == null ? "" : config.getDbType().toLowerCase(Locale.ROOT));
        }
        config.setDriverClass(driverClass);
        config.setJars(jars(body.get("jars")));
        driverStore.saveDriver(config);
        json.writeJson(exchange, 201, Map.of("name", name, "status", "created"));
    }

    private void update(HttpExchange exchange, String name) throws IOException {
        if (originRejected(exchange)) {
            return;
        }
        DriverConfig config = driverStore.get(name);
        if (config == null) {
            json.writeNotFound(exchange, "Unknown driver: " + name);
            return;
        }
        Map<String, Object> body = json.readBody(exchange, Map.class);
        if (body.containsKey("dbType")) {
            config.setDbType(str(body.get("dbType")));
        }
        if (body.containsKey("driverClass")) {
            config.setDriverClass(str(body.get("driverClass")));
        }
        if (body.containsKey("jars")) {
            config.setJars(jars(body.get("jars")));
        }
        config.setName(name);
        driverStore.saveDriver(config);
        json.writeOk(exchange, Map.of("name", name, "status", "updated"));
    }

    /**
     * 删除。
     *
     * 被别名引用时必须拒绝——删掉驱动后别名不会报错，而是等到下次连接时才失败，
     * 那时候用户已经不记得动过驱动配置了。
     */
    private void delete(HttpExchange exchange, String name) throws IOException {
        if (originRejected(exchange)) {
            return;
        }
        if (driverStore.get(name) == null) {
            json.writeNotFound(exchange, "Unknown driver: " + name);
            return;
        }
        List<String> usedBy = aliasStore.loadAliases().entrySet().stream()
                .filter(e -> name.equals(e.getValue().getDriverRef()))
                .map(Map.Entry::getKey)
                .toList();
        if (!usedBy.isEmpty()) {
            json.writeError(exchange, new IllegalArgumentException(
                    "有 " + usedBy.size() + " 个数据源正在使用该驱动：" + String.join("、", usedBy)));
            return;
        }
        driverStore.deleteDriver(name);
        json.writeOk(exchange, Map.of("name", name, "status", "deleted"));
    }

    private void setDefault(HttpExchange exchange, String name) throws IOException {
        if (originRejected(exchange)) {
            return;
        }
        DriverConfig config = driverStore.get(name);
        if (config == null) {
            json.writeNotFound(exchange, "Unknown driver: " + name);
            return;
        }
        if (config.getDbType() == null || config.getDbType().isBlank()) {
            json.writeError(exchange, new IllegalArgumentException("驱动未设置 dbType，无法设为默认"));
            return;
        }
        driverStore.setDefault(config.getDbType(), name);
        json.writeOk(exchange, Map.of("name", name, "dbType", config.getDbType(), "status", "default"));
    }

    // ── 工具 ──

    private boolean originRejected(HttpExchange exchange) throws IOException {
        return !json.requireAllowedOrigin(exchange, allowedOrigin);
    }

    private static String decode(String value) {
        return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> jars(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    result.add(String.valueOf(item).trim());
                }
            }
        }
        return result;
    }

    /** 支持 ${ENV:default} 形式的 jar 路径，与 DriverResolver 保持一致。 */
    private static String expandEnv(String value) {
        if (value == null) {
            return "";
        }
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\\$\\{([^:}]+)(?::([^}]*))?}").matcher(value);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String env = System.getenv(matcher.group(1));
            String replacement = env != null ? env : (matcher.group(2) != null ? matcher.group(2) : "");
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

}
