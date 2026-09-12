package com.sqlcli.graph.workspace;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class WorkspaceSearchEngine {
    private final GraphWorkspace workspace;

    public WorkspaceSearchEngine(GraphWorkspace workspace) {
        this.workspace = workspace;
    }

    public List<SearchResult> search(String keyword) {
        List<SearchResult> results = new ArrayList<>();
        for (TableWorkspaceNode table : workspace.getTables().values()) {
            addTableResult(results, keyword, table);
            String sourceAlias = table.getSourceAlias();
            for (ColumnWorkspaceNode column : table.getColumns()) {
                addColumnResult(results, keyword, table, column, sourceAlias);
            }
        }
        for (MetricRecord metric : workspace.getMetrics().values()) {
            addMetricResult(results, keyword, metric);
        }
        for (TermWorkspaceNode term : workspace.getTerms().values()) {
            addTermResult(results, keyword, term);
        }
        results.sort(Comparator.comparingDouble(SearchResult::getScore).reversed()
                .thenComparing(SearchResult::getId));
        return results;
    }

    private void addTableResult(List<SearchResult> results, String keyword, TableWorkspaceNode table) {
        SearchMatching.Scorer scorer = new SearchMatching.Scorer(keyword);
        scorer.field("name", table.getName(), SearchMatching.WEIGHT_NAME);
        scorer.field("businessName", table.getBusinessName(), SearchMatching.WEIGHT_BUSINESS_NAME);
        scorer.field("comment", table.getComment(), SearchMatching.WEIGHT_COMMENT);
        scorer.field("description", table.getDescription(), SearchMatching.WEIGHT_COMMENT);
        if (!scorer.matched()) {
            return;
        }
        SearchResult result = new SearchResult();
        result.setId(table.getId());
        result.setType("table");
        result.setSchema(table.getSchema());
        result.setTableName(table.getName());
        result.setComment(table.getComment());
        result.setDescription(table.getDescription());
        result.setBusinessName(table.getBusinessName());
        result.setCandidate(isCandidate(table));
        apply(result, scorer, SearchMatching.effectiveBoost(table.getBoost(), table.isSystem()));
        results.add(result);
    }

    private void addColumnResult(List<SearchResult> results, String keyword,
            TableWorkspaceNode table, ColumnWorkspaceNode column, String sourceAlias) {
        SearchMatching.Scorer scorer = new SearchMatching.Scorer(keyword);
        scorer.field("name", column.getName(), SearchMatching.WEIGHT_NAME);
        scorer.field("businessName", column.getBusinessName(), SearchMatching.WEIGHT_BUSINESS_NAME);
        scorer.field("comment", column.getComment(), SearchMatching.WEIGHT_COMMENT);
        scorer.field("description", column.getDescription(), SearchMatching.WEIGHT_COMMENT);
        scorer.field("semanticType", semanticName(column), SearchMatching.WEIGHT_SEMANTIC_TYPE);
        scorer.field("semanticType", semanticLabel(column), SearchMatching.WEIGHT_SEMANTIC_TYPE);
        if (!scorer.matched()) {
            return;
        }
        SearchResult result = new SearchResult();
        result.setId(column.computeId(sourceAlias, table.getSchema(), table.getName()));
        result.setType("column");
        result.setSchema(table.getSchema());
        result.setTableName(table.getName());
        result.setColumnName(column.getName());
        result.setComment(column.getComment());
        result.setDescription(column.getDescription());
        result.setBusinessName(column.getBusinessName());
        result.setSemanticType(semanticName(column));
        // 命中的是冗余快照列时把权威源一起带出来：Agent 选列的那一刻就该知道这不是
        // 权威值，不能等它已经生成完 SQL 才发现。
        result.setRedundantOf(column.getRedundantOf());
        // 内联列没有自己的 status，跟随所属表
        result.setCandidate(isCandidate(table));
        // 列自己的 boost；系统表标记跟随所属表
        apply(result, scorer, SearchMatching.effectiveBoost(column.getBoost(), table.isSystem()));
        results.add(result);
    }

    /** 检索要能靠 name / businessName / aliases 命中 metric，这是它有没有用的前提。 */
    private void addMetricResult(List<SearchResult> results, String keyword, MetricRecord metric) {
        SearchMatching.Scorer scorer = new SearchMatching.Scorer(keyword);
        scorer.field("name", metric.getName(), SearchMatching.WEIGHT_NAME);
        scorer.field("businessName", metric.getBusinessName(), SearchMatching.WEIGHT_BUSINESS_NAME);
        for (String alias : metric.getAliases() == null ? List.<String>of() : metric.getAliases()) {
            scorer.field("alias", alias, SearchMatching.WEIGHT_BUSINESS_NAME);
        }
        if (!scorer.matched()) {
            return;
        }
        SearchResult result = new SearchResult();
        result.setId(metric.getId());
        result.setType("metric");
        result.setBusinessName(metric.getBusinessName());
        result.setCandidate(metric.getStatus() == GraphStatus.candidate);
        apply(result, scorer, SearchMatching.effectiveBoost(null, false));
        results.add(result);
    }

    /**
     * 术语也要能被实时搜索命中。
     *
     * <p>这里原来是漏的：索引引擎一直在索引 term（`WorkspaceIndexer` 给它 8 分档，
     * 是最高权重），而这个实时引擎只扫表、列、metric。后果不是"排得靠后"，是**术语彻底消失**
     * ——索引一过期就自动降级到实时搜索，此时 Agent 搜任何业务词都看不见术语。
     * 真实图谱上撞见过：重建索引之前搜"巡检计划"，5 条术语一条都不出现。
     *
     * <p>`aliases` 必须参与匹配：术语的价值就是"用业务上的另一种说法也能找到"，
     * 只匹配 name 等于把同义词那一栏白填了。
     */
    private void addTermResult(List<SearchResult> results, String keyword, TermWorkspaceNode term) {
        // 负向词命中：这条术语这次查询整个不参与匹配——挡跨域撞词
        if (SearchMatching.blockedByNegativeAlias(term.getNegativeAliases(), keyword)) {
            return;
        }
        SearchMatching.Scorer scorer = new SearchMatching.Scorer(keyword);
        scorer.field("name", term.getName(), SearchMatching.WEIGHT_NAME);
        scorer.field("displayName", term.getDisplayName(), SearchMatching.WEIGHT_BUSINESS_NAME);
        scorer.field("description", term.getDescription(), SearchMatching.WEIGHT_COMMENT);
        for (String alias : term.getAliases() == null ? List.<String>of() : term.getAliases()) {
            scorer.field("alias", alias, SearchMatching.WEIGHT_BUSINESS_NAME);
        }
        if (!scorer.matched()) {
            return;
        }
        SearchResult result = new SearchResult();
        result.setId(term.getId());
        result.setType("term");
        // getTitle() 对没有 schema.table 的类型回落到 businessName，术语的展示名放这里
        result.setBusinessName(term.getDisplayName() != null && !term.getDisplayName().isBlank()
                ? term.getDisplayName() : term.getName());
        result.setDescription(term.getDescription());
        result.setCandidate(term.getStatus() == GraphStatus.candidate);
        apply(result, scorer, SearchMatching.effectiveBoost(null, false));
        results.add(result);
    }

    private void apply(SearchResult result, SearchMatching.Scorer scorer, double boost) {
        result.setScore(scorer.score() * boost);
        result.setMatchedField(scorer.matchedField());
        result.setMatchValue(scorer.matchedText());
    }

    private static boolean isCandidate(TableWorkspaceNode table) {
        return table.getStatus() == GraphStatus.candidate;
    }

    @Data
    public static class SearchResult {
        private String id;
        private String type;
        private String schema;
        private String tableName;
        private String columnName;
        /** 命中的字段原文；与 {@link #getMatchedText()} 同值，保留旧字段名不破坏既有契约。 */
        private String matchValue;
        private String matchedField;
        /** 数据库注释镜像，只由导入写。 */
        private String comment;
        /** 人/Agent 写的业务描述，与 comment 来源不同，检索结果里分开展示。 */
        private String description;
        private String businessName;
        private String semanticType;
        /** 权威来源列 id，非冗余列为 null；见 {@link ColumnWorkspaceNode#getRedundantOf()}。 */
        private String redundantOf;
        private boolean candidate;
        private double score;

        /** 与索引引擎对齐的字段名。 */
        public String getMatchedText() {
            return matchValue;
        }

        @JsonIgnore
        public String getTitle() {
            if ("column".equals(type)) {
                return schema + "." + tableName + "." + columnName;
            }
            if ("table".equals(type)) {
                return schema + "." + tableName;
            }
            // metric（以及其他没有 schema/table 的类型）没有 schema.table 结构，
            // 用 businessName 兜底展示，都没有才退化到 id。
            return businessName != null ? businessName : id;
        }
    }

    private static String semanticName(ColumnWorkspaceNode column) {
        return column.getSemanticType() == null ? null : column.getSemanticType().name();
    }

    /** 语义类型参与搜索时也用中文标签，搜"手机号"才能命中标了 phone 的字段。 */
    private static String semanticLabel(ColumnWorkspaceNode column) {
        return column.getSemanticType() == null ? null : column.getSemanticType().getLabel();
    }
}
