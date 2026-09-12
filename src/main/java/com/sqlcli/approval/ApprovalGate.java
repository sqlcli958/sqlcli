package com.sqlcli.approval;

import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.runstate.ApprovalRow;
import com.sqlcli.runstate.RunStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 别名上的三个审批开关（查询 / 更新 / 图谱）打开后，操作要先被放行。
 *
 * <p>两种用法，区别只在等不等：
 * <ul>
 *   <li>{@link #awaitApproval} 建请求并阻塞到有人裁决——SQL 执行和回滚走这条，
 *       它们没有「写一半」这种状态，不放行就不能开始；</li>
 *   <li>{@link #request} 只建请求，立刻返回——图谱变更走这条，而且**变更本身还没生效**：
 *       它连同 before/after 一起存进 {@code approval_request.payload}，批准时才写进图谱
 *       （见 {@code WorkspaceMutationService.stageForApproval} / {@code applyApproved}）。
 *       调用方（多半是 agent）拿到审批 id 就走，不必挂十分钟；拒绝则等于什么都没发生。</li>
 * </ul>
 *
 * <p>等待方和审批方是两个进程——CLI 在终端里跑，审批在 Web UI 的评审页点——
 * 所以协调只能走那张共享的 SQLite 表：等待方轮询自己那条记录的状态。
 * 没有回调，也不需要，一秒一次的 SQLite 读在本地是免费的。
 *
 * <p>UI 里发起的回滚同样阻塞在 handler 上（{@code GraphUiApiRouter}），这就要求线程池
 * 比 {@link #MAX_WAITERS} 大得多：挂起的请求占着线程，池子被占满就再也拉不到待审批列表，
 * 等于死锁。{@code GraphUiServer} 的池子按这个上限留了余量，两个数字要一起改。
 */
public class ApprovalGate {
    private static final Logger log = LoggerFactory.getLogger(ApprovalGate.class);

    /** 轮询间隔。人点一次按钮的反应时间尺度，不需要更快。 */
    private static final long POLL_INTERVAL_MS = 1000;

    /** 等待上限。超时置 expired 而不是自动放行——隔了半小时的预检结论早就失效了。 */
    private static final long DEFAULT_TIMEOUT_MS = 10 * 60 * 1000;

    /**
     * 同时挂起的等待数上限。
     *
     * <p>ponytail: 固定 8，够一个人手动操作用。真出现批量场景要改成按需排队，
     * 不是把这个数字调大。
     */
    public static final int MAX_WAITERS = 8;
    private static final AtomicInteger WAITING = new AtomicInteger();

    private final RunStateStore store;
    private final long timeoutMs;

    public ApprovalGate() {
        this(new RunStateStore(), DEFAULT_TIMEOUT_MS);
    }

    public ApprovalGate(RunStateStore store, long timeoutMs) {
        this.store = store;
        this.timeoutMs = timeoutMs;
    }

    /**
     * 前三个对应别名上的开关，没开时直接放行；RECOVERY 没有开关——回滚永远要审批。
     *
     * <p>GRAPH 只走 {@link #request}（不等），其余三个走 {@link #awaitApproval}（等）。
     */
    public enum Kind {
        QUERY("query", "查询"),
        UPDATE("update", "更新"),
        GRAPH("graph", "图谱"),
        RECOVERY("recovery", "回滚");

        private final String code;
        private final String label;

        Kind(String code, String label) {
            this.code = code;
            this.label = label;
        }

        public String code() {
            return code;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 按别名名查开关。
     *
     * <p>ponytail: 每次调用重新解析一遍 aliases.yaml。图谱变更是人手速度的，文件也就几 KB，
     * 换来的是和 CLI 完全一致的配置优先级（环境变量 &gt; settings.aliasesPath &gt; 默认路径）。
     * 真成为热点再加缓存，别为了省这个改成读固定路径——那会让自定义 aliasesPath 的用户
     * 以为开了审批其实没开。
     */
    public static boolean isEnabled(String alias, Kind kind) {
        try {
            return isEnabled(new com.sqlcli.config.AliasResolver().resolve(alias), kind);
        } catch (Exception e) {
            // 只能 debug：日志走 stdout，而 `--json` 的输出契约要求 stdout 只有那一个 JSON。
            // 别名解析不出来时任何针对它的命令本来也跑不通，这里静默降级不会掩盖真问题。
            log.debug("无法解析别名 {} 的审批配置，本次按未开启处理", alias, e);
            return false;
        }
    }

    public static boolean isEnabled(DatabaseConfig config, Kind kind) {
        if (config == null) return false;
        if (kind == Kind.GRAPH) return graphMode(config) == GraphMode.manual;
        return Boolean.TRUE.equals(switch (kind) {
            case QUERY -> config.getApproveQuery();
            case UPDATE -> config.getApproveUpdate();
            case RECOVERY -> Boolean.TRUE; // 回滚没有开关：调用方无条件走 awaitApproval
            default -> Boolean.FALSE;
        });
    }

    /**
     * 图谱写入的两个结尾，别名上只能选一个。
     *
     * <p>取代了原来的布尔 {@code approveGraph}。逃生门从「绕过审批」改成「自动批准」：
     * 管线仍然只有一条，{@code auto} 的条目照样留底（intent、agent、会话、批次号一个不少），
     * 只是不进待审批队列。删掉逃生门是不行的——CI 和定时脚本会卡死，没人点批准。
     */
    public enum GraphMode {
        /** 条目建好后系统立刻批准并落地 */
        auto,
        /** 条目进队列，等人在评审页裁决，批准才写图谱 */
        manual
    }

    /**
     * 别名的图谱审批模式。
     *
     * <p>优先级：显式的 {@code graphApproval} &gt; 老的布尔 {@code approveGraph}
     * （true → manual）&gt; {@code auto}。
     *
     * <p>没配就是 auto，不是 manual：现有别名一律没配（{@code approveGraph: false}
     * 从来不会被写进 aliases.yaml），默认 manual 会让所有 agent 的图谱写入一夜之间
     * 全部停在队列里。要人工审批就显式写 {@code graphApproval: manual}。
     */
    public static GraphMode graphMode(DatabaseConfig config) {
        if (config == null) return GraphMode.auto;
        String mode = config.getGraphApproval();
        if (mode != null && !mode.isBlank()) {
            try {
                return GraphMode.valueOf(mode.trim().toLowerCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("别名 " + config.getAliasName()
                        + " 的 graphApproval 只能是 auto 或 manual，当前是：" + mode);
            }
        }
        return Boolean.TRUE.equals(config.getApproveGraph()) ? GraphMode.manual : GraphMode.auto;
    }

    /** 建一条待审批请求，不等待。UI 走这条路，等待交给前端轮询。 */
    public long request(String alias, Kind kind, String summary, String detail) {
        return request(alias, kind, summary, detail, null);
    }

    /**
     * @param taskRunId 关联的写任务；null 表示没有（读操作、图谱变更都没有 task_run）。
     *                  审批详情页要展示的预检和时间线在 {@code task_event} 里，
     *                  这个 id 是从审批记录走到那些数据的唯一一条路。
     */
    public long request(String alias, Kind kind, String summary, String detail, Long taskRunId) {
        return request(alias, kind, summary, detail, taskRunId, null);
    }

    /**
     * @param targetId 审批针对的对象 id（图谱关系 / 执行记录）；评审页详情端点
     *                 用它把候选的 diff、证据、校验结果拼出来。null 表示没有明确对象。
     */
    public long request(String alias, Kind kind, String summary, String detail,
                        Long taskRunId, String targetId) {
        return request(alias, kind, summary, detail, taskRunId, targetId, null);
    }

    /**
     * @param payload 图谱审批专用：待批准的变更内容本身。图谱走「先落 db、批准后才写图谱」，
     *                批准时靠它重放（见 {@code WorkspaceMutationService.applyApproved}）。
     */
    public long request(String alias, Kind kind, String summary, String detail,
                        Long taskRunId, String targetId, String payload) {
        Long batchId = com.sqlcli.session.SessionContext.batchId();
        if (batchId == null) {
            return create(alias, kind, summary, detail, taskRunId, targetId, payload,
                    "pending", null, null);
        }
        // 归入批次的条目是 draft：那一批 submit 之前它不进任何队列，也不会落地。
        return create(alias, kind, summary, detail, taskRunId, targetId, payload,
                com.sqlcli.runstate.ApprovalRow.STATUS_DRAFT, batchId, store.nextBatchSeq(batchId));
    }

    /**
     * 别名是 {@code auto} 时的留底条目：建好就是已批准，**不进待审批队列**。
     *
     * <p>为什么还要建：auto 和 manual 的留底必须完全一致，否则「这条改动是谁、
     * 哪次会话、为了什么改的」在 auto 别名上就永远查不到——而那是绝大多数别名。
     */
    public long recordAutoApproved(String alias, Kind kind, String summary, String detail,
                                   String targetId, String payload) {
        Long batchId = com.sqlcli.session.SessionContext.batchId();
        return create(alias, kind, summary, detail, null, targetId, payload, "approved",
                batchId, batchId == null ? null : store.nextBatchSeq(batchId));
    }

    /** 阻塞等待用：无论有没有批次上下文都建一条 pending，等待方要的就是「有人裁决」。 */
    private long requestPending(String alias, Kind kind, String summary, String detail,
                                Long taskRunId, String targetId) {
        return create(alias, kind, summary, detail, taskRunId, targetId, null, "pending", null, null);
    }

    private long create(String alias, Kind kind, String summary, String detail, Long taskRunId,
                        String targetId, String payload, String status, Long batchId, Integer seq) {
        long id = store.createApproval(alias, kind.code(), summary, detail, taskRunId, targetId,
                payload, status, batchId, seq);
        if (id <= 0) {
            throw new IllegalStateException(kind.label() + "审批已开启，但审批请求无法写入运行库，"
                    + "操作已中止。检查 ~/.sql-cli/sqlcli.db 是否可写。");
        }
        return id;
    }

    /**
     * 建请求并阻塞到有人裁决。CLI 走这条路。
     *
     * @throws ApprovalDeniedException 被拒绝或超时
     */
    public void awaitApproval(String alias, Kind kind, String summary, String detail) {
        awaitApproval(alias, kind, summary, detail, null);
    }

    /** @param taskRunId 见 {@link #request(String, Kind, String, String, Long)} */
    public void awaitApproval(String alias, Kind kind, String summary, String detail, Long taskRunId) {
        awaitApproval(alias, kind, summary, detail, taskRunId, null);
    }

    /** @param targetId 见 {@link #request(String, Kind, String, String, Long, String)} */
    public void awaitApproval(String alias, Kind kind, String summary, String detail,
                              Long taskRunId, String targetId) {
        long id = requestPending(alias, kind, summary, detail, taskRunId, targetId);
        if (WAITING.incrementAndGet() > MAX_WAITERS) {
            WAITING.decrementAndGet();
            store.decideApproval(id, "expired", "同时等待审批的操作过多");
            throw new ApprovalDeniedException("同时等待审批的操作过多（上限 " + MAX_WAITERS + "），请先处理已有的待审批项");
        }
        try {
            // 带上 task id：调用方（尤其是 agent）应该把这条命令丢后台，用
            // `sql-cli task status <id>` 回头查结果，而不是干等 10 分钟。
            System.err.println("[审批] " + kind.label() + "操作需要审批（审批 #" + id
                    + (taskRunId == null ? "" : " / 任务 #" + taskRunId) + "），"
                    + "请到 Web UI 的评审页批准后继续；Ctrl+C 取消。"
                    + (taskRunId == null ? "" : "\n[审批] 进度可查：sql-cli task status " + taskRunId));
            waitForDecision(id, kind);
        } finally {
            WAITING.decrementAndGet();
        }
    }

    private void waitForDecision(long id, Kind kind) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            ApprovalRow row = store.findApproval(id);
            if (row == null) {
                throw new ApprovalDeniedException("审批请求 #" + id + " 已不存在");
            }
            switch (row.status()) {
                case "approved" -> {
                    log.debug("approval {} granted", id);
                    return;
                }
                case "rejected" -> throw new ApprovalDeniedException(
                        kind.label() + "操作被拒绝" + reasonSuffix(row.reason()));
                case "expired" -> throw new ApprovalDeniedException(
                        kind.label() + "操作的审批已作废" + reasonSuffix(row.reason()));
                default -> { /* pending，继续等 */ }
            }
            if (System.currentTimeMillis() >= deadline) {
                // 超时由等待方自己置位：审批方可能根本没打开过 UI。
                store.decideApproval(id, "expired", "等待超时");
                throw new ApprovalDeniedException(kind.label() + "操作等待审批超时（"
                        + (timeoutMs / 1000) + " 秒），已作废");
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                store.decideApproval(id, "expired", "等待被中断");
                throw new ApprovalDeniedException("等待审批被中断");
            }
        }
    }

    private static String reasonSuffix(String reason) {
        return reason == null || reason.isBlank() ? "" : "：" + reason;
    }

    /** 摘要用脱敏 SQL，列表里随手可见；原文放 detail。 */
    public static String summarize(String sql) {
        String masked = RunStateStore.maskSql(sql);
        return masked.length() <= 120 ? masked : masked.substring(0, 120) + "…";
    }

    public static class ApprovalDeniedException extends RuntimeException {
        public ApprovalDeniedException(String message) {
            super(message);
        }
    }
}
