package com.sqlcli.cli;

import com.sqlcli.parser.SqlStatementAnalyzer;
import com.sqlcli.runstate.ApprovalBatchRow;
import com.sqlcli.runstate.ApprovalRow;
import com.sqlcli.runstate.RunStateStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * 批次：把多条变更作为**一个业务意图**一次提交、一次裁决、原子落地。
 *
 * <pre>
 * sql-cli demo batch begin --intent "梳理报事域状态字段"   # 返回批次号
 * sql-cli demo schema edit --column ... --batch 77         # 只入批次，不落图谱
 * sql-cli demo batch submit 77                             # 封口，进待审批
 * sql-cli demo batch status 77                             # 裁决结果：哪条被否、理由
 * sql-cli demo batch list --draft                          # 还没提交的批次
 * sql-cli demo batch discard 77                            # 丢掉一个草稿批次
 * </pre>
 *
 * <p><b>批次是可选分组，不是强制包装</b>：一条查询、一次 {@code schema edit} 不开批次。
 * 判据是「这些改动是不是同一个业务意图」——把一次会话的所有改动塞进一个批次、
 * intent 写「维护图谱」，等于没有 intent。
 *
 * <p>{@code draft} 批次 submit 之前不落地。它的条目已经在库里，但不进任何队列。
 */
@Command(name = "batch",
         description = "批次（begin / submit / status / list / discard）",
         mixinStandardHelpOptions = true)
public class BatchCommand implements Callable<Integer> {

    /** DDL 隐式提交，rollback 对它无效——含 DDL 的批次不能标成可回滚。 */
    private static final Set<String> DDL_TYPES =
            Set.of("CREATE", "ALTER", "DROP", "TRUNCATE", "GRANT", "REVOKE");

    @Parameters(index = "0", arity = "0..1", description = "begin | submit | status | list | discard")
    private String action;

    @Parameters(index = "1", arity = "0..1", description = "批次号（submit / status / discard 用）")
    private String batchArg;

    @Option(names = "--intent", description = "这一批要做的业务需求，一句话。begin 必填")
    private String intent;

    @Option(names = "--kind", description = "graph（默认）或 sql")
    private String kind;

    @Option(names = "--draft", description = "list：只看还没提交的批次")
    private boolean draftOnly;

    @Option(names = {"-j", "--json"}, description = "JSON 格式输出")
    private boolean jsonOutput;

    private String alias;
    private final RunStateStore runState = new RunStateStore();

    public void setAlias(String alias) {
        this.alias = alias;
    }

    @Override
    public Integer call() {
        String verb = action == null ? "list" : action.toLowerCase(Locale.ROOT);
        return switch (verb) {
            case "begin" -> begin();
            case "submit" -> submit();
            case "status" -> status();
            case "list" -> list();
            case "discard" -> discard();
            default -> fail("未知动作：" + action
                    + "（只有 begin / submit / status / list / discard）");
        };
    }

    private int begin() {
        if (intent == null || intent.isBlank()) {
            return fail("--intent 必填：人在待审批队列里看到的就是它。"
                    + "写「梳理报事域状态字段」，不要写「维护图谱」。");
        }
        String batchKind = kind == null || kind.isBlank()
                ? ApprovalBatchRow.KIND_GRAPH : kind.trim().toLowerCase(Locale.ROOT);
        if (!ApprovalBatchRow.KIND_GRAPH.equals(batchKind)
                && !ApprovalBatchRow.KIND_SQL.equals(batchKind)) {
            return fail("--kind 只能是 graph 或 sql");
        }
        long id = runState.createBatch(alias, batchKind, intent.trim());
        if (id <= 0) {
            return fail("批次无法写入运行库。检查 ~/.sql-cli/sqlcli.db 是否可写。");
        }
        if (jsonOutput) {
            CliJson.printSuccess(batchMap(runState.findBatch(id), List.of()));
        } else {
            System.out.println("批次 #" + id + "（" + batchKind + "）：" + intent.trim());
            System.out.println("接下来的变更加 --batch " + id + " 就进这一批；"
                    + "全部加完执行 sql-cli " + alias + " batch submit " + id + " 才算提交。");
        }
        return 0;
    }

    private int submit() {
        Long id = batchId();
        if (id == null) return 2;
        ApprovalBatchRow batch = requireBatch(id);
        if (batch == null) return 2;
        List<ApprovalRow> items = runState.listBatchItems(id);
        if (items.isEmpty()) {
            return fail("批次 #" + id + " 是空的，没什么可提交的。");
        }
        boolean recoverable = !ApprovalBatchRow.KIND_SQL.equals(batch.kind()) || items.stream()
                .noneMatch(item -> DDL_TYPES.contains(sqlTypeOf(item)));
        int moved = runState.submitBatch(id, recoverable);
        if (moved < 0) {
            return fail("批次 #" + id + " 不是草稿状态（当前 " + batch.status() + "），无法再次提交。");
        }
        if (jsonOutput) {
            CliJson.printSuccess(batchMap(runState.findBatch(id), runState.listBatchItems(id)));
            return 0;
        }
        System.out.println("批次 #" + id + " 已提交，" + moved + " 条进入待审批。");
        if (!recoverable) {
            System.out.println("本批不可回滚：里面有 DDL，数据库对它隐式提交，rollback 无效。");
        }
        System.out.println("到 Web UI 评审页 · 待审批整批裁决；结果用 batch status " + id + " 取回。");
        return 0;
    }

