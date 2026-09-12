package com.sqlcli.graph.ui;

import com.sqlcli.config.AliasResolver;
import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sqlcli.graph.workspace.index.WorkspaceIndexStore;
import com.sqlcli.graph.ui.api.GraphUiApiRouter;
import com.sqlcli.graph.ui.api.WorkspaceMutationController;
import com.sqlcli.graph.ui.api.WorkspaceQueryController;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HTTP server for the graph UI.
 * <ul>
 *   <li>Uses com.sun.net.httpserver.HttpServer</li>
 *   <li>Start/stop with graceful shutdown (Ctrl+C)</li>
 *   <li>Registers API routes and static resource handler</li>
 *   <li>Supports random port (--port 0)</li>
 * </ul>
 */
public class GraphUiServer {

    private static final Logger LOG = LoggerFactory.getLogger(GraphUiServer.class);

    private final GraphUiOptions options;
    private final GraphWorkspace workspace;
    private final GraphUiSession session;
    private final JsonHttpSupport jsonSupport;
    private final WorkspaceIndexStore indexStore;
    private final GraphWorkspaceStore workspaceStore;
    private final Map<String, DatabaseConfig> aliases;
    private final CountDownLatch terminationLatch = new CountDownLatch(1);
    private HttpServer server;
    private ExecutorService executor;

    public GraphUiServer(GraphUiOptions options, GraphWorkspace workspace,
                         GraphUiSession session, JsonHttpSupport jsonSupport,
                         WorkspaceIndexStore indexStore) {
        this(options, workspace, session, jsonSupport, indexStore, new GraphWorkspaceStore());
    }

    public GraphUiServer(GraphUiOptions options, GraphWorkspace workspace,
                         GraphUiSession session, JsonHttpSupport jsonSupport,
                         WorkspaceIndexStore indexStore, GraphWorkspaceStore workspaceStore) {
        this(options, workspace, session, jsonSupport, indexStore, workspaceStore, null);
    }

    private GraphUiServer(GraphUiOptions options, GraphWorkspace workspace,
                          GraphUiSession session, JsonHttpSupport jsonSupport,
                          WorkspaceIndexStore indexStore, GraphWorkspaceStore workspaceStore,
                          Map<String, DatabaseConfig> aliases) {
        this.options = options;
        this.workspace = workspace;
        this.session = session;
        this.jsonSupport = jsonSupport;
        this.indexStore = indexStore;
        this.workspaceStore = workspaceStore;
        this.aliases = aliases;
    }

