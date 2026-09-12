package com.sqlcli.cli;

import com.sqlcli.runstate.ApprovalRow;
import com.sqlcli.runstate.RunStateStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 待审批队列的**只读**视图。
 *
 * <pre>
 * sql-cli demo approval            # 等价于 list，只看待审批
 * sql-cli demo approval list --all # 连已裁决的一起看（确认自己那条批了没）
 * </pre>
 *
 * <p><b>为什么只读、为什么还要有它。</b>裁决只在 Web UI 评审页 · 待审批
 * （CLAUDE.md「一件等着我决定的事，只有一个入口」）——这里加一个 approve 就是第二个入口。
 * 但队列**能不能被看见**和**在哪裁决**是两件事：图谱变更走「先落 db、不阻塞调用方」，
 * agent 提交完打印一句「待审批 #395」就走了，之后没有任何东西再提起它。
 * 实测结果是 6 条待审批从 2026-09-03 03:12 一直挂着没人裁决，而同期图谱内容一个字节没长。
 *
 * <p>所以这条命令的消费方是**两个**：agent（干完活能回头报「你有 N 条等着裁决」）
 * 和人（不必先开 UI 才知道队列里有没有东西）。
 *
 * <p>本别名为空时会顺带报一句「其他数据源还有 N 条」：待审批队列是跨别名的
 * （CLI 在别的库上等审批，不该因为这次问的是另一个别名就显示成 0），
 * 只报本别名会造出一个更糟的假阴性——看着像清空了。
 */
@Command(name = "approval",
         description = "待审批队列（只读，裁决在 Web UI 评审页）",
         mixinStandardHelpOptions = true)
public class ApprovalCommand implements Callable<Integer> {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    @Parameters(index = "0", arity = "0..1", description = "list（默认，也是唯一动作）")
    private String action;

    @Option(names = "--all", description = "连已裁决的一起列，不只是待审批")
    private boolean all;

    @Option(names = "--limit", description = "最多列几条（默认 20）")
    private int limit = 20;

    @Option(names = {"-j", "--json"}, description = "JSON 格式输出")
    private boolean jsonOutput;

    private String alias;
    private RunStateStore runState = new RunStateStore();

    public void setAlias(String alias) {
        this.alias = alias;
    }

    /** 测试用：指向临时运行库。 */
    void setRunState(RunStateStore runState) {
        this.runState = runState;
    }

    @Override
    public Integer call() {
        String verb = action == null ? "list" : action.toLowerCase(Locale.ROOT);
        if (!"list".equals(verb)) {
            return fail("未知动作：" + action + "（只有 list；批准和拒绝在 Web UI 评审页）");
        }
        String status = all ? null : ApprovalRow.STATUS_PENDING;
        List<ApprovalRow> rows = runState.listApprovals(alias, status, null, null, limit, 0);
        int pendingHere = runState.countApprovals(alias, ApprovalRow.STATUS_PENDING, null, null);
        int pendingAll = runState.countApprovals(null, ApprovalRow.STATUS_PENDING, null, null);

        if (jsonOutput) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("alias", alias);
            body.put("pending", pendingHere);
            body.put("pendingAllAliases", pendingAll);
            List<Map<String, Object>> items = new ArrayList<>(rows.size());
            for (ApprovalRow row : rows) items.add(rowMap(row));
            body.put("approvals", items);
            CliJson.printSuccess(body);
            return 0;
        }

        if (rows.isEmpty()) {
            System.out.println(all ? "还没有审批记录。" : "没有待审批。");
        } else {
            System.out.println((all ? "审批记录 " : "待审批 ") + rows.size() + " 条（"
                    + alias + "）：");
            for (ApprovalRow row : rows) System.out.println("  " + line(row));
        }
        if (pendingHere > 0) {
            System.out.println("到 Web UI 评审页 · 待审批裁决：sql-cli " + alias + " ui");
        }
        int elsewhere = pendingAll - pendingHere;
        if (elsewhere > 0) {
            System.out.println("其他数据源还有 " + elsewhere + " 条待审批。");
        }
        return 0;
    }

    /** 一条一行：等了多久排在最前——队列腐烂看的就是这个数，不是内容。 */
    private static String line(ApprovalRow row) {
        StringBuilder sb = new StringBuilder();
        sb.append('#').append(row.id())
                .append(" [").append(row.kind()).append('/').append(row.status()).append("] ")
                .append(STAMP.format(Instant.ofEpochMilli(row.createdAt())));
        if (ApprovalRow.STATUS_PENDING.equals(row.status())) {
            sb.append(" · 已等 ").append(waited(row.createdAt()));
        }
        if (row.batchId() != null) {
            sb.append(" · 批次 #").append(row.batchId());
        }
        if (row.agentId() != null && !row.agentId().isBlank()) {
            sb.append(" · ").append(row.agentId());
        }
        sb.append("  ").append(row.summary() == null ? "" : row.summary());
        if (row.targetId() != null && !row.targetId().isBlank()) {
            sb.append("  ").append(row.targetId());
        }
        if (row.reason() != null && !row.reason().isBlank()) {
            sb.append(" —— ").append(row.reason());
        }
        return sb.toString();
    }

    private static String waited(long createdAt) {
        Duration d = Duration.ofMillis(Math.max(0, System.currentTimeMillis() - createdAt));
        if (d.toHours() < 1) return d.toMinutes() + " 分钟";
        if (d.toDays() < 1) return d.toHours() + " 小时";
        return d.toDays() + " 天";
    }

    private static Map<String, Object> rowMap(ApprovalRow row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", row.id());
        map.put("kind", row.kind());
        map.put("status", row.status());
        map.put("summary", row.summary());
        map.put("targetId", row.targetId());
        map.put("batchId", row.batchId());
        map.put("agentId", row.agentId());
        map.put("sessionId", row.sessionId());
        map.put("createdAt", row.createdAt());
        map.put("decidedAt", row.decidedAt());
        map.put("reason", row.reason());
        return map;
    }

    private int fail(String message) {
        if (jsonOutput) {
            CliJson.printFailure("bad_request", message);
        } else {
            System.err.println("Error: " + message);
        }
        return 2;
    }
}
