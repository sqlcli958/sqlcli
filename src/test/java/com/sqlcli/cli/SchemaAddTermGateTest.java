package com.sqlcli.cli;

import com.sqlcli.graph.workspace.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code add-term} 的写入闸门。
 *
 * <p>只拒两条，判据都低到无可争议——这是 breaking change，门槛高一点就会挡住正当写入：
 * <ul>
 *   <li>同义词、映射**两样全空**：即使给了 {@code --description}，这种术语也跟
 *       businessName 完全重叠——搜不出任何独有的东西，却占着检索的最高权重档。
 *       真库上就躺着一条（`顺序巡检`）</li>
 *   <li>{@code --primary-target} 指向不存在的对象：死引用之后每次展开子图都静默展不开，
 *       没有任何地方会报错</li>
 * </ul>
 * 反过来，「映射的表之间连不上」只警告不拒——那多半是关系还没录进图谱，
 * 硬拒会逼人先补关系才能写术语，因果颠倒。
 */
class SchemaAddTermGateTest {

    private static final String ALIAS = "commands";

    @TempDir Path temp;

    @Test
    void rejectsATermThatCanNeverBeFound() throws Exception {
        GraphWorkspaceStore store = seed();
        SchemaActionCommand cmd = addTerm(store, "顺序巡检");

        assertEquals(1, cmd.executeCommand(), "两样全空的术语被写进去了");
        assertNull(store.load(ALIAS).getTerms().get("term:" + ALIAS + ":顺序巡检"));
    }

    @Test
    void rejectsADescriptionOnlyTermBecauseItOverlapsWithBusinessName() throws Exception {
        GraphWorkspaceStore store = seed();
        SchemaActionCommand cmd = addTerm(store, "报事");
        cmd.setDescription("住户提交的一次维修请求");

        assertEquals(1, cmd.executeCommand(), "只有 description、没有 map/aliases 的术语被写进去了");
        assertNull(store.load(ALIAS).getTerms().get("term:" + ALIAS + ":报事"));
    }

    @Test
    void rejectsADanglingPrimaryTarget() throws Exception {
        GraphWorkspaceStore store = seed();
        SchemaActionCommand cmd = addTerm(store, "报事");
        cmd.setDescription("住户提交的一次维修请求");
        cmd.setPrimaryTargetRef("app.不存在的表");

        assertNotEquals(0, cmd.executeCommand(), "入口指向一个不存在的对象却写进去了");
        assertNull(store.load(ALIAS).getTerms().get("term:" + ALIAS + ":报事"));
    }

    @Test
    void acceptsAScenarioAndStoresEntryAndFilters() throws Exception {
        GraphWorkspaceStore store = seed();
        SchemaActionCommand cmd = addTerm(store, "报事");
        cmd.setDescription("住户提交的一次维修请求");
        cmd.setAliasesCsv("工单,报修");
        cmd.setPrimaryTargetRef("app.report");
        cmd.setTermFilters(List.of("state IN (0,1,6)", "subject_id = :subjectId"));

        assertEquals(0, cmd.executeCommand());
        TermWorkspaceNode term = store.load(ALIAS).getTerms().get("term:" + ALIAS + ":报事");
        assertEquals(GraphIds.tableId(ALIAS, "app", "report"), term.getPrimaryTarget());
        assertEquals(List.of("state IN (0,1,6)", "subject_id = :subjectId"), term.getFilters());
    }

    @Test
    void topUpOfAnExistingTermIsNotBlocked() throws Exception {
        GraphWorkspaceStore store = seed();
        SchemaActionCommand first = addTerm(store, "报事");
        first.setDescription("住户提交的一次维修请求");
        first.setAliasesCsv("工单,报修");
        assertEquals(0, first.executeCommand());

        // 只补展示名——本次入参两样全空，但术语已经挂了同义词，闸门看的是合并后的结果
        SchemaActionCommand topUp = addTerm(store, "报事");
        topUp.setDisplayName("报事单");
        assertEquals(0, topUp.executeCommand(), "给已有同义词的术语补展示名被误拦了");
        assertEquals("报事单", store.load(ALIAS).getTerms()
                .get("term:" + ALIAS + ":报事").getDisplayName());
    }

    /**
     * 报错必须给可直接粘贴的替代命令，不能只说「参数不合法」——否则 agent 撞上报错
     * 之后大概率直接放弃写入，而不是改写成 businessName。
     */
    @Test
    void rejectionMessageGivesACopyPasteableAlternative() throws Exception {
        GraphWorkspaceStore store = seed();
        SchemaActionCommand cmd = addTerm(store, "顺序巡检");

        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        String stderr;
        try (PrintStream err = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setErr(err);
            cmd.executeCommand();
        } finally {
            System.setErr(originalErr);
        }
        stderr = captured.toString(StandardCharsets.UTF_8);

        assertTrue(stderr.contains("schema edit --table <schema.table> --business-name \"顺序巡检\""),
                "报错里没有可直接粘贴的替代命令 -> " + stderr);
        assertTrue(stderr.contains("schema edit --column <schema.table.column> --business-name \"顺序巡检\""),
                "报错里没有可直接粘贴的替代命令 -> " + stderr);
    }

    private SchemaActionCommand addTerm(GraphWorkspaceStore store, String name) {
        SchemaActionCommand cmd = new SchemaActionCommand(store);
        cmd.setAlias(ALIAS);
        cmd.setAction("add-term");
        cmd.setTermName(name);
        cmd.setCommandArgs(List.of("add-term", name));
        return cmd;
    }

    private GraphWorkspaceStore seed() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");
        TableWorkspaceNode table = TableWorkspaceNode.create(ALIAS, "app", "report", GraphActor.extractor);
        table.getColumns().add(ColumnWorkspaceNode.create("id"));
        workspace.getTables().put(table.getId(), table);
        store.save(workspace);
        return store;
    }
}
