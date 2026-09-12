package com.sqlcli.graph.policy;

import com.sqlcli.graph.ui.dto.GraphViewDto;
import com.sqlcli.graph.ui.service.GraphViewService;
import com.sqlcli.graph.ui.service.WorkspaceLockManager;
import com.sqlcli.graph.ui.service.WorkspaceMutationService;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拒绝候选不再删边（P1 第 6 条）之后的策略有两半，缺一半都是 bug：
 * <ol>
 *   <li>拿关系去派生/生成东西的路径（画布、规则评估……）必须把 ignored 当不存在；</li>
 *   <li>持久化必须原样存取 ignored——不能存丢，也不能"为了方便过滤"在读的时候顺手丢弃。</li>
 * </ol>
 * 两个测试各自证明其中一半都不够：真正的风险是"为了让画布/规则干净，在存取路径上
 * 加了一层不该有的过滤"，或者反过来"落盘忘了带上新状态"。这里用同一条边、同一次
 * {@link WorkspaceMutationService#rejectRelation} 调用，从磁盘重新加载后再分别喂给
 * {@link GraphViewService} 和 {@link PolicyEvaluator}，一次性把两半钉在一起。
 */
class IgnoredRelationRoundTripTest {

    private static final String ALIAS = "round-trip";

    @TempDir Path temp;

    private String previousHome;

    @BeforeEach
    void isolateRunStateHome() {
        previousHome = System.getProperty("sqlcli.home");
        System.setProperty("sqlcli.home", temp.resolve("run-state-home").toString());
    }

    @AfterEach
    void restoreRunStateHome() {
        if (previousHome == null) {
            System.clearProperty("sqlcli.home");
        } else {
            System.setProperty("sqlcli.home", previousHome);
        }
    }

    @Test
    void rejectedRelationSurvivesReloadButDisappearsFromCanvasAndRuleEvaluation() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create(ALIAS, "app", "orders", GraphActor.extractor);
        orders.getColumns().add(ColumnWorkspaceNode.create("customer_id"));
        workspace.getTables().put(orders.getId(), orders);
        TableWorkspaceNode customers = TableWorkspaceNode.create(ALIAS, "app", "customers", GraphActor.extractor);
        customers.getColumns().add(ColumnWorkspaceNode.create("id"));
        workspace.getTables().put(customers.getId(), customers);
        // 不给 orders 建索引：不过滤的话这条 join_observed 关系会同时触发
        // relation_column_index 规则，画布上也会画出一条边——两边都能观察到。
        RelationWorkspaceEdge relation = RelationWorkspaceEdge.create(ALIAS, RelationType.join_observed,
                GraphIds.columnId(ALIAS, "app", "orders", "customer_id"),
                GraphIds.columnId(ALIAS, "app", "customers", "id"), GraphActor.agent);
        relation.setConfidence(0.8);
        workspace.getRelations().add(relation);
        store.save(workspace);
        String relationId = relation.getId();
        long revision = workspace.getManifest().getRevision();

        WorkspaceMutationService mutationService = new WorkspaceMutationService(
                store, new WorkspaceValidator(), new WorkspaceLockManager());
        WorkspaceMutationService.MutationResult result = mutationService.rejectRelation(
                ALIAS, relationId, revision, "跨库误判");
        assertTrue(result.isSuccess(), result.getErrors().toString());

        // 半 1：落盘 + 重新从磁盘加载（新的 GraphWorkspaceStore 实例，不共享内存对象），
        // ignored 状态和原因必须原样在——这条断言失败就说明持久化路径把它当垃圾清掉了。
        GraphWorkspace reloaded = new GraphWorkspaceStore(temp).load(ALIAS);
        RelationWorkspaceEdge reloadedRelation = reloaded.getRelations().stream()
                .filter(edge -> edge.getId().equals(relationId)).findFirst().orElseThrow();
        assertEquals(GraphStatus.ignored, reloadedRelation.getStatus());
        assertEquals("跨库误判", reloadedRelation.getAttributes().get(
                WorkspaceMutationService.REJECTION_REASON_ATTR));

        // 半 2a：画布不能画出这条边（用重新加载出来的 workspace，不是内存里改过的那份，
        // 证明的是"存取之后再读出来喂给画布"这条真实路径，不是抄近路直接测内存对象）。
        GraphViewDto view = new GraphViewService(reloaded).buildView(
                null, null, 1, null, true, 500, 1000);
        assertEquals(0, view.getEdges().size(), "已忽略的关系不该出现在画布边列表里");

        // 半 2b：规则评估不能拿它去要求整改。
        Path rules = temp.resolve("relation-index.yaml");
        Files.writeString(rules, """
                kind: PolicyRuleSet
                id: relation-index
                title: Relation indexes
                version: "1"
                rules:
                  - id: relation-source-index
                    title: Relation source index
                    category: relation_column_index
                    severity: error
                    enforcement: required
                """);
        List<PolicyViolation> violations = new PolicyEvaluator().evaluate(
                reloaded, new RuleSetLoader().load(rules), List.of(), "evaluation-round-trip");
        assertTrue(violations.isEmpty(),
                "已忽略的关系不该被规则评估当成需要整改的 join 关系: " + violations);
    }
}
