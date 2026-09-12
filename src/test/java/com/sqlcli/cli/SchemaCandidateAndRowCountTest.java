package com.sqlcli.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcli.graph.workspace.ColumnWorkspaceNode;
import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphIds;
import com.sqlcli.graph.workspace.GraphStatus;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sqlcli.graph.workspace.RelationType;
import com.sqlcli.graph.workspace.RelationWorkspaceEdge;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import com.sqlcli.graph.workspace.TermWorkspaceNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 候选在 CLI 侧可见 + 表行数进 CLI。
 *
 * <p>不连真库：COUNT(*) 那一段由 {@code TableRowCounterTest} 覆盖，这里覆盖写回与输出。
 */
class SchemaCandidateAndRowCountTest {

    private static final String ALIAS = "candidate-cli";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir Path temp;

    private GraphWorkspaceStore store;
    private String previousHome;

    @BeforeEach
    void setUp() {
        // 变更会写运行库，指到临时目录，别碰用户真实的 ~/.sql-cli
        previousHome = System.getProperty("sqlcli.home");
        System.setProperty("sqlcli.home", temp.resolve("home").toString());
        store = new GraphWorkspaceStore(temp);
    }

    @AfterEach
    void tearDown() {
        if (previousHome == null) {
            System.clearProperty("sqlcli.home");
        } else {
            System.setProperty("sqlcli.home", previousHome);
        }
    }

    @Test
    void describeTextMarksCandidateRelationsAndPrintsRowEstimate() throws Exception {
        seedWorkspace();

        String output = runText("describe", command -> command.setTableName("app.orders"));

        assertTrue(output.contains("Rows: 未知"), output);
        assertTrue(output.contains("[候选] orders.user_id -> users.id"), output);
        assertTrue(output.contains("Relationships:"), output);
    }

    @Test
    void persistedRowEstimateShowsUpInTextAndJson() throws Exception {
        seedWorkspace();
        GraphWorkspace workspace = store.load(ALIAS);
        String tableId = GraphIds.tableId(ALIAS, "app", "orders");

        SchemaActionCommand writer = command("describe", cmd -> { });
        assertEquals(0, writer.persistRowEstimate(workspace, tableId, 4200L));

        assertTrue(runText("describe", cmd -> cmd.setTableName("app.orders")).contains("Rows: 4200"));

        JsonNode data = runJson("describe", cmd -> cmd.setTableName("app.orders")).get("data");
        assertEquals(4200L, data.get("table").get("rowEstimate").asLong());
        // 行数写回不能把表变成候选
        assertEquals(GraphStatus.discovered.name(), data.get("table").get("status").asText());
    }

    /**
     * W3：回写行数是<b>记账</b>，不是内容变更——不许把 agent 的署名和整理时间盖掉。
     *
     * <p>真库反例：{@code erp_prop_report} 被 agent 写满了描述、值域和 grain，
     * {@code updatedBy} 却是 {@code system}，就是一次 {@code --refresh-row-count} 盖的。
     * 盖掉之后「谁最后动了业务语义」这个问题在图谱里就没有答案了。
     */
    @Test
    void rowCountWritebackKeepsWhoLastTouchedTheSemantics() throws Exception {
        seedWorkspace();
        GraphWorkspace workspace = store.load(ALIAS);
        String tableId = GraphIds.tableId(ALIAS, "app", "orders");
        TableWorkspaceNode orders = workspace.getTables().get(tableId);
        orders.setDescription("订单主表");
        orders.touch(GraphActor.agent);
        java.time.LocalDateTime curatedAt = orders.getUpdatedAt();
        store.save(workspace);

        assertEquals(0, command("describe", cmd -> { })
                .persistRowEstimate(store.load(ALIAS), tableId, 4200L));

        TableWorkspaceNode after = store.load(ALIAS).getTables().get(tableId);
        assertEquals(4200L, after.getRowEstimate(), "行数照写");
        assertEquals(GraphActor.agent, after.getUpdatedBy(), "记账动作不许认领 updatedBy");
        // updatedAt 一起冻结：describe 把它当「最近整理时间」打出来，只冻 updatedBy
        // 等于换一个字段继续撒谎
        assertEquals(curatedAt, after.getUpdatedAt(), "记账动作不许顶掉最近整理时间");
    }

    @Test
    void statsCountCandidateRelationsAndTerms() throws Exception {
        seedWorkspace();

        JsonNode data = runJson("stats", cmd -> { }).get("data");
        assertEquals(1, data.get("candidateRelations").asInt());
        assertEquals(1, data.get("candidateTerms").asInt());
        assertEquals(2, data.get("terms").asInt(), "另一个术语是 human 写入，不算候选");

        String text = runText("stats", cmd -> { });
        assertTrue(text.contains("Relationships: 1 (候选 1, 已忽略 0)"), text);
        assertTrue(text.contains("Terms: 2 (候选 1)"), text);
    }

