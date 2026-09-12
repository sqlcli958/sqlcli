package com.sqlcli.graph.ui.api;

import com.sqlcli.graph.ui.JsonHttpSupport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 只读的服务端目录浏览：给新建 sqlite 别名的「数据库文件路径」选择器用。
 *
 * <p>浏览器的 {@code <input type="file">} 故意不暴露真实路径（安全设计），拿到的只有
 * 文件名；而 sqlite 别名要存的恰恰是 sql-cli 所在机器上的绝对路径——只能服务端自己
 * 列目录给前端选。这里只读文件/目录的名字和类型，从不读文件内容。
 *
 * <p><b>安全闸门</b>：这个接口让任何打得开 UI 的人枚举服务端文件系统，所以只在 UI
 * 实际监听回环地址时开放（{@link com.sqlcli.graph.ui.GraphUiServer} 用
 * {@code InetAddress.isLoopbackAddress()} 判定，不是看 {@code --host} 参数的字面值，
 * 因为 {@code 0.0.0.0} 这类通配地址不该被当成回环）。UI 默认绑 127.0.0.1，这时候只有
 * 本机能连上来，开放没问题；一旦 {@code --host} 被改成 0.0.0.0 给远程访问用，
 * 这里必须 403——否则等于把整台机器的目录结构开放给任何能连上 UI 端口的人。
 * 以后有人想「顺手放开好方便远程用」，先想清楚这一条。
 */
public class FileBrowseController implements HttpHandler {
    private static final List<String> SQLITE_SUFFIXES = List.of(".db", ".sqlite", ".sqlite3");

    private final boolean loopbackOnly;
    private final JsonHttpSupport json;

    public FileBrowseController(boolean loopbackOnly, JsonHttpSupport json) {
        this.loopbackOnly = loopbackOnly;
        this.json = json;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!loopbackOnly) {
            json.writeError(exchange, new SecurityException(
                    "文件浏览接口仅在 UI 绑定回环地址时开放；当前 UI 绑定了非回环地址（--host），"
                            + "这个接口会让任何能访问此 UI 的人枚举服务端文件系统，已禁用"));
            return;
        }
        Map<String, String> params = json.parseQueryParams(exchange);
        String raw = json.getParam(params, "path", null);
        try {
            Map<String, Object> body = (raw == null || raw.isBlank()) ? listRoots() : listDirectory(raw);
            json.writeOk(exchange, body);
        } catch (IllegalArgumentException e) {
            json.writeError(exchange, e);
        } catch (RuntimeException e) {
            // 路径格式非法（比如非法字符）之类没被上面单独处理的失败，同样是「这个路径不行」
            // 而不是服务器故障——统一按 400 处理，不让它冒泡成 500。
            json.writeError(exchange, new IllegalArgumentException("无法读取该目录：" + e.getMessage()));
        }
    }

    /**
     * 不传路径时的起点：各盘符（Windows 下 {@link File#listRoots()} 就是盘符列表，
     * 类 Unix 下就是 "/"，两个平台用同一行 stdlib 调用覆盖），外加用户主目录方便直达常用位置。
     */
    private Map<String, Object> listRoots() {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (File root : File.listRoots()) {
            entries.add(entry(root.getPath(), root.getPath(), true));
        }
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank() && Files.isDirectory(Path.of(home))) {
            entries.add(entry(home + "（主目录）", home, true));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("path", null);
        body.put("parent", null);
        body.put("entries", entries);
        return body;
    }

    private Map<String, Object> listDirectory(String raw) {
        Path dir = Path.of(raw).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            // 目录不存在、是个文件、或者没权限 stat 都会走到这里——统一当成一种结果处理，
            // 前端只需要展示错误文案，不用区分三种系统调用失败的原因。
            throw new IllegalArgumentException(dir + " 不是可读的目录");
        }
        File[] children = dir.toFile().listFiles();
        List<Map<String, Object>> entries = new ArrayList<>();
        // listFiles() 在没有权限读这个目录时返回 null（不是抛异常）——按需求跳过，
        // 返回一个空列表比让整个请求 500 更符合「浏览目录时一定会撞到权限问题」的预期。
        if (children != null) {
            for (File child : children) {
                try {
                    boolean isDir = child.isDirectory();
                    // 目录全部展示；文件只展示 sqlite 相关后缀，否则几千个文件的目录里翻不动。
                    if (!isDir && !isSqliteFile(child.getName())) {
                        continue;
                    }
                    entries.add(entry(child.getName(), child.getAbsolutePath(), isDir));
                } catch (SecurityException ignored) {
                    // 单个条目 stat 失败（比更常见的整目录权限问题少见，但确实存在）——跳过它，
                    // 不能让一个条目拖垮整次列举。
                }
            }
        }
        entries.sort(Comparator
                .comparing((Map<String, Object> e) -> !Boolean.TRUE.equals(e.get("dir")))
                .thenComparing(e -> String.valueOf(e.get("name")), String.CASE_INSENSITIVE_ORDER));

        Path parent = dir.getParent();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("path", dir.toString());
        body.put("parent", parent == null ? null : parent.toString());
        body.put("entries", entries);
        return body;
    }

    private boolean isSqliteFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String suffix : SQLITE_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> entry(String name, String path, boolean dir) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("path", path);
        item.put("dir", dir);
        return item;
    }
}
