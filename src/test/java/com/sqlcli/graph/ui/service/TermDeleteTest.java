package com.sqlcli.graph.ui.service;

import com.sqlcli.config.AliasConfigStore;
import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.config.SettingsConfig;
import com.sqlcli.graph.workspace.*;
import com.sqlcli.runstate.ApprovalBatchRow;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 删除术语——**连同它的边一起**。
 *
 * <p>关键的一条是 {@link #manualAliasStagesTheWholeCascadeAsOneBatch()}：级联曾经被禁，
 * 理由是审批 payload 只装一个对象，`manual` 别名上批准时重放的只有术语那一条，
 * 边会留下来变成 dangling_relation_source——而且是批准之后才出现。
 * 多对象补丁 + 批次原子重放之后这个理由不成立了，这条测试守的就是它别退回去。
 */
class TermDeleteTest {

    private static final String ALIAS = "term-delete-test";

    @TempDir Path temp;

    private GraphWorkspaceStore store;
    private WorkspaceMutationService service;
    private String previousHome;
    private String previousConfigRoot;

    @BeforeEach
    void setUp() {
        previousHome = System.getProperty("sqlcli.home");
        previousConfigRoot = System.getProperty(SettingsConfig.CONFIG_ROOT_PROPERTY);
        System.setProperty("sqlcli.home", temp.resolve("home").toString());
        System.setProperty(SettingsConfig.CONFIG_ROOT_PROPERTY, temp.resolve("config-root").toString());
        store = new GraphWorkspaceStore(temp);
        service = new WorkspaceMutationService(store, new WorkspaceValidator(), new WorkspaceLockManager());
    }

    @AfterEach
    void tearDown() {
        restore("sqlcli.home", previousHome);
        restore(SettingsConfig.CONFIG_ROOT_PROPERTY, previousConfigRoot);
    }

    private void graphApproval(String mode) {
        DatabaseConfig config = new DatabaseConfig();
        config.setType("sqlite");
        config.setDatabase(temp.resolve("demo.db").toString());
        config.setGraphApproval(mode);
        new AliasConfigStore().saveAlias(ALIAS, config);
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    @Test
    void deletesATermThatNobodyMapped() throws Exception {
        GraphWorkspace workspace = seed(false);
        String termId = "term:" + ALIAS + ":顺序巡检";

        var result = service.deleteTerm(ALIAS, termId,
                workspace.getManifest().getRevision(), "空壳术语");

        assertTrue(result.isSuccess(), result.getErrors().toString());
        assertFalse(store.load(ALIAS).getTerms().containsKey(termId));
    }

    @Test
    void deletesTheMappingsAlongWithTheTerm() throws Exception {
        GraphWorkspace workspace = seed(true);
        String termId = "term:" + ALIAS + ":报事";

        var result = service.deleteTerm(ALIAS, termId,
                workspace.getManifest().getRevision(), "空壳术语");

        assertTrue(result.isSuccess(), result.getErrors().toString());
        GraphWorkspace after = store.load(ALIAS);
        assertFalse(after.getTerms().containsKey(termId));
        // 留一条就是一条 dangling_relation_source，而且要人自己去关系列表里找
        assertTrue(after.getRelations().stream().noneMatch(edge -> termId.equals(edge.getFrom())),
                "边没跟着删，图谱里留下了悬空引用");
    }

    /**
     * manual 别名上整条级联是**一个批次**：术语一条、每条边一条，一次裁决、原子落地。
     * 少了这条，批准之后图谱里会留下指向已删术语的边。
     */
    @Test
    void manualAliasStagesTheWholeCascadeAsOneBatch() throws Exception {
        graphApproval("manual");
        GraphWorkspace workspace = seed(true);
        String termId = "term:" + ALIAS + ":报事";

        var staged = service.deleteTerm(ALIAS, termId,
                workspace.getManifest().getRevision(), "空壳术语");

        assertTrue(staged.isSuccess(), staged.getErrors().toString());
        assertNull(staged.getChangeId(), "manual 别名上图谱这时不该被动过");
        assertTrue(store.load(ALIAS).getTerms().containsKey(termId));

        RunStateStore runState = new RunStateStore();
        List<ApprovalBatchRow> batches = runState.listBatches(ALIAS, null, 10);
        assertEquals(1, batches.size(), "术语 + 边应该合成一个批次，不是几条散条目");
        long batchId = batches.get(0).id();
        assertEquals(2, runState.listBatchItems(batchId).size(), "1 条术语 + 1 条边");

        new ApprovalEffects(store, Map.of(), null, runState, null)
                .decideBatch(batchId, true, null, "tester");

        GraphWorkspace after = store.load(ALIAS);
        assertFalse(after.getTerms().containsKey(termId), "批准之后术语才真被删");
        assertTrue(after.getRelations().stream().noneMatch(edge -> termId.equals(edge.getFrom())),
                "边要跟着一起落地删掉，否则批准之后才冒出悬空关系");
    }

    @Test
    void deletingSomethingThatIsNotThereFailsLoudly() throws Exception {
        seed(false);
        var result = service.deleteTerm(ALIAS, "term:" + ALIAS + ":不存在", -1, null);
        assertFalse(result.isSuccess());
    }

    private GraphWorkspace seed(boolean withMapping) throws Exception {
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");
        TableWorkspaceNode table = TableWorkspaceNode.create(ALIAS, "app", "report", GraphActor.extractor);
        table.getColumns().add(ColumnWorkspaceNode.create("id"));
        workspace.getTables().put(table.getId(), table);

        for (String name : java.util.List.of("顺序巡检", "报事")) {
            TermWorkspaceNode term = TermWorkspaceNode.create(ALIAS, name, GraphActor.agent);
            workspace.getTerms().put(term.getId(), term);
        }
        if (withMapping) {
            workspace.getRelations().add(RelationWorkspaceEdge.create(ALIAS, RelationType.term_mapping,
                    "term:" + ALIAS + ":报事", table.getId(), GraphActor.agent));
        }
        store.save(workspace);
        return store.load(ALIAS);
    }
}
