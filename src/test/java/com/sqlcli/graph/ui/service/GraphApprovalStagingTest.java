package com.sqlcli.graph.ui.service;

import com.sqlcli.config.AliasConfigStore;
import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.config.SettingsConfig;
import com.sqlcli.graph.workspace.ColumnWorkspaceNode;
import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphIds;
import com.sqlcli.graph.workspace.GraphStatus;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sqlcli.graph.workspace.RelationType;
import com.sqlcli.graph.workspace.RelationWorkspaceEdge;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import com.sqlcli.graph.workspace.TermWorkspaceNode;
import com.sqlcli.graph.workspace.ChangeOperation;
import com.sqlcli.graph.workspace.WorkspaceValidator;
import com.sqlcli.runstate.ApprovalRow;
import com.sqlcli.runstate.GraphChangeRow;
import com.sqlcli.runstate.RunStateStore;
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
 * 图谱审批：先落 db、批准后才写图谱。
 *
 * <p>钉住的是这条链路上每一步的观察点：
 * <ul>
 *   <li>没开 {@code approveGraph} 的别名一条审批都不该多出来，变更照旧立即生效；</li>
 *   <li>开了的别名，提交之后**图谱不能变**，变更躺在 {@code approval_request.payload} 里；</li>
 *   <li>批准才写图谱，且候选关系顺手定级（否则人要为同一条边点两次同意）；</li>
 *   <li>拒绝什么也不做——这正是「先落 db」相对「先写图谱再改回去」的全部价值；</li>
 *   <li>提交后对象被人动过时，批准要拦下来而不是把那次改动盖掉；</li>
 *   <li>每次真正的图谱更新都在 {@code graph_change_log} 里留一条带内容的记录。</li>
 * </ul>
 */
class GraphApprovalStagingTest {

    private static final String ALIAS = "graph-approval-test";

    @TempDir Path temp;

