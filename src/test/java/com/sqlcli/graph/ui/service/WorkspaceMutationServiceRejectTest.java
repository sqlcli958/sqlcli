package com.sqlcli.graph.ui.service;

import com.sqlcli.graph.workspace.ColumnWorkspaceNode;
import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphIds;
import com.sqlcli.graph.workspace.GraphStatus;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sqlcli.graph.workspace.RelationType;
import com.sqlcli.graph.workspace.RelationWorkspaceEdge;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import com.sqlcli.graph.workspace.WorkspaceValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 候选拒绝改为 {@link GraphStatus#ignored} 而不是物理删除（P1 第 6 条）。
 *
 * <p>钉住的三件事：拒绝后边还在、原因可读回；撤销忽略能回到 candidate 且清掉旧原因；
 * foreign_key 的删除保护延续到拒绝入口，不能靠"拒绝"绕过。
 */
class WorkspaceMutationServiceRejectTest {

    private static final String ALIAS = "reject-test";

    @TempDir Path temp;

    private GraphWorkspaceStore store;
    private WorkspaceMutationService service;
    private String previousHome;

    @BeforeEach
    void setUp() throws Exception {
        previousHome = System.getProperty("sqlcli.home");
        System.setProperty("sqlcli.home", temp.resolve("home").toString());
        store = new GraphWorkspaceStore(temp);
    }

    @AfterEach
    void tearDown() {
        if (previousHome == null) {
            System.clearProperty("sqlcli.home");
        } else {
            System.setProperty("sqlcli.home", previousHome);
        }
    }

    /** 建一个两表一候选关系的工作区并落盘，返回候选关系的 id。 */
    private String seedCandidate(RelationType type, GraphActor actor) throws Exception {
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create(ALIAS, "app", "orders", GraphActor.extractor);
        orders.getColumns().add(ColumnWorkspaceNode.create("user_id"));
        workspace.getTables().put(orders.getId(), orders);
        TableWorkspaceNode users = TableWorkspaceNode.create(ALIAS, "app", "users", GraphActor.extractor);
        users.getColumns().add(ColumnWorkspaceNode.create("id"));
        workspace.getTables().put(users.getId(), users);

        RelationWorkspaceEdge edge = RelationWorkspaceEdge.create(ALIAS, type,
                GraphIds.columnId(ALIAS, "app", "orders", "user_id"),
                GraphIds.columnId(ALIAS, "app", "users", "id"), actor);
        if (type.isInferred()) {
            edge.setConfidence(0.6);
        }
        workspace.getRelations().add(edge);
        store.save(workspace);
        service = new WorkspaceMutationService(store, new WorkspaceValidator(), new WorkspaceLockManager());
        return edge.getId();
    }

    @Test
    void rejectShouldIgnoreNotDeleteAndRecordReason() throws Exception {
        String relationId = seedCandidate(RelationType.join_observed, GraphActor.agent);
        long revision = store.load(ALIAS).getManifest().getRevision();

        WorkspaceMutationService.MutationResult result = service.rejectRelation(
                ALIAS, relationId, revision, "误判：字段命中率过低");

        assertTrue(result.isSuccess(), result.getErrors().toString());
        GraphWorkspace saved = store.load(ALIAS);
        RelationWorkspaceEdge relation = findRelation(saved, relationId);
        assertEquals(GraphStatus.ignored, relation.getStatus(), "拒绝后边还在，状态转 ignored");
        assertEquals("误判：字段命中率过低",
                relation.getAttributes().get(WorkspaceMutationService.REJECTION_REASON_ATTR),
                "原因可读回");
    }

    @Test
    void rejectWithBlankReasonShouldStillRecordAFallback() throws Exception {
        String relationId = seedCandidate(RelationType.join_observed, GraphActor.agent);
        long revision = store.load(ALIAS).getManifest().getRevision();

        WorkspaceMutationService.MutationResult result = service.rejectRelation(ALIAS, relationId, revision, "  ");

        assertTrue(result.isSuccess(), result.getErrors().toString());
        RelationWorkspaceEdge relation = findRelation(store.load(ALIAS), relationId);
        assertEquals("拒绝候选关系",
                relation.getAttributes().get(WorkspaceMutationService.REJECTION_REASON_ATTR));
    }

    @Test
    void unignoreShouldReturnToCandidateAndClearReason() throws Exception {
        String relationId = seedCandidate(RelationType.join_observed, GraphActor.agent);
        long revision = store.load(ALIAS).getManifest().getRevision();
        service.rejectRelation(ALIAS, relationId, revision, "先拒一次");
        revision = store.load(ALIAS).getManifest().getRevision();

        WorkspaceMutationService.MutationResult result = service.unignoreRelation(
                ALIAS, relationId, revision, "重新评审");

        assertTrue(result.isSuccess(), result.getErrors().toString());
        RelationWorkspaceEdge relation = findRelation(store.load(ALIAS), relationId);
        assertEquals(GraphStatus.candidate, relation.getStatus());
        assertNull(relation.getAttributes().get(WorkspaceMutationService.REJECTION_REASON_ATTR),
                "撤销后不留旧的拒绝原因，避免误导下一次评审");
    }

    @Test
    void unignoreShouldRejectNonIgnoredRelation() throws Exception {
        String relationId = seedCandidate(RelationType.join_observed, GraphActor.agent);
        long revision = store.load(ALIAS).getManifest().getRevision();

        WorkspaceMutationService.MutationResult result = service.unignoreRelation(
                ALIAS, relationId, revision, "not ignored");

        assertFalse(result.isSuccess());
    }

    @Test
    void rejectShouldNotBypassForeignKeyDeleteProtection() throws Exception {
        String relationId = seedCandidate(RelationType.foreign_key, GraphActor.extractor);
        long revision = store.load(ALIAS).getManifest().getRevision();

        WorkspaceMutationService.MutationResult result = service.rejectRelation(
                ALIAS, relationId, revision, "尝试拒绝外键");

        assertFalse(result.isSuccess(), "foreign_key 不能通过 reject 入口被静默处理");
        RelationWorkspaceEdge relation = findRelation(store.load(ALIAS), relationId);
        assertEquals(GraphStatus.candidate, relation.getStatus(), "拒绝失败，状态未被改动");
    }

    @Test
    void isIgnoredShouldMatchOnlyIgnoredEdgeForSameEndpointsAndType() throws Exception {
        String relationId = seedCandidate(RelationType.join_observed, GraphActor.agent);
        GraphWorkspace beforeReject = store.load(ALIAS);
        String from = GraphIds.columnId(ALIAS, "app", "orders", "user_id");
        String to = GraphIds.columnId(ALIAS, "app", "users", "id");

        assertFalse(WorkspaceMutationService.isIgnored(
                beforeReject, ALIAS, RelationType.join_observed, from, to), "还是候选，不算被拒绝过");

        long revision = beforeReject.getManifest().getRevision();
        service.rejectRelation(ALIAS, relationId, revision, "剪枝用");
        GraphWorkspace afterReject = store.load(ALIAS);

        assertTrue(WorkspaceMutationService.isIgnored(
                afterReject, ALIAS, RelationType.join_observed, from, to), "同一对端点、同一类型，命中已拒绝");
        assertFalse(WorkspaceMutationService.isIgnored(
                afterReject, ALIAS, RelationType.foreign_key, from, to), "类型不同，不是同一条边");
        assertFalse(WorkspaceMutationService.isIgnored(
                afterReject, ALIAS, RelationType.join_observed, to, from), "端点方向不同，不是同一条边");
    }

    private RelationWorkspaceEdge findRelation(GraphWorkspace workspace, String relationId) {
        return workspace.getRelations().stream()
                .filter(edge -> edge.getId().equals(relationId))
                .findFirst().orElseThrow();
    }
}
