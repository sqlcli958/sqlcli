package com.sqlcli.graph.workspace;

import com.sqlcli.graph.workspace.index.WorkspaceIndexedSearchEngine;
import com.sqlcli.graph.workspace.index.WorkspaceIndexer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 中文多词检索与搜索解释：两个引擎（实时 / 索引）行为对齐。
 */
class WorkspaceSearchMatchingTest {

    private static final String ALIAS = "search-unit";

    @Test
    void tokenizeSplitsCjkIntoBigramsAndKeepsAsciiWords() {
        assertEquals(List.of("买家", "家手", "手机"), SearchMatching.tokenize("买家手机"));
        assertEquals(List.of("买家", "手机"), SearchMatching.tokenize("买家 手机"));
        assertEquals(List.of("buyer", "phone"), SearchMatching.tokenize("buyer phone"));
        assertEquals(List.of("买家", "phone"), SearchMatching.tokenize("买家phone"));
        assertEquals(List.of("单"), SearchMatching.tokenize("单"));
        assertTrue(SearchMatching.tokenize("  ").isEmpty());
    }

    // ------------------------------------------------------------ 实时引擎

    @Test
    void liveEngineKeepsSingleWordHits() {
        WorkspaceSearchEngine engine = new WorkspaceSearchEngine(workspace());

        assertEquals(1, engine.search("订单").stream().filter(r -> "table".equals(r.getType())).count());
        assertNotNull(column(engine.search("买家手机号")), "整串命中业务名不能回归");
        assertNotNull(column(engine.search("手机号")), "语义类型中文标签仍要命中");
    }

    @Test
    void liveEngineMatchesMultipleTermsAcrossFields() {
        WorkspaceSearchEngine engine = new WorkspaceSearchEngine(workspace());

        WorkspaceSearchEngine.SearchResult hit = column(engine.search("买家 手机"));

        assertNotNull(hit, "「买家 手机」应命中业务名「买家手机号」");
        assertEquals("businessName", hit.getMatchedField());
        assertEquals("买家手机号", hit.getMatchedText());
        assertEquals("买家手机号", hit.getMatchValue(), "matchValue 与 matchedText 同值");
        assertEquals("phone", hit.getSemanticType());
        assertEquals("下单人联系方式", hit.getComment());
    }

    @Test
    void liveEngineMatchesDescriptionSeparatelyFromComment() {
        WorkspaceSearchEngine engine = new WorkspaceSearchEngine(workspace());

        WorkspaceSearchEngine.SearchResult hit = engine.search("电话确认").stream()
                .filter(r -> "buyer_note".equals(r.getColumnName()))
                .findFirst().orElse(null);

        assertNotNull(hit, "description 里的词也要能命中，不止 comment");
        assertEquals("老客户，下单前请电话确认", hit.getDescription());
        assertNull(hit.getComment(), "这一列没有数据库注释，comment 应为 null，不能和 description 混在一起");
    }

    @Test
    void liveEngineIgnoresUnrelatedWords() {
        WorkspaceSearchEngine engine = new WorkspaceSearchEngine(workspace());

        assertTrue(engine.search("库存 天气").isEmpty(), "毫不相关的词不得误命中");
    }

    // ------------------------------------------------------------ 索引引擎

    @Test
    void indexedEngineKeepsSingleWordHits() {
        WorkspaceIndexedSearchEngine engine = indexed();

        assertFalse(engine.search("订单").isEmpty());
        assertEquals("table", engine.search("订单").get(0).getType());
        assertNotNull(hit(engine.search("手机号"), "column"), "索引里存英文枚举，搜中文标签也要命中");
    }

    @Test
    void indexedEngineMatchesMultipleTermsAcrossFields() {
        WorkspaceIndexedSearchEngine.SearchHit hit = hit(indexed().search("买家 手机"), "column");

        assertNotNull(hit, "「买家 手机」应命中业务名「买家手机号」");
        assertEquals("businessName", hit.getMatchedField());
        assertEquals("买家手机号", hit.getMatchedText());
        assertEquals("phone", hit.getSemanticType());
        assertEquals("下单人联系方式", hit.getComment());
    }

    @Test
    void indexedEngineIgnoresUnrelatedWords() {
        assertTrue(indexed().search("库存 天气").isEmpty(), "毫不相关的词不得误命中");
    }

    @Test
    void fullCoverageOutranksPartialCoverage() {
        List<WorkspaceIndexedSearchEngine.SearchHit> hits = indexed().search("买家 手机");

        WorkspaceIndexedSearchEngine.SearchHit both = hit(hits, "column");
        Optional<WorkspaceIndexedSearchEngine.SearchHit> onlyBuyer = hits.stream()
                .filter(h -> "buyer_address".equals(h.getColumn())).findFirst();

        assertTrue(onlyBuyer.isPresent(), "只命中「买家」的字段仍算候选，但排在后面");
        assertTrue(both.getScore() > onlyBuyer.get().getScore());
    }

