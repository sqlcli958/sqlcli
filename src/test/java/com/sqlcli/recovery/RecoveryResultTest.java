package com.sqlcli.recovery;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回滚脚本和原始行现在存进运行库（{@code recovery_artifact}），不再写
 * {@code ~/.sql-cli/recovery/*.sql}——所以这里验的是那两段文本本身，
 * 尤其是回滚段必须是能直接切分执行的纯语句，不带注释抬头。
 */
class RecoveryResultTest {

    private RecoveryResult result(List<String> recoverySqls) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 7);
        row.put("status", "pending");
        return new RecoveryResult("UPDATE", "orders", 1, List.of(row), recoverySqls,
                "test", "UPDATE orders SET status='active' WHERE id=7");
    }

    @Test
    void rollbackTextIsExecutableStatementsOnly() {
        String rollback = result(List.of(
                "UPDATE orders SET status='pending' WHERE id=7;",
                "UPDATE orders SET status='paid' WHERE id=8;")).rollbackText();

        assertEquals("""
                UPDATE orders SET status='pending' WHERE id=7;
                UPDATE orders SET status='paid' WHERE id=8;""", rollback);
        // 抬头注释会把整段吞掉：RecoveryExecutor 按 ';' 切，以 '--' 开头的片段直接跳过。
        assertEquals(2, RecoveryExecutor.splitStatements(rollback).size());
    }

    @Test
    void backupTextKeepsOneOriginalRowPerLine() {
        assertEquals("id=7, status='pending'", result(List.of("x;")).backupText());
    }

    /** 一行都没匹配上时没有回滚脚本，调用方据此判断"这条语句无可回滚"。 */
    @Test
    void emptyRecoveryIsBlankNotNull() {
        RecoveryResult empty = new RecoveryResult("UPDATE", "orders", 0, List.of(), List.of(), "test", "x");
        assertTrue(empty.rollbackText().isBlank());
        assertTrue(empty.backupText().isBlank());
    }
}
