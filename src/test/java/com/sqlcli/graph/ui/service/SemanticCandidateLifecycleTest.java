package com.sqlcli.graph.ui.service;

import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphStatus;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sqlcli.graph.workspace.MetricRecord;
import com.sqlcli.graph.workspace.TermWorkspaceNode;
import com.sqlcli.graph.workspace.WorkspaceValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * term / metric 不能成为“永久 candidate”。
 *
 * <p>关系和血缘早已有候选评审出口；语义对象也必须进入同一个审批队列，并能被发布或拒绝。
 */
class SemanticCandidateLifecycleTest {

    private static final String ALIAS = "semantic-candidate";

    @TempDir Path temp;

    private GraphWorkspaceStore store;
    private WorkspaceMutationService service;
    private String previousHome;

    @BeforeEach
    void setUp() throws Exception {
        previousHome = System.getProperty("sqlcli.home");
        System.setProperty("sqlcli.home", temp.resolve("home").toString());

        store = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");

        TermWorkspaceNode term = TermWorkspaceNode.create(ALIAS, "已支付订单", GraphActor.agent);
        term.setDescription("已完成支付且未取消的订单");
        workspace.getTerms().put(term.getId(), term);

        MetricRecord metric = MetricRecord.create(ALIAS, "gmv_paid", GraphActor.agent);
        metric.setExpression("SUM(amount)");
        workspace.getMetrics().put(metric.getId(), metric);

        store.save(workspace);
        service = new WorkspaceMutationService(store, new WorkspaceValidator(), new WorkspaceLockManager());
    }

    @AfterEach
    void tearDown() {
        if (previousHome == null) {
            System.clearProperty("sqlcli.home");
        } else {
            System.setProperty("sqlcli.home", previousHome);
        }
    }

    @Test
    void syncQueuesTermAndMetricExactlyOnce() {
        assertEquals(2, service.syncCandidateApprovals(ALIAS),
                "两个语义 candidate 都应进入统一审批队列");
        assertEquals(0, service.syncCandidateApprovals(ALIAS),
                "重复同步不能重复排同一个候选审批");
    }

    @Test
    void publishingTermClosesCandidateState() throws Exception {
        service.syncCandidateApprovals(ALIAS);
        GraphWorkspace before = store.load(ALIAS);
        String termId = "term:" + ALIAS + ":已支付订单";

        WorkspaceMutationService.MutationResult result = service.publishCandidate(
                ALIAS, termId, before.getManifest().getRevision(), "口径确认");

        assertTrue(result.isSuccess(), result.getErrors().toString());
        TermWorkspaceNode saved = store.load(ALIAS).getTerms().get(termId);
        assertNotNull(saved);
        assertEquals(GraphStatus.partial, saved.getStatus(),
                "默认置信度 0.8 应离开 candidate 并定级为 partial");
        assertEquals(Boolean.FALSE, saved.getVerified());
    }

    @Test
    void rejectingMetricKeepsAuditObjectButMakesItInactive() throws Exception {
        service.syncCandidateApprovals(ALIAS);
        GraphWorkspace before = store.load(ALIAS);
        String metricId = "metric:" + ALIAS + ":gmv_paid";

        WorkspaceMutationService.MutationResult result = service.rejectCandidate(
                ALIAS, metricId, before.getManifest().getRevision(), "口径与财务定义不一致");

        assertTrue(result.isSuccess(), result.getErrors().toString());
        MetricRecord saved = store.load(ALIAS).getMetrics().get(metricId);
        assertNotNull(saved, "拒绝不是物理删除，保留对象才能阻止同一候选反复生成");
        assertEquals(GraphStatus.ignored, saved.getStatus());
        assertEquals("口径与财务定义不一致",
                saved.getAttributes().get(WorkspaceMutationService.REJECTION_REASON_ATTR));
    }
}
