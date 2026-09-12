package com.sqlcli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcli.runstate.RunStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** `sql-cli task status <id> --json`：按 id 读 task_run + 事件的纯查询命令。 */
class TaskStatusCommandTest {

    @TempDir Path temp;

    @Test
    void printsTaskRunWithParsedEventPayloads() throws Exception {
        System.setProperty("sqlcli.home", temp.toString());
        PrintStream originalOut = System.out;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try {
            RunStateStore store = new RunStateStore();
            long id = store.createTaskRun("demo", "update", "cli", null);
            store.recordTaskEvent(id, "precheck", "{\"targetTable\":\"t\"}");
            store.finishTaskRun(id, "success");

            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            int exit = SqlCli.run("task", "status", String.valueOf(id), "--json");
            System.out.flush();
            assertEquals(0, exit);

            JsonNode root = new ObjectMapper().readTree(stdout.toString(StandardCharsets.UTF_8));
            assertTrue(root.get("ok").asBoolean());
            JsonNode data = root.get("data");
            assertEquals("update", data.get("taskRun").get("taskType").asText());
            assertEquals("success", data.get("taskRun").get("status").asText());
            // payload 内联为 JSON 对象，不是字符串套字符串
            assertEquals("t", data.get("events").get(0).get("payload").get("targetTable").asText());
        } finally {
            System.setOut(originalOut);
            System.clearProperty("sqlcli.home");
        }
    }

    @Test
    void unknownIdExitsOne() {
        System.setProperty("sqlcli.home", temp.toString());
        try {
            assertEquals(1, SqlCli.run("task", "status", "999999"));
            assertEquals(2, SqlCli.run("task", "status"));
        } finally {
            System.clearProperty("sqlcli.home");
        }
    }
}
