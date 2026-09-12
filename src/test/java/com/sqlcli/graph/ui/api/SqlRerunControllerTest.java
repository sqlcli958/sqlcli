package com.sqlcli.graph.ui.api;

import com.sqlcli.approval.ApprovalGate;
import com.sqlcli.connection.ConnectionManager;
import com.sqlcli.graph.ui.JsonHttpSupport;
import com.sqlcli.runstate.RunStateStore;
import com.sqlcli.task.SqlTaskModule;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqlRerunControllerTest {

    // validate() 是纯字符串检查，从不碰 RunStateStore；这里只是给构造函数一个不指向
    // 真实 ~/.sql-cli/sqlcli.db 的路径，不需要 @TempDir 那一套生命周期。
    private final SqlRerunController controller = newController();

    private static SqlRerunController newController() {
        try {
            Path dir = Files.createTempDirectory("sql-rerun-controller-test");
            RunStateStore runState = new RunStateStore(dir.resolve("sqlcli.db"), dir.resolve("history"));
            SqlTaskModule taskModule = new SqlTaskModule(
                    new ConnectionManager(), runState, new ApprovalGate(runState, 1000));
            return new SqlRerunController(Map.of(), "demo", "http://127.0.0.1:8080", new JsonHttpSupport(), taskModule);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void readOnlyStatementsPass() {
        assertEquals("SELECT * FROM users WHERE id = 1",
                controller.validate("SELECT * FROM users WHERE id = 1"));
        assertEquals("WITH t AS (SELECT 1 AS a) SELECT a FROM t",
                controller.validate("WITH t AS (SELECT 1 AS a) SELECT a FROM t"));
        assertEquals("SHOW TABLES", controller.validate("SHOW TABLES"));
        assertEquals("EXPLAIN SELECT 1", controller.validate("EXPLAIN SELECT 1"));
    }

    @Test
    void writeStatementsAreRejected() {
        for (String sql : new String[]{
                "UPDATE users SET name = 'x' WHERE id = 1",
                "DELETE FROM users WHERE id = 1",
                "INSERT INTO users(id) VALUES (1)",
                "DROP TABLE users",
                "TRUNCATE TABLE users"}) {
            IllegalArgumentException error =
                    assertThrows(IllegalArgumentException.class, () -> controller.validate(sql));
            org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("只读"), sql);
        }
    }

    @Test
    void multipleStatementsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.validate("SELECT 1; DROP TABLE users"));
    }

    @Test
    void redactedHistoryIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.validate("SELECT * FROM users WHERE name = '<redacted>'"));
        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("脱敏"));
    }

    @Test
    void blankSqlIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> controller.validate("  "));
        assertThrows(IllegalArgumentException.class, () -> controller.validate(null));
    }
}
