package com.sqlcli.graph.ui.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 图谱自身的完整性看板（GET /api/workspace/completeness）：数的是图谱这份元数据本身
 * 齐不齐、待办多不多、关系健不健康，不碰用户的业务数据。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkspaceCompletenessDto {
    private TableCoverage tables;
    private ColumnCoverage columns;
    private Backlog backlog;
    private RelationHealth relations;

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TableCoverage {
        private int total;
        private int withComment;
        private int withBusinessName;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ColumnCoverage {
        private int total;
        private int withComment;
        private int withBusinessName;
        private int withSemanticType;
        private int withValueHints;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Backlog {
        /** 候选待评审：agent/extractor 写入、还没人发布的表/关系/术语。 */
        private int candidateTables;
        private int candidateRelations;
        private int candidateTerms;
        /** 已被人明确拒绝（status=ignored），保留审计不再参与展示/检索。 */
        private int ignoredTables;
        private int ignoredRelations;
        private int ignoredTerms;
        /** 未处理校验问题（status=open）按 severity 分布，key 是 ValidationSeverity 原值。 */
        private Map<String, Integer> openIssuesBySeverity = new LinkedHashMap<>();
        /** 已忽略的校验问题数（status=ignored），区别于上面 open 的分布。 */
        private int ignoredIssues;
        /**
         * 有语义内容但没人确认过的字段数（{@code ColumnWorkspaceNode.hasUnconfirmedSemantics}）。
         *
         * <p>没跟上面几个 candidate 数合并：字段级没有候选态，值是立刻生效的，
         * 这个数说的是"生效了但没人认过"，跟"写进去了还不算数"不是一回事。
         */
        private int unverifiedColumnSemantics;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RelationHealth {
        private int totalTables;
        /** 一条结构性关系（foreign_key / join_observed）都没有的表。 */
        private int isolatedTables;
        /** 有 foreign_key 但一条 join_observed 都没有的表——业务关联没人补过。 */
        private int fkOnlyTables;
    }
}
