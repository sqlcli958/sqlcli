package com.sqlcli.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 「这串改动出自哪次会话、哪个 agent、归到哪一批」。
 *
 * <p>写进 {@code approval_request} 和 {@code sql_execution} 的溯源三件套。没有它，
 * 图谱变更流水里永远只有 {@code actor=agent}——回头问「这十条是不是一次梳理做的」
 * 只能靠时间戳猜。
 *
 * <h2>优先级（高到低）</h2>
 * <ol>
 *   <li>{@code --session} / {@code --agent} / {@code --batch} 命令行参数</li>
 *   <li>会话状态文件（{@code sql-cli session begin} 写入，<b>按工作目录隔离</b>）</li>
 *   <li>环境变量 {@code SQLCLI_SESSION} / {@code SQLCLI_AGENT}（仅面向 CI）</li>
 *   <li>都没有 → {@code null}</li>
 * </ol>
 *
 * <p><b>取不到就记 null，不生成占位 id。</b>每条命令一个新 id 等于没有会话，
 * 而「这批改动是一起做的」这个判断会因此变成假的。
 *
 * <p>状态文件按工作目录隔离而不是按进程：agent 的每个 Bash 调用都是新 shell，
 * {@code export} 不跨调用；写进 profile 则永远不变，同样等于没有会话。工作目录是
 * 唯一一个「同一次会话里稳定、不同会话之间大概率不同」的东西。
 *
 * <p>ponytail: 同目录并发跑两个 agent 会串号。设计里明确不做隔离——要分开用
 * {@code --session} 覆盖，为它加锁和引用计数不划算。
 */
public final class SessionContext {

    private static final Logger log = LoggerFactory.getLogger(SessionContext.class);

    private static volatile String sessionOverride;
    private static volatile String agentOverride;
    private static volatile Long batchOverride;

    private SessionContext() {
    }

    /** CLI 解析出的全局参数，进程内一次性设定。null 表示没传这个参数。 */
    public static void applyOverrides(String session, String agent, Long batch) {
        if (session != null && !session.isBlank()) sessionOverride = session.trim();
        if (agent != null && !agent.isBlank()) agentOverride = agent.trim();
        if (batch != null) batchOverride = batch;
    }

    /** 测试用：清掉进程内的覆盖值。 */
    public static void reset() {
        sessionOverride = null;
        agentOverride = null;
        batchOverride = null;
    }

    public static String sessionId() {
        return firstNonBlank(sessionOverride, stateValue("id"), env("SQLCLI_SESSION"));
    }

    public static String agentId() {
        return firstNonBlank(agentOverride, stateValue("agent"), env("SQLCLI_AGENT"));
    }

    /** 这条命令要归进哪一批；null = 独立条目。只认命令行参数——批次是显式动作。 */
    public static Long batchId() {
        return batchOverride;
    }

    // ------------------------------------------------------------ state file

    /** 开一次会话，返回新 id；已经开着就沿用原来的（重复 begin 不该换号）。 */
    public static String begin(String agent) throws IOException {
        Properties existing = state();
        String id = existing.getProperty("id");
        if (id == null || id.isBlank()) {
            id = "s" + Long.toString(System.currentTimeMillis(), 36) + "-"
                    + Integer.toHexString(new java.security.SecureRandom().nextInt(0x10000) | 0x10000).substring(1);
        }
        Properties props = new Properties();
        props.setProperty("id", id);
        props.setProperty("cwd", cwd());
        props.setProperty("startedAt", existing.getProperty("startedAt",
                String.valueOf(System.currentTimeMillis())));
        if (agent != null && !agent.isBlank()) props.setProperty("agent", agent.trim());
        Path file = stateFile();
        Files.createDirectories(file.getParent());
        try (var out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            props.store(out, "sql-cli session · " + cwd());
        }
        return id;
    }

    /** @return true 表示确实有一个会话被关掉了 */
    public static boolean end() throws IOException {
        return Files.deleteIfExists(stateFile());
    }

    /** 当前生效的三件套，`session status` 直接打印它。 */
    public static Map<String, Object> describe() {
        Map<String, Object> item = new LinkedHashMap<>();
        Properties props = state();
        item.put("sessionId", sessionId());
        item.put("agentId", agentId());
        item.put("cwd", cwd());
        item.put("stateFile", stateFile().toString());
        item.put("startedAt", props.getProperty("startedAt") == null
                ? null : Long.valueOf(props.getProperty("startedAt")));
        item.put("source", sessionOverride != null ? "--session"
                : props.getProperty("id") != null ? "state-file"
                : env("SQLCLI_SESSION") != null ? "SQLCLI_SESSION" : "none");
        return item;
    }

    /** {@code ~/.sql-cli/sessions/<工作目录指纹>.properties}。 */
    public static Path stateFile() {
        return home().resolve("sessions").resolve(fingerprint(cwd()) + ".properties");
    }

    private static Properties state() {
        Properties props = new Properties();
        Path file = stateFile();
        if (!Files.isReadable(file)) return props;
        try (var in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(in);
        } catch (IOException e) {
            log.debug("failed to read session state {}", file, e);
        }
        return props;
    }

    private static String stateValue(String key) {
        return state().getProperty(key);
    }

    private static String cwd() {
        Path path = Path.of(System.getProperty("user.dir", "."));
        try {
            return path.toRealPath().toString();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize().toString();
        }
    }

    /** 目录名不能直接当文件名（分隔符、长度、大小写），取 sha-256 前 16 位十六进制。 */
    private static String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    /** 跟 {@code RunStateStore} 用同一个 home 覆盖属性，测试才不会写到真实目录。 */
    private static Path home() {
        String override = System.getProperty("sqlcli.home");
        return override != null && !override.isBlank()
                ? Path.of(override)
                : Path.of(System.getProperty("user.home"), ".sql-cli");
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }
}
