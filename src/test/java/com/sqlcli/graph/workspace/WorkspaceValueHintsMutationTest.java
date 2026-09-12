package com.sqlcli.graph.workspace;

import com.sqlcli.graph.ui.service.WorkspaceLockManager;
import com.sqlcli.graph.ui.service.WorkspaceMutationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UI 的值域编辑走列编辑 mutation 通道，与 CLI {@code schema edit --enum-values}
 * 写同一批字段、同一份校验（{@link ColumnValueHints#normalizeEnumValues}）。
 */
class WorkspaceValueHintsMutationTest {

    private static final String ALIAS = "hints";

    @TempDir Path temp;

    private String previousHome;

    @org.junit.jupiter.api.BeforeEach
    void keepHome() {
        previousHome = System.getProperty("sqlcli.home");
    }

    @org.junit.jupiter.api.AfterEach
    void restoreHome() {
        if (previousHome == null) {
            System.clearProperty("sqlcli.home");
        } else {
            System.setProperty("sqlcli.home", previousHome);
        }
    }

    @Test
    void columnPatchWritesEnumValuesFormatAndSamples() throws Exception {
        GraphWorkspaceStore store = seed();
        WorkspaceMutationService service = service(store);
        String tableId = GraphIds.tableId(ALIAS, "app", "orders");
        long revision = store.load(ALIAS).getManifest().getRevision();

        Map<String, Object> patch = new HashMap<>();
        patch.put("enumValues", List.of("0=待付款", "1=已付款"));
        patch.put("format", "  单字符状态码 ");
        patch.put("sampleValues", List.of("0", "1"));
        WorkspaceMutationService.MutationResult result = service.updateColumnDescription(
                ALIAS, tableId, "status", patch, revision, "值域编辑");
        assertTrue(result.isSuccess(), String.join("; ", result.getErrors()));

        ColumnValueHints hints = statusColumn(store).getValueHints();
        assertEquals(List.of("0=待付款", "1=已付款"), hints.getEnumValues());
        assertEquals("单字符状态码", hints.getFormat());
        assertEquals(List.of("0", "1"), hints.getSampleValues());
    }

    @Test
    void duplicateEnumValueFailsAndWritesNothing() throws Exception {
        GraphWorkspaceStore store = seed();
        WorkspaceMutationService service = service(store);
        String tableId = GraphIds.tableId(ALIAS, "app", "orders");
        long revision = store.load(ALIAS).getManifest().getRevision();

        WorkspaceMutationService.MutationResult result = service.updateColumnDescription(
                ALIAS, tableId, "status", Map.of("enumValues", List.of("1=已付款", "1=已发货")),
                revision, "值域编辑");
        assertFalse(result.isSuccess());
        assertTrue(result.getErrors().get(0).contains("值域取值重复"));
        assertNull(statusColumn(store).getValueHints());
    }

    @Test
    void emptyEnumListClearsTheDomainAndBlankFormatClearsFormat() throws Exception {
        GraphWorkspaceStore store = seed();
        WorkspaceMutationService service = service(store);
        String tableId = GraphIds.tableId(ALIAS, "app", "orders");
        long revision = store.load(ALIAS).getManifest().getRevision();
        Map<String, Object> fill = new HashMap<>();
        fill.put("enumValues", List.of("1=有"));
        fill.put("format", "F");
        assertTrue(service.updateColumnDescription(ALIAS, tableId, "status", fill, revision, "填").isSuccess());

        revision = store.load(ALIAS).getManifest().getRevision();
        Map<String, Object> clear = new HashMap<>();
        clear.put("enumValues", List.of());
        clear.put("format", "");
        assertTrue(service.updateColumnDescription(ALIAS, tableId, "status", clear, revision, "清").isSuccess());

        ColumnValueHints hints = statusColumn(store).getValueHints();
        assertTrue(hints == null || (!hints.hasData()));
    }

    // ---------------------------------------------------------------- 辅助

    private GraphWorkspaceStore seed() throws Exception {
        System.setProperty("sqlcli.home", temp.resolve("home").toString());
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create(ALIAS, "app", "orders", GraphActor.extractor);
        orders.getColumns().add(ColumnWorkspaceNode.create("status"));
        workspace.getTables().put(orders.getId(), orders);
        store.save(workspace);
        return store;
    }

    private ColumnWorkspaceNode statusColumn(GraphWorkspaceStore store) throws Exception {
        return store.load(ALIAS).getTables().get(GraphIds.tableId(ALIAS, "app", "orders"))
                .findColumn("status");
    }

    private WorkspaceMutationService service(GraphWorkspaceStore store) {
        return new WorkspaceMutationService(store, new WorkspaceValidator(), new WorkspaceLockManager());
    }
}
