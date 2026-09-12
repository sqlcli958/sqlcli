package com.sqlcli.graph.ui;

import java.net.URI;
import java.util.Set;

/**
 * 判断一个 Origin 头是不是指向本机这台 UI 服务器。
 *
 * <p>不能拿字符串直接比：<b>{@code localhost} 和 {@code 127.0.0.1} 是同一台服务器的两种写法</b>，
 * 而浏览器发的 Origin 就是用户地址栏里的那一种。服务器启动时只能挑一种打印出来，
 * 挑哪种都会把另一种全部拒掉——症状是所有写操作 403，读操作却正常（读不校验 Origin），
 * 看起来像「保存失败」而不是「跨域被拦」。
 *
 * <p>把回环地址的几种写法当作同一个 host，其余部分（scheme、端口）仍然精确匹配，
 * 也仍然拒绝带 userInfo / path / query / fragment 的畸形 Origin。
 */
public final class OriginCheck {

    /** 回环地址的等价写法。多一种写法就多一批被误杀的写请求。 */
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    private OriginCheck() {
    }

    /**
     * @param allowedOrigin 服务器自己的 Origin，形如 {@code http://localhost:8080}
     * @param requestOrigin 请求头里的 Origin；null 或空表示非浏览器客户端（CLI、curl），放行
     */
    public static boolean allows(String allowedOrigin, String requestOrigin) {
        if (requestOrigin == null || requestOrigin.isBlank()) {
            return true;
        }
        if (allowedOrigin == null || allowedOrigin.isBlank()) {
            return false;
        }
        try {
            return matches(URI.create(allowedOrigin), URI.create(requestOrigin));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 两个已解析的 Origin 是否指向同一台服务器。 */
    public static boolean matches(URI allowed, URI request) {
        if (allowed == null || request == null || !isBareOrigin(request) || !isBareOrigin(allowed)) {
            return false;
        }
        return allowed.getScheme() != null
                && allowed.getScheme().equalsIgnoreCase(request.getScheme())
                && sameHost(allowed.getHost(), request.getHost())
                && effectivePort(allowed) == effectivePort(request);
    }

    /** Origin 只能是 scheme://host[:port]，带路径或凭据的一律不认。 */
    private static boolean isBareOrigin(URI uri) {
        return uri.getHost() != null
                && uri.getUserInfo() == null
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null
                && (uri.getRawPath() == null || uri.getRawPath().isEmpty());
    }

    private static boolean sameHost(String allowed, String request) {
        if (allowed == null || request == null) {
            return false;
        }
        if (allowed.equalsIgnoreCase(request)) {
            return true;
        }
        return LOOPBACK.contains(allowed.toLowerCase()) && LOOPBACK.contains(request.toLowerCase());
    }

    private static int effectivePort(URI origin) {
        if (origin.getPort() >= 0) {
            return origin.getPort();
        }
        return "https".equalsIgnoreCase(origin.getScheme()) ? 443 : 80;
    }
}