    @Test
    void indexedEngineMatchesDescriptionSeparatelyFromComment() {
        WorkspaceIndexedSearchEngine.SearchHit hit = indexed().search("电话确认").stream()
                .filter(h -> "buyer_note".equals(h.getColumn()))
                .findFirst().orElse(null);

        assertNotNull(hit, "索引引擎也要能命中 description 里的词");
        assertEquals("老客户，下单前请电话确认", hit.getDescription());
        assertNull(hit.getComment());
    }

    @Test
    void candidateTermIsFlaggedInIndexedResults() {
        WorkspaceIndexedSearchEngine.SearchHit hit = hit(indexed().search("购买用户"), "term");

        assertNotNull(hit);
        assertTrue(hit.isCandidate(), "agent 写入的术语是候选，搜索结果要标出来");
    }

    /**
     * 负向词：查询恰好等于其中一条时，这条术语这次整个不参与匹配——挡跨域撞词
     * （T4/S5：`扫码` 挂着巡检域点位，但 `扫码打卡` 在考勤域另有所指）。
     */
    @Test
    void liveEngineSuppressesTermWhenQueryMatchesNegativeAlias() {
        WorkspaceSearchEngine engine = new WorkspaceSearchEngine(workspaceWithNegativeAliasTerm());

        assertTrue(engine.search("扫码打卡").stream().noneMatch(r -> "term".equals(r.getType())),
                "查询恰好命中负向词，术语不该出现在结果里");
        assertFalse(engine.search("扫码").isEmpty(), "负向词只挡它自己列的那句查询，不挡术语本身");
        assertTrue(engine.search("扫码").stream().anyMatch(r -> "term".equals(r.getType())),
                "「扫码」本身仍要能命中这条术语");
    }

    @Test
    void indexedEngineSuppressesTermWhenQueryMatchesNegativeAlias() {
        WorkspaceIndexedSearchEngine engine =
                new WorkspaceIndexedSearchEngine(new WorkspaceIndexer().rebuild(workspaceWithNegativeAliasTerm()));

        assertTrue(engine.search("扫码打卡").stream().noneMatch(h -> "term".equals(h.getType())),
                "查询恰好命中负向词，术语不该出现在索引搜索结果里");
        assertTrue(engine.search("扫码").stream().anyMatch(h -> "term".equals(h.getType())));
    }

    private GraphWorkspace workspaceWithNegativeAliasTerm() {
        GraphWorkspace workspace = workspace();
        TermWorkspaceNode term = TermWorkspaceNode.create(ALIAS, "扫码", GraphActor.agent);
        term.getNegativeAliases().add("扫码打卡");
        workspace.getTerms().put(term.getId(), term);
        return workspace;
    }

    // ---------------------------------------------------------------- 辅助

    private GraphWorkspace workspace() {
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");

        TableWorkspaceNode table = TableWorkspaceNode.create(ALIAS, "trade", "orders", GraphActor.extractor);
        table.setComment("订单主表");
        workspace.getTables().put(table.getId(), table);

        ColumnWorkspaceNode phone = ColumnWorkspaceNode.create("buyer_mobile");
        phone.setBusinessName("买家手机号");
        phone.setComment("下单人联系方式");
        phone.setSemanticType(SemanticType.phone);
        table.getColumns().add(phone);

        ColumnWorkspaceNode address = ColumnWorkspaceNode.create("buyer_address");
        address.setBusinessName("买家收货地址");
        table.getColumns().add(address);

        // 只写 description、不写 comment：验证检索能命中业务描述，且两个字段不会被混在一起
        ColumnWorkspaceNode note = ColumnWorkspaceNode.create("buyer_note");
        note.setDescription("老客户，下单前请电话确认");
        table.getColumns().add(note);

        TermWorkspaceNode term = TermWorkspaceNode.create(ALIAS, "buyer", GraphActor.agent);
        term.setDisplayName("买家");
        term.getAliases().add("购买用户");
        workspace.getTerms().put(term.getId(), term);
        return workspace;
    }

    private WorkspaceIndexedSearchEngine indexed() {
        return new WorkspaceIndexedSearchEngine(new WorkspaceIndexer().rebuild(workspace()));
    }

    private WorkspaceSearchEngine.SearchResult column(List<WorkspaceSearchEngine.SearchResult> results) {
        return results.stream()
                .filter(r -> "buyer_mobile".equals(r.getColumnName()))
                .findFirst().orElse(null);
    }

    private WorkspaceIndexedSearchEngine.SearchHit hit(
            List<WorkspaceIndexedSearchEngine.SearchHit> hits, String type) {
        return hits.stream()
                .filter(h -> type.equals(h.getType()))
                .filter(h -> !"column".equals(type) || "buyer_mobile".equals(h.getColumn()))
                .findFirst().orElse(null);
    }
}
