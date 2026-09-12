package com.sqlcli.graph.ui.service;

import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sqlcli.graph.workspace.WorkspaceValidator;
import com.sqlcli.recovery.RecoveryExecutor;
import com.sqlcli.runstate.ApprovalBatchRow;
import com.sqlcli.runstate.ApprovalRow;
import com.sqlcli.runstate.RunStateStore;
import com.sqlcli.task.SqlBatchExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 裁决一条审批（或一整批）时真正要做的那件事。
 *
 * <h2>为什么集中在一个地方</h2>
 * 审批中心是所有「等着我决定的事」的唯一入口，那么「决定完了之后发生什么」也该在
 * 这一个地方分发。分散在各自的入口上会长成这样：回滚在执行记录页点按钮然后请求挂住、
 * 候选关系在图谱页点发布、图谱变更在审批页批准——同一类动作三种操作方式，
 * 而且前两种都得让人先在别处发起、再回审批中心批准自己刚发起的东西。
 *
 * <h2>三类 kind 的落地方式</h2>
 * <ul>
 *   <li><b>graph</b>——两种：变更还没进图谱的（批准时重放 payload），
 *       和候选边已经在图谱里的（批准 = 发布，拒绝 = 转 ignored）。
 *       见 {@link GraphChangePayload#ACTION_APPLY} / {@link GraphChangePayload#ACTION_PUBLISH}。</li>
 *   <li><b>recovery</b>——批准时执行回滚脚本。提交回滚的那个请求早就返回了，
 *       它不等结果（原来是挂住等，一个 HTTP 线程耗在人的反应时间上）。</li>
 *   <li><b>query / update</b>——单条时什么都不做。等待方是另一个进程（CLI 或工作台的写任务），
 *       它自己轮询审批状态然后继续执行；这里替它执行反而会执行两次。
 *       批次里的 SQL 条目是例外：没有等待方，它就是在这一刻才跑的（见 {@link #decideBatch}）。</li>
 * </ul>
 *
 * <p>**抛异常 = 这次裁决没落地**，调用方据此不把审批标成已裁决。顺序上必须先落地
 * 再改状态：反过来的话，落地失败会留下一条「已批准」但什么也没发生的记录。
 */
public class ApprovalEffects {

    private final GraphWorkspaceStore workspaceStore;
    private final Map<String, DatabaseConfig> aliases;
    private final RecoveryExecutor recoveryExecutor;
    private final RunStateStore runState;
    /** 可为 null：没有它就只能落地图谱批次，SQL 批次会明确报错而不是假装成功。 */
    private final SqlBatchExecutor sqlBatchExecutor;

    public ApprovalEffects(GraphWorkspaceStore workspaceStore, Map<String, DatabaseConfig> aliases,
                           RecoveryExecutor recoveryExecutor) {
        this(workspaceStore, aliases, recoveryExecutor, new RunStateStore(), null);
    }

    public ApprovalEffects(GraphWorkspaceStore workspaceStore, Map<String, DatabaseConfig> aliases,
                           RecoveryExecutor recoveryExecutor, RunStateStore runState,
                           SqlBatchExecutor sqlBatchExecutor) {
        this.workspaceStore = workspaceStore;
        this.aliases = aliases;
        this.recoveryExecutor = recoveryExecutor;
        this.runState = runState;
        this.sqlBatchExecutor = sqlBatchExecutor;
    }

    public void apply(ApprovalRow row, boolean approved, String reason) throws Exception {
        if (row == null) return;
        if (row.batchId() != null && !row.isDraft() && approved) {
            // 批次里的条目只能整批批准：图谱要原子落地、SQL 要一个事务，
            // 单条批准做不到那两件事里的任何一件。逐条「否掉」倒是允许（见 UI）。
            throw new IllegalStateException("条目 #" + row.id() + " 属于批次 #" + row.batchId()
                    + "，请整批批准。单条只能否掉。");
        }
        switch (row.kind()) {
            case "graph" -> applyGraph(row, approved, reason);
            case "recovery" -> {
                if (approved) executeRollback(row);
            }
            default -> { /* query / update：等待方自己继续，这里插手会执行两次 */ }
        }
    }

    /**
     * 整批裁决。
     *
     * <p><b>批次是审批单位，条目是裁决单位</b>：批准 = 落地这一批中所有**未被单独否掉**的
     * 条目。有条目被否时批次记 {@code partial}——30 条错 2 条就整批打回、让提交方全部重来，
     * 代价太大。
     *
     * <p>落地是全有全无的：图谱走一次 load/apply/save，SQL 走一个事务。任一条失败
     * 整批标 {@code failed}，条目留在 pending，人看清原因后重新决定。
     * 不允许出现「已批准但什么也没发生」的记录。
     */
    public void decideBatch(long batchId, boolean approved, String reason, String decidedBy)
            throws Exception {
        ApprovalBatchRow batch = runState.findBatch(batchId);
        if (batch == null) {
            throw new IllegalStateException("批次 #" + batchId + " 不存在");
        }
        // failed 也放行：它不是终态。上面那段注释写着「条目留在 pending，人看清原因后
        // 重新决定」——只认 pending 的话那句话做不到，落地失败的批次会**批不动也拒不掉**，
        // 条目却还挂在待审批队列上，人没有任何出口。
        // 真实死结：批次 6 / 7（图谱审批 #395、#398），冲突检测挡住批准、这道守卫挡住拒绝。
        boolean decidable = ApprovalBatchRow.STATUS_PENDING.equals(batch.status())
                || ApprovalBatchRow.STATUS_FAILED.equals(batch.status());
        if (!decidable) {
            throw new IllegalStateException("批次 #" + batchId + " 当前是「" + batch.status()
                    + "」，不能再次裁决");
        }
        List<ApprovalRow> all = runState.listBatchItems(batchId);
        List<ApprovalRow> pending = all.stream()
                .filter(item -> "pending".equals(item.status())).toList();
        long rejected = all.size() - pending.size();

        if (!approved) {
            for (ApprovalRow item : pending) {
                runState.decideApproval(item.id(), "rejected", reason);
            }
            runState.finishBatch(batchId, ApprovalBatchRow.STATUS_REJECTED, decidedBy, reason);
            return;
        }
        if (pending.isEmpty()) {
            // 每一条都被单独否掉了，没有可落地的内容——它是 rejected 不是 applied
            runState.finishBatch(batchId, ApprovalBatchRow.STATUS_REJECTED, decidedBy,
                    "批次内条目已全部被否");
            return;
        }
        try {
            if (ApprovalBatchRow.KIND_SQL.equals(batch.kind())) {
                applySqlBatch(batch, pending);
            } else {
                applyGraphBatch(batch, pending);
            }
        } catch (Exception e) {
            // 先落地、再改状态：这里一条都没生效，条目留在 pending 等人重新决定
            runState.finishBatch(batchId, ApprovalBatchRow.STATUS_FAILED, decidedBy,
                    e.getMessage());
            throw e;
        }
        for (ApprovalRow item : pending) {
            runState.decideApproval(item.id(), "approved", reason);
        }
        runState.finishBatch(batchId,
                rejected > 0 ? ApprovalBatchRow.STATUS_PARTIAL : ApprovalBatchRow.STATUS_APPLIED,
                decidedBy, reason);
    }

    private void applyGraphBatch(ApprovalBatchRow batch, List<ApprovalRow> items) {
        if (workspaceStore == null) {
            throw new IllegalStateException("当前服务没有图谱工作区，无法落地这批图谱变更");
        }
        List<WorkspaceMutationService.BatchItem> payloads = new ArrayList<>();
        for (ApprovalRow item : items) {
            payloads.add(new WorkspaceMutationService.BatchItem(item.id(),
                    GraphChangePayload.fromJson(item.payload())));
        }
        WorkspaceMutationService service = new WorkspaceMutationService(
                workspaceStore, new WorkspaceValidator(), new WorkspaceLockManager());
        WorkspaceMutationService.MutationResult result =
                service.applyBatch(batch.alias(), batch.id(), payloads);
        if (!result.isSuccess()) {
            throw new IllegalStateException("批准失败，图谱一个字节没动："
                    + String.join("; ", result.getErrors()));
        }
    }

    private void applySqlBatch(ApprovalBatchRow batch, List<ApprovalRow> items) throws Exception {
        if (sqlBatchExecutor == null || aliases == null) {
            throw new IllegalStateException("当前服务不能执行 SQL 批次");
        }
        DatabaseConfig config = aliases.get(batch.alias());
        if (config == null) {
            throw new IllegalStateException("Unknown alias: " + batch.alias());
        }
        sqlBatchExecutor.execute(config, batch.id(), items);
    }

    private void applyGraph(ApprovalRow row, boolean approved, String reason) {
        GraphChangePayload payload = GraphChangePayload.fromJson(row.payload());
        if (payload == null) {
            // 升级前的老记录没有 payload。批准它不会改图谱，说清楚而不是假装成功。
            throw new IllegalStateException("这条审批没有记下变更内容（升级前提交的），"
                    + "无法落地。请让提交方基于最新图谱重新提交。");
        }
        if (workspaceStore == null) {
            throw new IllegalStateException("当前服务没有图谱工作区，无法落地这条图谱变更");
        }
        WorkspaceMutationService service = new WorkspaceMutationService(
                workspaceStore, new WorkspaceValidator(), new WorkspaceLockManager());
        WorkspaceMutationService.MutationResult result = service.decideGraphApproval(
                row.alias(), row.id(), payload, approved, reason);
        if (!result.isSuccess()) {
            throw new IllegalStateException((approved ? "批准" : "拒绝") + "失败，图谱未改动："
                    + String.join("; ", result.getErrors()));
        }
    }

    /** targetId 形如 {@code execution:42}——回滚针对的是那条执行记录。 */
    private void executeRollback(ApprovalRow row) throws Exception {
        if (recoveryExecutor == null || aliases == null) {
            throw new IllegalStateException("当前服务不能执行回滚");
        }
        String targetId = row.targetId();
        if (targetId == null || !targetId.startsWith("execution:")) {
            throw new IllegalStateException("回滚审批没有关联执行记录，无法执行");
        }
        long executionId = Long.parseLong(targetId.substring("execution:".length()));
        DatabaseConfig config = aliases.get(row.alias());
        if (config == null) {
            throw new IllegalStateException("Unknown alias: " + row.alias());
        }
        // 提交回滚到批准之间别名可能被改成只读了，按批准这一刻的配置判断
        RecoveryExecutor.requireWritable(config);
        recoveryExecutor.execute(config, executionId, recoveryExecutor.loadScript(executionId));
    }
}
