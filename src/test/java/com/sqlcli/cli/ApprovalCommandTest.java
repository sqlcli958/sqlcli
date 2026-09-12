package com.sqlcli.cli;

import com.sqlcli.SqlCli;
import com.sqlcli.runstate.RunStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `<alias> approval`：队列能不能被看见。
 *
 * <p>三条断言对应三个真实的坑：队列有东西时要给出去哪裁决；本别名空但别处有时
 * 不能显示成「清空了」；裁决动作不在这里（只有一个入口，在 Web UI）。
 */
class ApprovalCommandTest {

    @TempDir Path temp;

    private String previousHome;

    @BeforeEach
    void setUp() {
        previousHome = System.getProperty("sqlcli.home");
        System.setProperty("sqlcli.home", temp.resolve("home").toString());
    }

    @AfterEach
    void tearDown() {
        if (previousHome == null) System.clearProperty("sqlcli.home");
        else System.setProperty("sqlcli.home", previousHome);
    }

    @Test
    void pendingItemsAreListedWithTheirAgeAndWhereToDecideThem() {
        RunStateStore store = new RunStateStore();
        long id = store.createApproval("demo", "graph", "术语「报事回访」", null,
                null, "term:demo:报事回访", "{}");

        String out = run("demo", "approval");

        assertTrue(out.contains("#" + id), out);
        assertTrue(out.contains("术语「报事回访」"), out);
        assertTrue(out.contains("已等"), "挂了多久才是队列腐烂的信号，不是内容\n" + out);
        assertTrue(out.contains("评审页"), "看得见但不知道去哪裁决等于没看见\n" + out);
    }

    /**
     * 待审批队列是跨别名的。只报本别名会造出一个比「没有入口」更糟的假阴性——
     * 看着像清空了，而另一个库上正挂着一堆。
     */
    @Test
    void anEmptyAliasStillReportsWhatIsWaitingElsewhere() {
        RunStateStore store = new RunStateStore();
        store.createApproval("other-db", "graph", "另一个库上的变更", null, null, "table:other-db:t", "{}");

        String out = run("demo", "approval");

        assertTrue(out.contains("没有待审批"), out);
        assertTrue(out.contains("其他数据源还有 1 条"), out);
        assertFalse(out.contains("另一个库上的变更"), "别的别名的内容不该混进这个别名的列表\n" + out);
    }

    /** 裁决只在 Web UI 评审页——这里多一个 approve 就是第二个入口。 */
    @Test
    void decidingIsNotOfferedHere() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        int exitCode;
        try (PrintStream stream = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            System.setErr(stream);
            exitCode = SqlCli.run("demo", "approval", "approve");
        } finally {
            System.setErr(originalErr);
        }
        assertEquals(2, exitCode);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Web UI"),
                err.toString(StandardCharsets.UTF_8));
    }

    private static String run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream stream = new PrintStream(out, true, StandardCharsets.UTF_8)) {
            System.setOut(stream);
            assertEquals(0, SqlCli.run(args));
        } finally {
            System.setOut(originalOut);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
