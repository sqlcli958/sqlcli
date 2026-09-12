package com.sqlcli.graph.ui.service;

import com.sqlcli.config.AliasConfigStore;
import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.config.SettingsConfig;
import com.sqlcli.graph.workspace.ColumnWorkspaceNode;
import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphIds;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import com.sqlcli.graph.workspace.WorkspaceValidator;
import com.sqlcli.runstate.ApprovalBatchRow;
import com.sqlcli.runstate.ApprovalRow;
import com.sqlcli.runstate.RunStateStore;
import com.sqlcli.session.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审批管线：字段级冲突（A1）+ 批次（A3）+ 图谱批次原子落地（A4）。
 *
 * <p>钉住的是这几条：
 * <ul>
 *   <li>并发改的是**别的字段**时批准不该被拦——审批 #154 就是被整对象比对挂掉的；</li>
 *   <li>写回也只写自己动过的那几个字段，不能把中间那次改动抹掉；</li>
 *   <li>同一字段真被人改过时仍然要拦，且错误信息说得出是哪个字段；</li>
 *   <li>{@code --batch} 下的变更进 draft，submit 之前图谱一个字节没动、也不进队列；</li>
 *   <li>整批批准是原子的，其中一条落不下去就全都不落；</li>
 *   <li>逐条否掉之后整批批准 = partial，剩下的照样落地。</li>
 * </ul>
 */
class ApprovalBatchPipelineTest {

    private static final String ALIAS = "batch-pipeline-test";

    @TempDir Path temp;

    private GraphWorkspaceStore store;
    private WorkspaceMutationService service;
    private RunStateStore runState;
    private ApprovalEffects effects;
    private String previousHome;
    private String previousConfigRoot;

    @BeforeEach
    void setUp() {
        previousHome = System.getProperty("sqlcli.home");
        previousConfigRoot = System.getProperty(SettingsConfig.CONFIG_ROOT_PROPERTY);
        System.setProperty("sqlcli.home", temp.resolve("home").toString());
        System.setProperty(SettingsConfig.CONFIG_ROOT_PROPERTY, temp.resolve("config-root").toString());
        store = new GraphWorkspaceStore(temp);
        runState = new RunStateStore();
        service = new WorkspaceMutationService(store, new WorkspaceValidator(), new WorkspaceLockManager());
        effects = new ApprovalEffects(store, Map.of(), null, runState, null);
        SessionContext.reset();
        graphApproval("manual");
    }

    @AfterEach
    void tearDown() {
        SessionContext.reset();
        restore("sqlcli.home", previousHome);
        restore(SettingsConfig.CONFIG_ROOT_PROPERTY, previousConfigRoot);
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private void graphApproval(String mode) {
        DatabaseConfig config = new DatabaseConfig();
        config.setType("sqlite");
        config.setDatabase(temp.resolve("demo.db").toString());
        config.setGraphApproval(mode);
        new AliasConfigStore().saveAlias(ALIAS, config);
    }

    // ------------------------------------------------------------------ A1

    @Test
    void concurrentEditOfAnotherFieldDoesNotBlockApproval() throws Exception {
        String columnId = seedColumn();
        var staged = service.updateColumnDescription(ALIAS, tableId(), "status",
                Map.of("businessName", "订单状态"), revision(), "补业务名");
        long approvalId = staged.getPendingApprovalId();
        assertNotNull(approvalId, "开了 manual 就该拿到审批号");

        // 提交之后有人改了同一个字段对象上的**另一个**字段
        graphApproval("auto");
        service.updateColumnDescription(ALIAS, tableId(), "status",
                Map.of("semanticType", "status"), WorkspaceMutationService.ANY_REVISION, "补语义类型");
        graphApproval("manual");

        effects.apply(runState.findApproval(approvalId), true, null);

        ColumnWorkspaceNode column = (ColumnWorkspaceNode) com.sqlcli.graph.workspace.GraphObjectPatch
                .read(store.load(ALIAS), columnId);
        assertEquals("订单状态", column.getBusinessName(), "批准的那个字段要落地");
        assertEquals("status", String.valueOf(column.getSemanticType()),
                "中间那次改动不能被整份 after 抹掉");
    }

    @Test
    void concurrentEditOfTheSameFieldIsStillRejectedAndNamesIt() throws Exception {
        seedColumn();
        var staged = service.updateColumnDescription(ALIAS, tableId(), "status",
                Map.of("businessName", "订单状态"), revision(), "补业务名");
        long approvalId = staged.getPendingApprovalId();

        graphApproval("auto");
        service.updateColumnDescription(ALIAS, tableId(), "status",
                Map.of("businessName", "状态（别人写的）"),
                WorkspaceMutationService.ANY_REVISION, "并发改同一字段");
        graphApproval("manual");

        ApprovalRow row = runState.findApproval(approvalId);
        IllegalStateException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> effects.apply(row, true, null));
        assertTrue(error.getMessage().contains("businessName"),
                "冲突要说清是哪个字段：" + error.getMessage());
    }

