package com.sqlcli.graph.ui;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * Session state for the graph UI server.
 * Carries a random token for write-request validation.
 *
 * <p><b>readOnly 的语义（已拍板）：别名的 readonly 配置指的是「数据库只读」。</b>
 * 它随 /api/session 下发给前端，用于顶栏只读状态与 SQL 工作台的写语句提前禁用；
 * 真正的强制在 {@code SqlTaskModule} 的 GuardStage（读同一份别名配置）。
 * 图谱工作区是本地元数据，编辑它不碰数据库，因此不受 readonly 限制——
 * 见 {@link #validateWriteRequest}。
 */
public class GraphUiSession {
    private final String sessionToken;
    private final String alias;
    /** 数据库只读（来自别名的 readonly 配置），不代表图谱工作区只读。 */
    private final boolean readOnly;
    private URI allowedOrigin;
    private long workspaceRevision;

    public GraphUiSession(String alias, boolean readOnly) {
        this.sessionToken = UUID.randomUUID().toString();
        this.alias = alias;
        this.readOnly = readOnly;
    }

    public String getSessionToken() {
        return sessionToken;
    }

    public String getAlias() {
        return alias;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public long getWorkspaceRevision() {
        return workspaceRevision;
    }

    public void setWorkspaceRevision(long revision) {
        this.workspaceRevision = revision;
    }

    public void setAllowedOrigin(String origin) {
        URI parsed = URI.create(origin);
        if (parsed.getScheme() == null || parsed.getHost() == null
                || parsed.getUserInfo() != null || parsed.getRawQuery() != null
                || parsed.getRawFragment() != null
                || parsed.getRawPath() != null && !parsed.getRawPath().isEmpty()) {
            throw new IllegalArgumentException("Invalid allowed Origin");
        }
        this.allowedOrigin = parsed;
    }

    /**
     * Validate that a write request has the correct session token and Origin header.
     *
     * <p>这里的「写请求」全是图谱工作区的写（关系、描述、值域、索引重建），
     * 不包含 SQL 执行。语义决策：别名 readonly 指数据库只读，图谱是本地元数据，
     * 所以<b>不检查 readOnly</b>——历史上这里有 {@code if (readOnly) return false;}，
     * 那会把只读数据源的图谱维护也一并禁掉；SQL 写语句由 SqlTaskModule 按同一份
     * 别名配置拦截，不经过本方法。
     */
    public boolean validateWriteRequest(String token, String origin) {
        if (token == null || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), sessionToken.getBytes(StandardCharsets.UTF_8))) {
            return false;
        }
        if (origin == null || origin.isBlank()) {
            return true;
        }
        if (allowedOrigin == null) {
            return false;
        }
        try {
            // 和无 session 的那些端点共用同一套判定，localhost / 127.0.0.1 视为同一台
            return OriginCheck.matches(allowedOrigin, URI.create(origin));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