    private int status() {
        Long id = batchId();
        if (id == null) return 2;
        ApprovalBatchRow batch = requireBatch(id);
        if (batch == null) return 2;
        List<ApprovalRow> items = runState.listBatchItems(id);
        if (jsonOutput) {
            CliJson.printSuccess(batchMap(batch, items));
            return 0;
        }
        System.out.println("批次 #" + batch.id() + " [" + batch.status() + "] "
                + batch.kind() + "  " + batch.intent());
        if (!batch.recoverable()) {
            System.out.println("本批不可回滚（含 DDL）。");
        }
        if (batch.reason() != null && !batch.reason().isBlank()) {
            System.out.println("裁决说明：" + batch.reason());
        }
        for (ApprovalRow item : items) {
            System.out.println("  #" + item.id() + " seq" + item.seq() + " [" + item.status() + "] "
                    + item.summary()
                    + (item.reason() == null || item.reason().isBlank() ? "" : " —— " + item.reason()));
        }
        long rejected = items.stream().filter(item -> "rejected".equals(item.status())).count();
        if (rejected > 0) {
            System.out.println("被否 " + rejected + " 条。按上面的理由改完再提一批，不要原样重提。");
        }
        return 0;
    }

    private int list() {
        List<ApprovalBatchRow> batches = runState.listBatches(alias,
                draftOnly ? ApprovalBatchRow.STATUS_DRAFT : null, 50);
        if (jsonOutput) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (ApprovalBatchRow batch : batches) {
                items.add(batchMap(batch, List.of()));
            }
            CliJson.printSuccess(items);
            return 0;
        }
        if (batches.isEmpty()) {
            System.out.println(draftOnly ? "没有未提交的批次。" : "还没有批次。");
            return 0;
        }
        for (ApprovalBatchRow batch : batches) {
            System.out.println("#" + batch.id() + " [" + batch.status() + "] " + batch.kind()
                    + "  " + batch.intent());
        }
        return 0;
    }

    private int discard() {
        Long id = batchId();
        if (id == null) return 2;
        ApprovalBatchRow batch = requireBatch(id);
        if (batch == null) return 2;
        if (!runState.discardDraftBatch(id)) {
            return fail("批次 #" + id + " 已经提交过（" + batch.status() + "），不能丢弃。");
        }
        if (jsonOutput) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("discarded", true);
            CliJson.printSuccess(item);
        } else {
            System.out.println("批次 #" + id + " 及其草稿条目已丢弃。图谱和数据库从头到尾没被碰过。");
        }
        return 0;
    }

    // ---------------------------------------------------------------- helpers

    private Long batchId() {
        if (batchArg == null || batchArg.isBlank()) {
            fail("需要批次号，例如：sql-cli " + alias + " batch " + action + " 77");
            return null;
        }
        try {
            return Long.parseLong(batchArg.trim());
        } catch (NumberFormatException e) {
            fail("批次号必须是数字：" + batchArg);
            return null;
        }
    }

    private ApprovalBatchRow requireBatch(long id) {
        ApprovalBatchRow batch = runState.findBatch(id);
        if (batch == null) {
            fail("批次 #" + id + " 不存在");
            return null;
        }
        if (!batch.alias().equals(alias)) {
            // 跨别名批次明确不做——跨库事务保证不了
            fail("批次 #" + id + " 属于别名 " + batch.alias() + "，不是 " + alias);
            return null;
        }
        return batch;
    }

    /** 条目的 detail 就是语句原文；解析不出来当 OTHER（不是 DDL，仍按可回滚算）。 */
    private static String sqlTypeOf(ApprovalRow item) {
        if (item.detail() == null || item.detail().isBlank()) {
            return "OTHER";
        }
        return new SqlStatementAnalyzer().detectSqlType(item.detail());
    }

    private Map<String, Object> batchMap(ApprovalBatchRow batch, List<ApprovalRow> items) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", batch.id());
        map.put("alias", batch.alias());
        map.put("kind", batch.kind());
        map.put("intent", batch.intent());
        map.put("status", batch.status());
        map.put("recoverable", batch.recoverable());
        map.put("createdAt", batch.createdAt());
        map.put("submittedAt", batch.submittedAt());
        map.put("decidedAt", batch.decidedAt());
        map.put("reason", batch.reason());
        List<Map<String, Object>> entries = new ArrayList<>();
        for (ApprovalRow item : items) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", item.id());
            entry.put("seq", item.seq());
            entry.put("kind", item.kind());
            entry.put("status", item.status());
            entry.put("summary", item.summary());
            entry.put("targetId", item.targetId());
            entry.put("reason", item.reason());
            entries.add(entry);
        }
        map.put("items", entries);
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