    // ------------------------------------------------------------ A3 / A4

    @Test
    void batchedChangesStayOutOfTheQueueUntilSubmitted() throws Exception {
        graphApproval("auto"); // 就算是 auto，进了批次也不许自己先落地
        seedColumn();
        long batchId = runState.createBatch(ALIAS, ApprovalBatchRow.KIND_GRAPH, "梳理订单状态字段");
        SessionContext.applyOverrides("s-test", "tester", batchId);

        var result = service.updateColumnDescription(ALIAS, tableId(), "status",
                Map.of("businessName", "订单状态"), revision(), "补业务名");

        assertNotNull(result.getPendingApprovalId());
        assertNull(result.getChangeId(), "图谱不该被动过");
        assertEquals(0, runState.listApprovals("pending", 50).size(), "draft 不进待审批队列");
        List<ApprovalRow> items = runState.listBatchItems(batchId);
        assertEquals(1, items.size());
        assertTrue(items.get(0).isDraft());
        assertEquals("s-test", items.get(0).sessionId());
        assertEquals("tester", items.get(0).agentId());

        assertEquals(1, runState.submitBatch(batchId, true));
        assertEquals(1, runState.listApprovals("pending", 50).size(), "submit 之后才进队列");
    }

    @Test
    void approvingABatchLandsEveryItemAtOnce() throws Exception {
        seedColumn();
        long batchId = stageTwoColumnEdits();
        runState.submitBatch(batchId, true);

        effects.decideBatch(batchId, true, "看过了", "tester");

        GraphWorkspace workspace = store.load(ALIAS);
        assertEquals("订单状态", column(workspace, "status").getBusinessName());
        assertEquals("下单时间", column(workspace, "created_at").getBusinessName());
        assertEquals(ApprovalBatchRow.STATUS_APPLIED, runState.findBatch(batchId).status());
    }

    @Test
    void oneRejectedItemMakesTheBatchPartialAndTheRestStillLands() throws Exception {
        seedColumn();
        long batchId = stageTwoColumnEdits();
        runState.submitBatch(batchId, true);
        List<ApprovalRow> items = runState.listBatchItems(batchId);
        runState.decideApproval(items.get(1).id(), "rejected", "这个名字不对");

        effects.decideBatch(batchId, true, null, "tester");

        GraphWorkspace workspace = store.load(ALIAS);
        assertEquals("订单状态", column(workspace, "status").getBusinessName());
        assertNull(column(workspace, "created_at").getBusinessName(), "被否的那条不该落地");
        assertEquals(ApprovalBatchRow.STATUS_PARTIAL, runState.findBatch(batchId).status());
    }

    @Test
    void aFailingItemKeepsTheWholeBatchOutOfTheGraph() throws Exception {
        seedColumn();
        long batchId = stageTwoColumnEdits();
        runState.submitBatch(batchId, true);
        // 第二条的基线被人改掉了：整批都不该落地
        graphApproval("auto");
        service.updateColumnDescription(ALIAS, tableId(), "created_at",
                Map.of("businessName", "别人写的"), WorkspaceMutationService.ANY_REVISION, "并发改动");
        graphApproval("manual");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> effects.decideBatch(batchId, true, null, "tester"));

