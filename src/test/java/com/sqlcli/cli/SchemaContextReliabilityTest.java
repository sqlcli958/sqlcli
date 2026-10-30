package com.sqlcli.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcli.graph.workspace.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SchemaContextReliabilityTest {
    @TempDir Path temp;
    private String previousHome;
    private GraphWorkspaceStore store;

    @BeforeEach void setup() throws Exception {
        previousHome = System.getProperty("sqlcli.home");
        System.setProperty("sqlcli.home", temp.resolve("runtime").toString());
        store = new GraphWorkspaceStore(temp.resolve("graph"));
        GraphWorkspace workspace = GraphWorkspace.create("demo", "mysql");
        TableWorkspaceNode table = TableWorkspaceNode.create("demo", "app", "orders", GraphActor.extractor);
        ColumnWorkspaceNode column = ColumnWorkspaceNode.create("del_flag");
        ColumnValueHints hints = new ColumnValueHints();
        hints.setEnumValues(List.of("0=删除", "1=存在"));
        column.setValueHints(hints);
        column.setDescription("记录是否有效");
        column.setVerified(true);
        column.getAttributes().put(ColumnWorkspaceNode.ATTR_SEMANTIC_BASIS_CHANGES,
                Map.of("comment", Map.of("before", "0=存在", "after", "0=删除")));
        table.getColumns().add(column);
        workspace.getTables().put(table.getId(), table);
        store.save(workspace);
        Path policy = store.workspacePath("demo").resolve("policy");
        Files.createDirectories(policy.resolve("rules"));
        Files.writeString(policy.resolve("bindings.yaml"), "ruleSets: [rules/test.yaml]\n");
        Files.writeString(policy.resolve("rules/test.yaml"), """
                kind: PolicyRuleSet
                id: test
                title: test
                version: '1'
                rules:
                  - id: soft_delete
                    title: 有效记录
                    severity: info
                    enforcement: advisory
                    category: query_convention
                    when:
                      columnNameRegex: '^del_flag$'
                      columnValueMeanings: {"0": "存在"}
                    statement:
                      queryFilter: '{column} = 0'
                """);
    }

    @AfterEach void restore() {
        if (previousHome == null) System.clearProperty("sqlcli.home");
        else System.setProperty("sqlcli.home", previousHome);
    }

    @Test void describeShowsPendingBasisAndDoesNotOfferTheWrongFilter() throws Exception {
        String text = run("describe", false, null);
        assertTrue(text.contains("语义来源变化"), text);
        assertTrue(text.contains("约定待确认"), text);
        assertFalse(text.contains("约定过滤:"), text);
        assertTrue(run("describe", false, "del_flag").contains("原确认需要复核"));
        var data = new ObjectMapper().readTree(run("describe", true, null)).path("data");
        assertEquals(0, data.path("conventions").size());
        assertEquals("soft_delete", data.path("unresolvedConventions").get(0).path("ruleId").asText());
        assertTrue(data.path("columns").get(0).path("attributes").has("semanticBasisChanges"));
    }

    @Test void searchJsonPreservesUnresolvedConventions() throws Exception {
        var data = new ObjectMapper().readTree(run("search", true, null)).path("data");
        assertFalse(data.isEmpty());
        assertTrue(data.findValues("unresolvedConventions").stream().anyMatch(node -> !node.isEmpty()));
    }

    private String run(String action, boolean json, String column) throws Exception {
        SchemaActionCommand command = new SchemaActionCommand(store);
        command.setAlias("demo");
        command.setAction(action);
        command.setTableName("app.orders");
        command.setKeyword("orders");
        command.setEditColumn(column);
        command.setJsonOutput(json);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try (PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            assertEquals(0, command.executeCommand());
        } finally { System.setOut(original); }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
