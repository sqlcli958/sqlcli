package com.sqlcli.graph.workspace;

import com.sqlcli.graph.ui.service.WorkspaceLockManager;
import com.sqlcli.graph.ui.service.WorkspaceMutationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceMutationConcurrencyTest {
    @TempDir Path temp;

    @Test
    void sameRevisionAllowsOnlyOneWriterAndRecordsHashes() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = GraphWorkspace.create("concurrent", "mysql");
        TableWorkspaceNode table = TableWorkspaceNode.create("concurrent", "app", "orders", GraphActor.extractor);
        workspace.getTables().put(table.getId(), table);
        store.save(workspace);
        long revision = workspace.getManifest().getRevision();
        WorkspaceMutationService first = service(store);
        WorkspaceMutationService second = service(store);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        var one = executor.submit(() -> {
            start.await();
            return first.updateTableDescription("concurrent", table.getId(), Map.of("description", "one"),
                    revision, "one");
        });
        var two = executor.submit(() -> {
            start.await();
            return second.updateTableDescription("concurrent", table.getId(), Map.of("description", "two"),
                    revision, "two");
        });
        start.countDown();
        int successes = (one.get().isSuccess() ? 1 : 0) + (two.get().isSuccess() ? 1 : 0);
        assertEquals(1, successes);
        GraphWorkspace saved = store.load("concurrent");
        assertEquals(revision + 1, saved.getManifest().getRevision());
        ChangeRecord change = saved.getChanges().get(saved.getChanges().size() - 1);
        assertNotNull(change.getBeforeHash());
        assertNotNull(change.getAfterHash());
        assertNotEquals(change.getBeforeHash(), change.getAfterHash());

        long current = saved.getManifest().getRevision();
        String description = saved.getTables().get(table.getId()).getDescription();
        WorkspaceMutationService.MutationResult noOp = first.updateTableDescription(
                "concurrent", table.getId(), Map.of("description", description), current, "same");
        assertTrue(noOp.isSuccess());
        assertNull(noOp.getChangeId());
        assertEquals(current, store.load("concurrent").getManifest().getRevision());
    }

    private WorkspaceMutationService service(GraphWorkspaceStore store) {
        return new WorkspaceMutationService(store, new WorkspaceValidator(), new WorkspaceLockManager());
    }
}