        GraphWorkspace workspace = store.load(ALIAS);
        assertNull(column(workspace, "status").getBusinessName(), "第一条也不能落地");
        assertEquals(ApprovalBatchRow.STATUS_FAILED, runState.findBatch(batchId).status());
        assertEquals("pending", runState.listBatchItems(batchId).get(0).status(),
                "条目留在 pending 等人重新决定");

        // 「重新决定」得真的做得到：落地失败之后必须还能拒掉这一批
        effects.decideBatch(batchId, false, "基线已被改掉，不再需要", "tester");
        assertEquals(ApprovalBatchRow.STATUS_REJECTED, runState.findBatch(batchId).status());
        assertEquals("rejected", runState.listBatchItems(batchId).get(0).status(),
                "拒完条目要出队列，否则它永远挂在待审批上");
    }

    @Test
    void autoAliasesStillLeaveAnAuditTrail() throws Exception {
        graphApproval("auto");
        seedColumn();

        var result = service.updateColumnDescription(ALIAS, tableId(), "status",
                Map.of("businessName", "订单状态"), revision(), "补业务名");

        assertNotNull(result.getChangeId(), "auto 就是当场落地");
        List<ApprovalRow> approved = runState.listApprovals("approved", 50);
        assertEquals(1, approved.size(), "auto 也要留底，否则查不到是谁改的");
        assertEquals("补业务名", approved.get(0).summary());
        assertEquals(0, runState.listApprovals("pending", 50).size(), "但不进待审批队列");
    }

    // -------------------------------------------------------------- helpers

    /** 两条改的是同一个表的两个不同列，落地顺序由 seq 定。 */
    private long stageTwoColumnEdits() {
        long batchId = runState.createBatch(ALIAS, ApprovalBatchRow.KIND_GRAPH, "梳理订单状态字段");
        SessionContext.applyOverrides(null, null, batchId);
        service.updateColumnDescription(ALIAS, tableId(), "status",
                Map.of("businessName", "订单状态"), WorkspaceMutationService.ANY_REVISION, "补 status");
        service.updateColumnDescription(ALIAS, tableId(), "created_at",
                Map.of("businessName", "下单时间"), WorkspaceMutationService.ANY_REVISION, "补 created_at");
        SessionContext.reset();
        return batchId;
    }

    private static ColumnWorkspaceNode column(GraphWorkspace workspace, String name) {
        return workspace.getTables().values().iterator().next().findColumn(name);
    }

    private String tableId() {
        return GraphIds.tableId(ALIAS, "app", "orders");
    }

    /** 一张表两个列，够试「同一对象两个字段」和「一批两条」。 */
    private String seedColumn() throws Exception {
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create(ALIAS, "app", "orders", GraphActor.extractor);
        orders.getColumns().add(ColumnWorkspaceNode.create("status"));
        orders.getColumns().add(ColumnWorkspaceNode.create("created_at"));
        workspace.getTables().put(orders.getId(), orders);
        store.save(workspace);
        return GraphIds.columnId(ALIAS, "app", "orders", "status");
    }

    private long revision() throws Exception {
        return store.load(ALIAS).getManifest().getRevision();
    }

    @Test
    void draftBatchesNeverShowUpInTheHistoryView() {
        long batchId = runState.createBatch(ALIAS, ApprovalBatchRow.KIND_GRAPH, "还没提交的");
        runState.createApproval(ALIAS, "graph", "草稿条目", null, null, "table:x", null,
                ApprovalRow.STATUS_DRAFT, batchId, 1);

        assertEquals(0, runState.listApprovals(null, null, null, null, null, null, 50, 0).size(),
                "draft 既不在待审批也不在审批记录里——它还没发生过");
        assertFalse(runState.listBatchItems(batchId).isEmpty(), "但它确实在库里，batch status 查得到");
    }
}
