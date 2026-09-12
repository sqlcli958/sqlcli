package com.sqlcli.graph.ui;

import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.index.WorkspaceIndexStore;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphUiServerLifecycleTest {

    @Test
    void awaitTerminationBlocksUntilServerStops() throws Exception {
        GraphUiServer server = new GraphUiServer(
                new GraphUiOptions(), new GraphWorkspace(),
                new GraphUiSession("test", false), new JsonHttpSupport(),
                new WorkspaceIndexStore());
        CountDownLatch awaiting = new CountDownLatch(1);

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            awaiting.countDown();
            try {
                server.awaitTermination();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        });

        assertTrue(awaiting.await(1, TimeUnit.SECONDS));
        assertFalse(future.isDone());
        server.stop();
        future.get(1, TimeUnit.SECONDS);
        assertTrue(future.isDone());
    }
}