    private GraphWorkspaceStore store;
    private WorkspaceMutationService service;
    private RunStateStore runState;
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
    }

    @AfterEach
    void tearDown() {
        restore("sqlcli.home", previousHome);
        restore(SettingsConfig.CONFIG_ROOT_PROPERTY, previousConfigRoot);
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    /** 写一份只带 approveGraph 开关的别名配置——审批闸门读的就是这个文件。 */
    private void approveGraph(boolean enabled) {
        DatabaseConfig config = new DatabaseConfig();
        config.setType("sqlite");
        config.setDatabase(temp.resolve("demo.db").toString());
        config.setApproveGraph(enabled);
        new AliasConfigStore().saveAlias(ALIAS, config);
    }

    @Test
    void withoutTheSwitchChangesLandImmediately() throws Exception {
        approveGraph(false);
        String tableId = seedTable();

        var result = service.updateTableDescription(ALIAS, tableId,
                Map.of("description", "订单主表"), revision(), "schema edit");

        assertTrue(result.isSuccess(), result.getErrors().toString());
        assertNull(result.getPendingApprovalId(), "没开审批就不该建审批记录");
        assertNotNull(result.getChangeId(), "变更应该已经落盘");
        assertEquals("订单主表", store.load(ALIAS).getTables().get(tableId).getDescription());
        assertEquals(0, pending().size());
    }

    @Test
    void withTheSwitchTheGraphIsUntouchedAndTheChangeWaitsInTheDatabase() throws Exception {
        approveGraph(true);
        String tableId = seedTable();
        long before = revision();

        var result = service.updateTableDescription(ALIAS, tableId,
                Map.of("description", "订单主表"), before, "schema edit");

        assertTrue(result.isSuccess(), result.getErrors().toString());
        assertNotNull(result.getPendingApprovalId(), "应该拿到审批号");
        assertNull(result.getChangeId(), "还没落盘，不能报告 changeId");
        // 图谱一个字节都没动
        assertNull(store.load(ALIAS).getTables().get(tableId).getDescription());
        assertEquals(before, revision(), "revision 不该被一次待审批的变更推进");

        List<ApprovalRow> rows = pending();
        assertEquals(1, rows.size());
        GraphChangePayload payload = GraphChangePayload.fromJson(rows.get(0).payload());
        assertNotNull(payload, "变更内容必须存进审批记录，否则批准时无从重放");
        assertEquals(tableId, payload.targetId());
        assertEquals("订单主表", payload.after().get("description").asText());
    }

    @Test
    void approvingWritesTheChangeIntoTheGraphAndLeavesAnAuditRow() throws Exception {
        approveGraph(true);
        String tableId = seedTable();
        long approvalId = service.updateTableDescription(ALIAS, tableId,
                Map.of("description", "订单主表"), revision(), "schema edit").getPendingApprovalId();

        var applied = service.applyApproved(ALIAS, approvalId,
                GraphChangePayload.fromJson(runState.findApproval(approvalId).payload()));

        assertTrue(applied.isSuccess(), applied.getErrors().toString());
        assertEquals("订单主表", store.load(ALIAS).getTables().get(tableId).getDescription());

        GraphChangeRow row = runState.listGraphChanges(ALIAS, tableId, 10, 0).get(0);
        assertEquals(approvalId, row.approvalId(), "流水要指回放行它的审批");
        assertNotNull(row.payload(), "流水要能回答「改成了什么」");
        assertTrue(row.revisionAfter() > row.revisionBefore());
    }

    @Test
    void rejectingLeavesTheGraphAlone() throws Exception {
        approveGraph(true);
        String tableId = seedTable();
        long approvalId = service.updateTableDescription(ALIAS, tableId,
                Map.of("description", "订单主表"), revision(), "schema edit").getPendingApprovalId();

        // 拒绝只翻状态，没有任何图谱侧动作——变更从来没进过图谱
        assertTrue(runState.decideApproval(approvalId, "rejected", "描述不准确"));

        assertNull(store.load(ALIAS).getTables().get(tableId).getDescription());
        assertEquals(0, pending().size());
    }

    @Test
    void approvingRefusesToOverwriteAConcurrentEdit() throws Exception {
        approveGraph(true);
        String tableId = seedTable();
        long approvalId = service.updateTableDescription(ALIAS, tableId,
                Map.of("description", "订单主表"), revision(), "schema edit").getPendingApprovalId();
        GraphChangePayload payload = GraphChangePayload.fromJson(
                runState.findApproval(approvalId).payload());

        // 审批还挂着的时候，有人（这里模拟成关掉审批后直接改）动了同一个对象
        approveGraph(false);
        service.updateTableDescription(ALIAS, tableId,
                Map.of("description", "别人改的"), revision(), "并发改动");

        var applied = service.applyApproved(ALIAS, approvalId, payload);

        assertFalse(applied.isSuccess(), "批准不能悄悄盖掉那次改动");
        assertEquals("别人改的", store.load(ALIAS).getTables().get(tableId).getDescription());
    }

    @Test
    void approvingACandidateRelationAlsoPublishesIt() throws Exception {
        approveGraph(true);
        seedTable();
        String relationId = GraphIds.relationId(ALIAS, RelationType.join_observed,
                GraphIds.columnId(ALIAS, "app", "orders", "user_id"),
                GraphIds.columnId(ALIAS, "app", "users", "id"));
        long approvalId = service.addRelation(ALIAS, RelationType.join_observed,
                GraphIds.columnId(ALIAS, "app", "orders", "user_id"),
                GraphIds.columnId(ALIAS, "app", "users", "id"),
                null, null, 0.95, null, revision(), "agent 推断的关联", GraphActor.agent)
                .getPendingApprovalId();
        assertNotNull(approvalId);
        assertTrue(store.load(ALIAS).getRelations().isEmpty(), "批准之前图谱里不该有这条边");

        var applied = service.applyApproved(ALIAS, approvalId,
                GraphChangePayload.fromJson(runState.findApproval(approvalId).payload()));

        assertTrue(applied.isSuccess(), applied.getErrors().toString());
        RelationWorkspaceEdge edge = store.load(ALIAS).getRelations().get(0);
        assertEquals(relationId, edge.getId());
        // 批准就是评审，不该再要求人去图谱页点一次「发布」
        assertEquals(GraphStatus.verified, edge.getStatus());
    }

    @Test
    void bookkeepingActorsNeverQueue() throws Exception {
        approveGraph(true);
        String tableId = seedTable();

        // system = 建索引 / 跑校验 / 回写行数这一类；挡下来的后果是「不批准就不能校验」
        var result = service.mutate(ALIAS, revision(), GraphActor.system, "schema validate",
                workspace -> {
                    workspace.getTables().get(tableId).setRowEstimate(42L);
                    return new WorkspaceMutationService.MutationOutcome(tableId,
                            com.sqlcli.graph.workspace.ChangeOperation.update);
                });

        assertTrue(result.isSuccess(), result.getErrors().toString());
        assertNull(result.getPendingApprovalId());
        assertEquals(42L, store.load(ALIAS).getTables().get(tableId).getRowEstimate());
    }

    // ---- 没开审批时：候选边也要排进同一个待审批队列 ----

    @Test
    void candidateRelationsQueueForReviewEvenWithoutTheSwitch() throws Exception {
        approveGraph(false);
        seedTable();

        var result = service.addRelation(ALIAS, RelationType.join_observed,
                GraphIds.columnId(ALIAS, "app", "orders", "user_id"),
                GraphIds.columnId(ALIAS, "app", "users", "id"),
                null, null, 0.6, null, revision(), "agent 推断的关联", GraphActor.agent);

        assertTrue(result.isSuccess(), result.getErrors().toString());
        // 这次写入是真发生了的（changeId 非空），但落成候选，还等着人发布
        assertNotNull(result.getChangeId());
        assertNotNull(result.getPendingApprovalId(), "候选边也要有一条待审批");
        ApprovalRow row = runState.findApproval(result.getPendingApprovalId());
        assertTrue(GraphChangePayload.fromJson(row.payload()).isPublish());
        assertTrue(row.summary().contains("orders.user_id") && row.summary().contains("60%"),
                "摘要要够决定发不发布：" + row.summary());
    }

    @Test
    void repeatedEditsOfTheSameCandidateDoNotQueueTwice() throws Exception {
        approveGraph(false);
        seedTable();
        String from = GraphIds.columnId(ALIAS, "app", "orders", "user_id");
        String to = GraphIds.columnId(ALIAS, "app", "users", "id");
        service.addRelation(ALIAS, RelationType.join_observed, from, to,
                null, null, 0.6, null, revision(), "第一次", GraphActor.agent);

        String relationId = GraphIds.relationId(ALIAS, RelationType.join_observed, from, to);
        service.updateRelation(ALIAS, relationId, Map.of("confidence", 0.8), revision(), "改置信度");

        assertEquals(1, pending().size(), "同一条边不该在队列里排两遍");
    }

    @Test
    void approvingACandidatePublishesItAndClosesTheApproval() throws Exception {
        approveGraph(false);
        seedTable();
        long approvalId = service.addRelation(ALIAS, RelationType.join_observed,
                GraphIds.columnId(ALIAS, "app", "orders", "user_id"),
                GraphIds.columnId(ALIAS, "app", "users", "id"),
                null, null, 0.95, null, revision(), "agent 推断的关联", GraphActor.agent)
                .getPendingApprovalId();

        var applied = service.decideGraphApproval(ALIAS, approvalId,
                GraphChangePayload.fromJson(runState.findApproval(approvalId).payload()),
                true, "证据充分");

        assertTrue(applied.isSuccess(), applied.getErrors().toString());
        assertEquals(GraphStatus.verified, store.load(ALIAS).getRelations().get(0).getStatus());
        assertEquals(0, pending().size(), "发布完那条审批要跟着结掉");
    }

    @Test
    void rejectingACandidateIgnoresIt() throws Exception {
        approveGraph(false);
        seedTable();
        long approvalId = service.addRelation(ALIAS, RelationType.join_observed,
                GraphIds.columnId(ALIAS, "app", "orders", "user_id"),
                GraphIds.columnId(ALIAS, "app", "users", "id"),
                null, null, 0.6, null, revision(), "agent 推断的关联", GraphActor.agent)
                .getPendingApprovalId();

        var applied = service.decideGraphApproval(ALIAS, approvalId,
                GraphChangePayload.fromJson(runState.findApproval(approvalId).payload()),
                false, "误判");

        assertTrue(applied.isSuccess(), applied.getErrors().toString());
        // 拒绝不是删除：边留着并标 ignored，下次挖掘不会再把它摆到人面前
        assertEquals(GraphStatus.ignored, store.load(ALIAS).getRelations().get(0).getStatus());
        assertEquals(0, pending().size());
    }

    @Test
    void publishingDirectlyAlsoClosesTheQueuedApproval() throws Exception {
        approveGraph(false);
        seedTable();
        String from = GraphIds.columnId(ALIAS, "app", "orders", "user_id");
        String to = GraphIds.columnId(ALIAS, "app", "users", "id");
        service.addRelation(ALIAS, RelationType.join_observed, from, to,
                null, null, 0.95, null, revision(), "agent 推断的关联", GraphActor.agent);
        String relationId = GraphIds.relationId(ALIAS, RelationType.join_observed, from, to);

        // 批量评审、CLI 走的是这条路，不经过审批中心
        service.publishRelation(ALIAS, relationId, revision(), "直接发布");

        assertEquals(0, pending().size(), "不结掉就是一个处理完的数字挂在待审批角标上");
    }

    @Test
    void aChangeTouchingSeveralObjectsStagesAllOfThemAsOneBatch() throws Exception {
        approveGraph(true);
        seedTable();
        String termId = "term:" + ALIAS + ":报事";
        String tableA = GraphIds.tableId(ALIAS, "app", "orders");
        String tableB = GraphIds.tableId(ALIAS, "app", "users");

        // add-term --map 的形状：术语 + N 条 term_mapping 边，一次提交
        var result = service.mutate(ALIAS, revision(), GraphActor.agent, "补报事的映射",
                termId, workspace -> {
                    TermWorkspaceNode term = TermWorkspaceNode.create(ALIAS, "报事", GraphActor.agent);
                    workspace.getTerms().put(term.getId(), term);
                    List<String> edges = new java.util.ArrayList<>();
                    for (String target : List.of(tableA, tableB)) {
                        RelationWorkspaceEdge edge = RelationWorkspaceEdge.create(
                                ALIAS, RelationType.term_mapping, term.getId(), target, GraphActor.agent);
                        workspace.getRelations().add(edge);
                        edges.add(edge.getId());
                    }
                    return new WorkspaceMutationService.MutationOutcome(
                            term.getId(), ChangeOperation.upsert, edges);
                });

        assertTrue(result.isSuccess(), result.getErrors().toString());
        assertEquals(0, store.load(ALIAS).getTerms().size(), "暂存阶段图谱不该被改动");
        // 三个对象都要进队列：只暂存术语的话，批准之后两条映射边一条都不会落地
        assertEquals(3, pending().size(), "术语 + 两条边应该都进待审批");
        assertNotNull(runState.findApproval(pending().get(0).id()).batchId(),
                "一次动多个对象要归成一批，散成三条互不相干的审批会被批一半");
    }

    /**
     * 同一条命令在 manual 别名上跑两遍，队列里只该有一份。
     *
     * <p><b>真实事故（2026-09-03，审批 #398）</b>：一个 agent 68 秒内把同一条术语提交了三次，
     * 队列里出现三张标题一模一样的卡，20 小时后批准第一批、剩下两批因冲突检测全部批不动。
     *
     * <p>根因是 {@code stageForApproval} 取 diff 的基线是<b>图谱</b>：manual 别名上第一次提交
     * 图谱一个字节没动，所以第二次跑同一条命令时 {@code before} 仍然是「不存在」，
     * 又生成一份完整 payload。<b>同一条命令在 auto 别名上是幂等的 no-op，在 manual 上却会
     * 复制一份待审批</b>——这个差异本身就是 bug，不需要 agent 犯错也会被脚本重试触发。
     *
     * <p>姊妹用例 {@code repeatedEditsOfTheSameCandidateDoNotQueueTwice} 早就钉住了同一个
     * 不变量，但它跑在 auto 别名的候选边路径上，manual 这条路一直没人覆盖。
     */
    @Test
    void repeatedIdenticalWritesOnManualAliasesDoNotQueueTwice() throws Exception {
        approveGraph(true);
        seedTable();

        stageTerm("回访完成");
        assertEquals(3, pending().size(), "第一次提交：术语 + 两条映射边");

        stageTerm("回访完成");
        assertEquals(3, pending().size(),
                "同一条命令跑第二遍不该再排一份——auto 别名上它是 no-op，manual 上也必须是");
    }

    /**
     * 改了内容再提交，取代旧的那一份，不是并排再挂一条。
     *
     * <p>auto 别名上第二次写就是覆盖第一次；manual 上的等价物是让旧的待审批作废。
     * 两边行为必须一致，否则 agent 改主意时只能靠人去队列里认哪张卡是最新的——
     * 而三张卡的标题在真实事故里完全相同。
     */
    @Test
    void aRevisedSubmissionSupersedesTheOlderPendingOne() throws Exception {
        approveGraph(true);
        seedTable();

        stageTerm("只算已完成回访");
        long firstId = pending().stream().filter(r -> r.targetId().startsWith("term:"))
                .findFirst().orElseThrow().id();

        stageTerm("含待回访");

        assertEquals(3, pending().size(), "改主意之后队列里仍然只有一份");
        ApprovalRow live = pending().stream().filter(r -> r.targetId().startsWith("term:"))
                .findFirst().orElseThrow();
        assertTrue(live.payload().contains("含待回访"), "留在队列里的必须是新那一版");
        ApprovalRow old = runState.findApproval(firstId);
        assertEquals("expired", old.status(), "旧的那份要作废，不能还挂在队列里");
        assertNotNull(old.reason(), "作废要写明是被谁取代的，否则提交方看不懂它为什么没了");
    }

    /**
     * 幂等闸门装在 {@code stageForApproval} 一处，所有受管辖的写入都汇到那里——
     * 这条用 {@code schema edit}（最常用的那条路、跟术语完全不同的入口）再证一遍。
     *
     * <p>只测术语的话，下一个人加一个新的写入入口时不会知道这条不变量存在。
     */
    @Test
    void theSameGuardCoversPlainFieldEditsNotJustTerms() throws Exception {
        approveGraph(true);
        seedTable();
        String tableId = GraphIds.tableId(ALIAS, "app", "orders");

        service.updateColumnDescription(ALIAS, tableId, "user_id",
                Map.of("description", "下单人"), revision(), "补字段描述");
        assertEquals(1, pending().size());

        service.updateColumnDescription(ALIAS, tableId, "user_id",
                Map.of("description", "下单人"), revision(), "补字段描述");
        assertEquals(1, pending().size(), "同一条 schema edit 跑第二遍不该再排一份");

        service.updateColumnDescription(ALIAS, tableId, "user_id",
                Map.of("description", "下单人（不是收货人）"), revision(), "改得更准");
        assertEquals(1, pending().size(), "改了内容也只留最新那一份");
        assertTrue(pending().get(0).payload().contains("不是收货人"), "留下的必须是新那一版");
    }

    /** `add-term --map` 的形状：术语 + 两条 term_mapping 边，一次提交。 */
    private void stageTerm(String description) throws Exception {
        String termId = "term:" + ALIAS + ":报事";
        String tableA = GraphIds.tableId(ALIAS, "app", "orders");
        String tableB = GraphIds.tableId(ALIAS, "app", "users");
        var result = service.mutate(ALIAS, revision(), GraphActor.agent, "补报事的映射",
                termId, workspace -> {
                    TermWorkspaceNode term = TermWorkspaceNode.create(ALIAS, "报事", GraphActor.agent);
                    term.setDescription(description);
                    workspace.getTerms().put(term.getId(), term);
                    List<String> edges = new java.util.ArrayList<>();
                    for (String target : List.of(tableA, tableB)) {
                        RelationWorkspaceEdge edge = RelationWorkspaceEdge.create(
                                ALIAS, RelationType.term_mapping, term.getId(), target, GraphActor.agent);
                        workspace.getRelations().add(edge);
                        edges.add(edge.getId());
                    }
                    return new WorkspaceMutationService.MutationOutcome(
                            term.getId(), ChangeOperation.upsert, edges);
                });
        assertTrue(result.isSuccess(), result.getErrors().toString());
    }

    @Test
    void anUnchangedTermWithNewMappingsIsNotReportedAsNoChange() throws Exception {
        approveGraph(true);
        seedTable();
        String termId = "term:" + ALIAS + ":报事";
        // 术语已经存在且这次一个标量都不改，只新增一条边
        GraphWorkspace seeded = store.load(ALIAS);
        seeded.getTerms().put(termId, TermWorkspaceNode.create(ALIAS, "报事", GraphActor.agent));
        store.save(seeded);

        var result = service.mutate(ALIAS, revision(), GraphActor.agent, "补一条映射",
                termId, workspace -> {
                    RelationWorkspaceEdge edge = RelationWorkspaceEdge.create(ALIAS,
                            RelationType.term_mapping, termId,
                            GraphIds.tableId(ALIAS, "app", "orders"), GraphActor.agent);
                    workspace.getRelations().add(edge);
                    return new WorkspaceMutationService.MutationOutcome(
                            termId, ChangeOperation.upsert, List.of(edge.getId()));
                });

        // 只比主对象的话这里会判成 no-op：术语一个字节没变，于是审批都不建，
        // 报「没有修改」——而边确实还没有，用 CLI 再也提交不进去
        assertNotNull(result.getPendingApprovalId(), "只看术语就成了 no-op，边永远补不上");
        assertEquals(1, pending().size());
    }

    @Test
    void rejectingStillWorksAfterTheWorkspaceIsGone() throws Exception {
        approveGraph(false);
        seedTable();
        String to = GraphIds.columnId(ALIAS, "app", "users", "id");
        long doomed = service.addRelation(ALIAS, RelationType.join_observed,
                GraphIds.columnId(ALIAS, "app", "orders", "user_id"), to,
                null, null, 0.6, null, revision(), "agent 推断的关联", GraphActor.agent)
                .getPendingApprovalId();
        // 反向再建一条：relationId 带 from/to，两条边不会撞 id，而两端的列都真实存在
        // （换成不存在的列，校验会先一步把它拦下来，根本走不到排队那步）
        long other = service.addRelation(ALIAS, RelationType.join_observed,
                to, GraphIds.columnId(ALIAS, "app", "orders", "user_id"),
                null, null, 0.6, null, revision(), "agent 推断的关联", GraphActor.agent)
                .getPendingApprovalId();

        deleteRecursively(temp.resolve(ALIAS));

        // 拒绝 = 「这条不要」。图谱都没了，这个意图早就达成——硬失败只会把审批
        // 永远钉在队列里，批也批不了、拒也拒不掉（真实撞见过：一次 e2e 测试
        // 往运行库写了 4 条 publish 审批，测完把工作区删了）。
        var rejected = service.decideGraphApproval(ALIAS, doomed,
                GraphChangePayload.fromJson(runState.findApproval(doomed).payload()), false, "不要了");
        assertTrue(rejected.isSuccess(), rejected.getErrors().toString());

        // 批准仍然必须硬失败：往一个不存在的图谱里发布，报成功就是骗人。
        var approved = service.decideGraphApproval(ALIAS, other,
                GraphChangePayload.fromJson(runState.findApproval(other).payload()), true, "放行");
        assertFalse(approved.isSuccess(), "图谱不在了却报批准成功");
    }

    private static void deleteRecursively(Path root) throws Exception {
        try (var paths = java.nio.file.Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    java.nio.file.Files.deleteIfExists(path);
                } catch (java.io.IOException ignored) {
                    // 锁文件被占着删不掉不影响这个测试：load() 照样读不到 generations/
                }
            });
        }
    }

    @Test
    void syncBackfillsCandidatesWrittenBeforeThisFeature() throws Exception {
        approveGraph(false);
        seedTable();
        // 手写一条候选边进图谱，绕过 addRelation——模拟改造之前留下的存量
        GraphWorkspace workspace = store.load(ALIAS);
        RelationWorkspaceEdge edge = RelationWorkspaceEdge.create(ALIAS, RelationType.join_observed,
                GraphIds.columnId(ALIAS, "app", "orders", "user_id"),
                GraphIds.columnId(ALIAS, "app", "users", "id"), GraphActor.agent);
        edge.setConfidence(0.6);
        workspace.getRelations().add(edge);
        store.save(workspace);
        assertEquals(0, pending().size());

        assertEquals(1, service.syncCandidateApprovals(ALIAS));
        assertEquals(1, pending().size());
        // 幂等：每次开 UI 都会跑一遍
        assertEquals(0, service.syncCandidateApprovals(ALIAS));
        assertEquals(1, pending().size());
    }

    /**
     * 并发裁决两条审批，两条都要成功。
     *
     * <p>早先裁决路径会在拿写锁**之前**读一次 revision 当乐观锁版本，读和拿锁之间隔着一个
     * 窗口：另一次裁决在这中间提交，后来的那条就报 "revision mismatch: expected 152 but was
     * 153"。那个版本号既不是调用方基于它做决定的那一版（批准动作不携带版本），
     * 也保护不了任何东西——真正的冲突判据是对象的 before 比对，在锁里做。
     */
    @Test
    void concurrentDecisionsDoNotCollideOnRevision() throws Exception {
        approveGraph(false);
        seedTable();
        String from = GraphIds.columnId(ALIAS, "app", "orders", "user_id");
        String to = GraphIds.columnId(ALIAS, "app", "users", "id");
        long first = service.addRelation(ALIAS, RelationType.join_observed, from, to,
                null, null, 0.95, null, revision(), "候选一", GraphActor.agent).getPendingApprovalId();
        // 反向也是一条独立的边（id 含方向），用它凑出第二条待审批
        long second = service.addRelation(ALIAS, RelationType.join_observed, to, from,
                null, null, 0.95, null, revision(), "候选二", GraphActor.agent).getPendingApprovalId();
        assertNotNull(first);
        assertNotNull(second);

        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        List<String> failures = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Thread t1 = decideThread(first, go, failures);
        Thread t2 = decideThread(second, go, failures);
        t1.start();
        t2.start();
        go.countDown();
        t1.join();
        t2.join();

        assertTrue(failures.isEmpty(), "两条裁决都该成功，实际: " + failures);
        assertEquals(0, pending().size());
    }

    private Thread decideThread(long approvalId, java.util.concurrent.CountDownLatch go,
            List<String> failures) {
        return new Thread(() -> {
            try {
                go.await();
                var result = service.decideGraphApproval(ALIAS, approvalId,
                        GraphChangePayload.fromJson(runState.findApproval(approvalId).payload()),
                        true, "并发批准");
                if (!result.isSuccess()) failures.add(String.join("; ", result.getErrors()));
            } catch (Exception e) {
                failures.add(e.toString());
            }
        });
    }

    private long revision() throws Exception {
        return store.load(ALIAS).getManifest().getRevision();
    }

    private List<ApprovalRow> pending() {
        return runState.listApprovals(ALIAS, "pending", "graph", null, 50, 0);
    }

    /** 两张表，orders.user_id → users.id 的端点都在；返回 orders 的 id。 */
    private String seedTable() throws Exception {
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create(ALIAS, "app", "orders", GraphActor.extractor);
        orders.getColumns().add(ColumnWorkspaceNode.create("user_id"));
        workspace.getTables().put(orders.getId(), orders);
        TableWorkspaceNode users = TableWorkspaceNode.create(ALIAS, "app", "users", GraphActor.extractor);
        users.getColumns().add(ColumnWorkspaceNode.create("id"));
        workspace.getTables().put(users.getId(), users);
        store.save(workspace);
        return orders.getId();
    }
}