    @Test
    void queryDepthExcludesIgnoredRelationInJsonAndText() throws Exception {
        seedWorkspaceWithIgnoredRelation();

        JsonNode related = runJson("query", cmd -> cmd.setTableName("app.orders")).get("data");
        assertEquals(1, related.size(), "被忽略的边不能进 --depth 的 BFS 结果：Agent 靠这个判断表关联");
        assertTrue(related.get(0).get("to").asText().contains("app.users"), related.toString());

        String text = runText("query", cmd -> cmd.setTableName("app.orders"));
        assertTrue(text.contains("orders.user_id -> users.id"), text);
        assertTrue(!text.contains("archive"), text);
    }

    @Test
    void describeShowsIgnoredRelationWithoutFiltering() throws Exception {
        seedWorkspaceWithIgnoredRelation();

        String text = runText("describe", cmd -> cmd.setTableName("app.orders"));
        assertTrue(text.contains("[已忽略] orders.deleted_ref -> archive.id"), text);

        JsonNode data = runJson("describe", cmd -> cmd.setTableName("app.orders")).get("data");
        boolean hasIgnored = false;
        for (JsonNode relation : data.get("relations")) {
            if ("ignored".equals(relation.get("status").asText())) {
                hasIgnored = true;
            }
        }
        assertTrue(hasIgnored, "describe 的 JSON 关系列表不过滤 ignored，跟文本一致");
    }

    // ---------------------------------------------------------------- 辅助

    private void seedWorkspace() throws Exception {
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");

        TableWorkspaceNode orders = TableWorkspaceNode.create(ALIAS, "app", "orders", GraphActor.extractor);
        orders.getColumns().add(ColumnWorkspaceNode.create("id"));
        orders.getColumns().add(ColumnWorkspaceNode.create("user_id"));
        workspace.getTables().put(orders.getId(), orders);

        TableWorkspaceNode users = TableWorkspaceNode.create(ALIAS, "app", "users", GraphActor.extractor);
        users.getColumns().add(ColumnWorkspaceNode.create("id"));
        workspace.getTables().put(users.getId(), users);

        RelationWorkspaceEdge relation = RelationWorkspaceEdge.create(ALIAS, RelationType.join_observed,
                GraphIds.columnId(ALIAS, "app", "orders", "user_id"),
                GraphIds.columnId(ALIAS, "app", "users", "id"), GraphActor.agent);
        relation.setConfidence(0.6);
        workspace.getRelations().add(relation);
        assertEquals(GraphStatus.candidate, relation.getStatus());

        TermWorkspaceNode candidateTerm = TermWorkspaceNode.create(ALIAS, "buyer", GraphActor.agent);
        workspace.getTerms().put(candidateTerm.getId(), candidateTerm);
        TermWorkspaceNode publishedTerm = TermWorkspaceNode.create(ALIAS, "order", GraphActor.human);
        workspace.getTerms().put(publishedTerm.getId(), publishedTerm);

        store.save(workspace);
    }

    /**
     * 在 {@link #seedWorkspace()} 的基础上加一张 archive 表和一条被人拒绝（ignored）的关系：
     * orders.deleted_ref -> archive.id。用来验证「必须过滤」（query --depth）与
     * 「显式展示不过滤」（describe）两条路径对同一条 ignored 边给出不同处理。
     */
    private void seedWorkspaceWithIgnoredRelation() throws Exception {
        seedWorkspace();
        GraphWorkspace workspace = store.load(ALIAS);

        TableWorkspaceNode orders = workspace.getTableByQualifiedName("app.orders");
        orders.getColumns().add(ColumnWorkspaceNode.create("deleted_ref"));

        TableWorkspaceNode archive = TableWorkspaceNode.create(ALIAS, "app", "archive", GraphActor.extractor);
        archive.getColumns().add(ColumnWorkspaceNode.create("id"));
        workspace.getTables().put(archive.getId(), archive);

        RelationWorkspaceEdge ignored = RelationWorkspaceEdge.create(ALIAS, RelationType.join_observed,
                GraphIds.columnId(ALIAS, "app", "orders", "deleted_ref"),
                GraphIds.columnId(ALIAS, "app", "archive", "id"), GraphActor.agent);
        ignored.setStatus(GraphStatus.ignored);
        workspace.getRelations().add(ignored);

        store.save(workspace);
    }

    private SchemaActionCommand command(String action, Consumer<SchemaActionCommand> configure) {
        SchemaActionCommand command = new SchemaActionCommand(store);
        command.setAlias(ALIAS);
        command.setAction(action);
        command.setCommandArgs(List.of(action));
        configure.accept(command);
        return command;
    }

    private String runText(String action, Consumer<SchemaActionCommand> configure) {
        return capture(command(action, configure)).stdout();
    }

    private JsonNode runJson(String action, Consumer<SchemaActionCommand> configure) throws Exception {
        SchemaActionCommand command = command(action, configure);
        command.setJsonOutput(true);
        return MAPPER.readTree(capture(command).stdout());
    }

    private record Captured(int exitCode, String stdout) {
    }

    private Captured capture(SchemaActionCommand command) {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int exitCode;
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            exitCode = command.executeCommand();
        } finally {
            System.setOut(originalOut);
        }
        assertEquals(0, exitCode, stdout.toString(StandardCharsets.UTF_8));
        return new Captured(exitCode, stdout.toString(StandardCharsets.UTF_8));
    }
}
