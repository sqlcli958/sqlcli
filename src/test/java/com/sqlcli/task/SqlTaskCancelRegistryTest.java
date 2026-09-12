package com.sqlcli.task;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlTaskCancelRegistryTest {

    @Test
    void cancelRunsRegisteredActionExactlyOnce() {
        AtomicInteger hits = new AtomicInteger();
        SqlTaskCancelRegistry.register("t1", hits::incrementAndGet);

        assertTrue(SqlTaskCancelRegistry.cancel("t1"));
        assertEquals(1, hits.get());
        // 第二次中止同一 token：动作已被移除，不重复执行
        assertFalse(SqlTaskCancelRegistry.cancel("t1"));
        assertEquals(1, hits.get());
    }

    @Test
    void unknownOrUnregisteredTokenIsNotCancellable() {
        assertFalse(SqlTaskCancelRegistry.cancel("nope"));
        assertFalse(SqlTaskCancelRegistry.cancel(null));

        AtomicInteger hits = new AtomicInteger();
        SqlTaskCancelRegistry.register("t2", hits::incrementAndGet);
        SqlTaskCancelRegistry.unregister("t2");
        // 执行已结束（unregister 之后）就没什么可中止的了
        assertFalse(SqlTaskCancelRegistry.cancel("t2"));
        assertEquals(0, hits.get());
    }

    @Test
    void blankTokenIsNeverRegistered() {
        SqlTaskCancelRegistry.register(null, () -> { });
        SqlTaskCancelRegistry.register("  ", () -> { });
        assertFalse(SqlTaskCancelRegistry.cancel("  "));
    }
}
