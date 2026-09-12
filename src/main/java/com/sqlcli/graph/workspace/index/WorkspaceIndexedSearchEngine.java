package com.sqlcli.graph.workspace.index;

import com.sqlcli.graph.workspace.SearchMatching;
import com.sqlcli.graph.workspace.SemanticType;

import java.util.ArrayList;
import java.util.List;

public class WorkspaceIndexedSearchEngine {

    public static class SearchHit {
        private String id;
        private String type;
        private String schema;
        private String table;
        private String column;
        private String name;
        /** 数据库注释镜像，只由导入写。 */
        private String comment;
        /** 人/Agent 写的业务描述，与 comment 分字段展示。 */
        private String description;
        private String businessName;
        private String semanticType;
        /** 权威来源列 id，非冗余列为 null。 */
        private String redundantOf;
        private boolean candidate;
        private double score;
        private String matchedField;
        private String matchedText;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getSchema() { return schema; }
        public void setSchema(String schema) { this.schema = schema; }

        public String getTable() { return table; }
        public void setTable(String table) { this.table = table; }

        public String getColumn() { return column; }
        public void setColumn(String column) { this.column = column; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getComment() { return comment; }
        public void setComment(String comment) { this.comment = comment; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getBusinessName() { return businessName; }
        public void setBusinessName(String businessName) { this.businessName = businessName; }

        public String getSemanticType() { return semanticType; }
        public void setSemanticType(String semanticType) { this.semanticType = semanticType; }

        public String getRedundantOf() { return redundantOf; }
        public void setRedundantOf(String redundantOf) { this.redundantOf = redundantOf; }

        /** 候选对象（agent 写入、等人发布），搜索默认包含，靠这个标记区分。 */
        public boolean isCandidate() { return candidate; }
        public void setCandidate(boolean candidate) { this.candidate = candidate; }

        public double getScore() { return score; }
        public void setScore(double score) { this.score = score; }

        public String getMatchedField() { return matchedField; }
        public void setMatchedField(String matchedField) { this.matchedField = matchedField; }

        public String getMatchedText() { return matchedText; }
        public void setMatchedText(String matchedText) { this.matchedText = matchedText; }

        public String getTitle() {
            if ("table".equals(type)) {
                return schema + "." + table;
            }
            if ("column".equals(type)) {
                return schema + "." + table + "." + column;
            }
            return name;
        }
    }

    private final List<WorkspaceIndexDocument> documents;

    public WorkspaceIndexedSearchEngine(WorkspaceIndexSnapshot snapshot) {
        this.documents = snapshot.getDocuments();
    }

    public List<SearchHit> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<SearchHit> hits = new ArrayList<>();
        for (WorkspaceIndexDocument doc : documents) {
            // 负向词命中：这份文档（通常是跨域撞词的术语）这次查询整个不参与匹配
            if (SearchMatching.blockedByNegativeAlias(doc.getNegativeAliases(), query)) {
                continue;
            }
            // 字段按重要性依次喂入，同分先到先得
            SearchMatching.Scorer scorer = new SearchMatching.Scorer(query);
            scorer.field("name", doc.getName(), SearchMatching.WEIGHT_NAME);
            scorer.field("qualifiedName", doc.getQualifiedName(), 45);
            scorer.field("displayName", doc.getDisplayName(), 35);
            scorer.field("businessName", doc.getBusinessName(), SearchMatching.WEIGHT_BUSINESS_NAME);
            scorer.field("comment", doc.getComment(), SearchMatching.WEIGHT_COMMENT);
            scorer.field("description", doc.getDescription(), SearchMatching.WEIGHT_COMMENT);
            scorer.field("semanticType", doc.getSemanticType(), SearchMatching.WEIGHT_SEMANTIC_TYPE);
            scorer.field("semanticType", semanticLabel(doc.getSemanticType()), SearchMatching.WEIGHT_SEMANTIC_TYPE);
            for (String alias : values(doc.getAliases())) {
                scorer.field("alias", alias, 35);
            }
            for (String tag : values(doc.getTags())) {
                scorer.field("tag", tag, 20);
            }
            if (!scorer.matched()) {
                continue;
            }
            // boost / 系统表降权只影响排序得分，命中判断与 JSON 字段不变
            double score = (scorer.score() + typeBonus(doc.getType()))
                    * SearchMatching.effectiveBoost(doc.getBoost(), doc.isSystem());
            SearchHit hit = new SearchHit();
            hit.setId(doc.getId());
            hit.setType(doc.getType());
            hit.setSchema(doc.getSchema());
            hit.setTable(doc.getTable());
            hit.setColumn(doc.getColumn());
            hit.setName(doc.getName());
            hit.setComment(doc.getComment());
            hit.setDescription(doc.getDescription());
            hit.setBusinessName(doc.getBusinessName());
            hit.setSemanticType(doc.getSemanticType());
            hit.setRedundantOf(doc.getRedundantOf());
            hit.setCandidate(doc.isCandidate());
            hit.setScore(score);
            hit.setMatchedField(scorer.matchedField());
            hit.setMatchedText(scorer.matchedText());
            hits.add(hit);
        }
        hits.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        return hits;
    }

    private static double typeBonus(String type) {
        if ("table".equals(type)) return 10;
        if ("term".equals(type)) return 8;
        if ("column".equals(type)) return 5;
        return 0;
    }

    /** 索引里存的是枚举名（contract），中文标签在搜索时按需还原，不占索引体积。 */
    private static String semanticLabel(String semanticType) {
        if (semanticType == null || semanticType.isBlank()) {
            return null;
        }
        try {
            return SemanticType.valueOf(semanticType).getLabel();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private List<String> values(List<String> values) {
        return values == null ? List.of() : values;
    }
}
