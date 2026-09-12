package com.sqlcli.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话身份的三级优先级，以及「取不到就是 null」。
 *
 * <p>最后一条最重要：每条命令编一个新 id 等于没有会话，还会让
 * 「这批改动是一起做的」这个判断变成假的。
 */
class SessionContextTest {

    @TempDir Path temp;

    private String previousHome;

    @BeforeEach
    void setUp() {
        previousHome = System.getProperty("sqlcli.home");
        System.setProperty("sqlcli.home", temp.toString());
        SessionContext.reset();
    }

    @AfterEach
    void tearDown() throws Exception {
        SessionContext.end();
        SessionContext.reset();
        if (previousHome == null) System.clearProperty("sqlcli.home");
        else System.setProperty("sqlcli.home", previousHome);
    }

    @Test
    void withoutASessionEverythingIsNull() {
        // 环境变量在测试进程里设不了，这里验的是「没有状态文件、没有参数」这一格
        assertNull(SessionContext.sessionId(), "不许编占位 id");
        assertNull(SessionContext.agentId());
        assertNull(SessionContext.batchId());
    }

    @Test
    void beginWritesAStateFileScopedToTheWorkingDirectory() throws Exception {
        String id = SessionContext.begin("claude");

        assertNotNull(id);
        assertTrue(Files.exists(SessionContext.stateFile()));
        assertEquals(id, SessionContext.sessionId());
        assertEquals("claude", SessionContext.agentId());
        assertEquals("state-file", SessionContext.describe().get("source"));
    }

    @Test
    void beginTwiceKeepsTheSameId() throws Exception {
        String first = SessionContext.begin(null);
        String second = SessionContext.begin("claude");

        assertEquals(first, second, "重复 begin 换号会把一次会话切成两半");
        assertEquals("claude", SessionContext.agentId(), "但 agent 可以补上");
    }

    @Test
    void theCommandLineFlagWinsOverTheStateFile() throws Exception {
        SessionContext.begin("claude");
        SessionContext.applyOverrides("s-explicit", "other-agent", 77L);

        assertEquals("s-explicit", SessionContext.sessionId());
        assertEquals("other-agent", SessionContext.agentId());
        assertEquals(77L, SessionContext.batchId());
    }

    @Test
    void endRemovesTheSession() throws Exception {
        SessionContext.begin(null);

        assertTrue(SessionContext.end());
        assertFalse(SessionContext.end(), "已经结束的会话再 end 一次不该报成功");
        assertNull(SessionContext.sessionId());
    }
}