    /**
     * Start the server and return the actual bound port.
     * Does NOT block — the server runs in background threads.
     */
    public int start() throws IOException {
        InetSocketAddress addr = new InetSocketAddress(options.getHost(), options.getPort());
        server = HttpServer.create(addr, 0);
        InetSocketAddress boundAddress = server.getAddress();
        // 绑定通配地址时挑一个能写进 Origin 的具体主机名。
        // 注意这里挑哪一个都不再决定成败：localhost 和 127.0.0.1 的等价判定在
        // OriginCheck 里——之前靠这一行"规范化"，结果是从另一种写法打开 UI 时
        // 所有写操作 403。
        String originHost = boundAddress.getHostString();
        if ("127.0.0.1".equals(originHost) || "0.0.0.0".equals(originHost) || "::".equals(originHost)) {
            originHost = "localhost";
        }
        String allowedOrigin = "http://" + originHost + ":" + boundAddress.getPort();
        if (session != null) {
            session.setAllowedOrigin(allowedOrigin);
        }
        // 文件系统浏览接口（sqlite 路径选择器）的开闸条件：判的是服务器实际监听的地址，
        // 不是上面那个为 Origin 比较而规范化过的字符串——0.0.0.0 在 Origin 比较里被当成
        // "localhost" 的等价写法，但它监听的是所有网卡，不能被这条闸门当成回环。
        // InetAddress.isLoopbackAddress() 对通配地址（0.0.0.0/::）返回 false，语义正好对上。
        boolean loopbackOnly = boundAddress.getAddress() != null
                && boundAddress.getAddress().isLoopbackAddress();

        // Thread pool (fixed size, compatible with Java 17+)
        int threads = Runtime.getRuntime().availableProcessors() * 2;
        // 下限比 ApprovalGate.MAX_WAITERS 高出一截：挂起等审批的请求占着线程，
        // 池子被它们占满就再也拉不到待审批列表，只能等超时。
        executor = Executors.newFixedThreadPool(
                Math.max(threads, com.sqlcli.approval.ApprovalGate.MAX_WAITERS * 3));
        server.setExecutor(executor);

        // Register routes
        registerRoutes(allowedOrigin, loopbackOnly);

        // Graceful shutdown on Ctrl+C
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutting down UI server...");
            stop();
        }));

        server.start();
        syncCandidateApprovals();

        InetSocketAddress boundAddr = server.getAddress();
        String url = "http://" + boundAddr.getHostString() + ":" + boundAddr.getPort() + "/";
        if (options.getAlias() != null && !options.getAlias().isBlank()) {
            url += "?alias=" + URLEncoder.encode(options.getAlias(), StandardCharsets.UTF_8);
        }

        System.out.println("Graph UI server started:");
        System.out.println("  URL:   " + url);
        System.out.println("  Alias: " + (options.getAlias() == null || options.getAlias().isBlank()
                ? "select from homepage" : options.getAlias()));
        System.out.println("  Mode:  " + (session == null || !session.isReadOnly() ? "read-write" : "read-only"));
        System.out.println("  Logs:  console (errors include stack traces)");

        // Auto-open browser
        if (!options.isNoOpen()) {
            tryOpenBrowser(url);
        }

        return boundAddr.getPort();
    }

    /**
     * Stop the server gracefully.
     */
    public void stop() {
        try {
            if (server != null) {
                server.stop(1);
                server = null;
            }
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
            LOG.info("UI server stopped");
        } finally {
            terminationLatch.countDown();
        }
    }

    /**
     * Keep a foreground CLI process alive until {@link #stop()} is called.
     */
    public void awaitTermination() throws InterruptedException {
        terminationLatch.await();
    }

    /**
     * Get the actual bound port (useful when port=0 for auto-select).
     */
    public int getBoundPort() {
        return server != null ? server.getAddress().getPort() : -1;
    }

    /**
     * 把图谱里已有的候选边补进待审批队列。
     *
     * <p>候选边改成排队评审之前写进去的那些，谁也没给它们建过审批行——不补的话它们
     * 就永远躺在图谱里没人处理（候选队列已经从图谱页撤掉了，评审入口只剩审批中心）。
     * 补的动作是幂等的（同 target 已有待审批就跳过），所以每次开 UI 跑一遍就行，
     * 不需要额外的「迁移完成」标记。
     *
     * <p>失败只记日志：这是补数据，不是 UI 起得来的前提条件。
     */
    private void syncCandidateApprovals() {
        if (workspaceStore == null) return;
        // 单别名模式补那一个；多别名模式（dev-restart 起的就是这种）补全部有图谱的别名——
        // 原来只补单别名，多别名下这一步从没跑过，候选永远是候选
        java.util.List<String> targets = new java.util.ArrayList<>();
        String alias = options.getAlias();
        if (alias != null && !alias.isBlank()) {
            targets.add(alias);
        } else if (aliases != null) {
            targets.addAll(aliases.keySet());
        }
        for (String each : targets) {
            try {
                if (!workspaceStore.exists(each)) continue;
                int queued = new com.sqlcli.graph.ui.service.WorkspaceMutationService(
                        workspaceStore, new com.sqlcli.graph.workspace.WorkspaceValidator(),
                        new com.sqlcli.graph.ui.service.WorkspaceLockManager())
                        .syncCandidateApprovals(each);
                if (queued > 0) {
                    LOG.info("{}：补齐 {} 条候选的待审批", each, queued);
                }
            } catch (Exception e) {
                LOG.warn("{}：候选待审批补齐失败: {}", each, e.toString());
            }
        }
    }

    private void registerRoutes(String allowedOrigin, boolean loopbackOnly) {
        if (aliases != null) {
            GraphUiApiRouter router = new GraphUiApiRouter(
                    aliases, options.getAlias(), allowedOrigin, jsonSupport, indexStore, workspaceStore,
                    loopbackOnly);
            server.createContext("/api", router);
            server.createContext("/", new StaticResourceHandler());
            return;
        }
        // API routes - query controller
        WorkspaceQueryController apiController = new WorkspaceQueryController(
                workspace, session, jsonSupport, indexStore, workspaceStore);

        // API routes - mutation controller
        WorkspaceMutationController mutationController = new WorkspaceMutationController(
                workspace, session, jsonSupport, indexStore, workspaceStore);

        // Wire mutation controller into query controller for PATCH delegation
        apiController.setMutationController(mutationController);

        server.createContext("/api/session", apiController);
        server.createContext("/api/workspace", apiController);
        server.createContext("/api/schemas", apiController);
        server.createContext("/api/tables", apiController);
        server.createContext("/api/terms", apiController);
        server.createContext("/api/search", apiController);
        server.createContext("/api/graph", apiController);

        server.createContext("/api/validate", mutationController);
        server.createContext("/api/index", mutationController);
        server.createContext("/api/relations", mutationController);
        server.createContext("/api/validation", mutationController);
        server.createContext("/api/metrics", mutationController);

        // Static resources (catch-all, must be last)
        StaticResourceHandler staticHandler = new StaticResourceHandler();
        server.createContext("/", staticHandler);
    }

    private void tryOpenBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                LOG.info("Browser opened: " + url);
            } else {
                LOG.info("Desktop browse not supported. Open manually: " + url);
            }
        } catch (Exception e) {
            LOG.warn("Failed to open browser", e);
            System.out.println("Open manually: " + url);
        }
    }

    /**
     * Convenience method to create and start a UI server for a given alias.
     */
    public static GraphUiServer createAndStart(GraphUiOptions options) throws IOException {
        GraphWorkspaceStore workspaceStore = new GraphWorkspaceStore();
        Map<String, DatabaseConfig> aliases = new AliasResolver().listAll();
        String alias = options.getAlias();
        if (alias != null && !alias.isBlank() && !aliases.containsKey(alias)) {
            throw new IOException("Unknown alias: " + alias);
        }
        if (alias != null && !alias.isBlank() && !workspaceStore.exists(alias)) {
            throw new IOException("Workspace not found for alias: " + alias
                    + ". Run: sql-cli " + alias + " schema import --from-db");
        }
        JsonHttpSupport jsonSupport = new JsonHttpSupport();
        WorkspaceIndexStore indexStore = new WorkspaceIndexStore();
        GraphUiServer server = new GraphUiServer(
                options, null, null, jsonSupport, indexStore, workspaceStore, aliases);
        server.start();
        return server;
    }
}
