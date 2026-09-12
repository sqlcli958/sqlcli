package com.sqlcli.graph.ui.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 单字段血缘的多跳视图（GET /api/lineage）：以某一个字段为起点，BFS 展开到 {@code depth} 跳。
 * 每个节点带 signed {@code level}（0=起点，负数=上游第几跳，正数=下游第几跳），前端按
 * level 分列画图，不需要自己算布局。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LineageGraphDto {
    /** 起点，schema.table.column 形式，与 CLI `schema lineage` 的 ref 风格一致。 */
    private String center;
    private int depth;
    /** 命中节点数超过展示上限时为 true，超出部分被丢弃（前端提示收窄深度）。 */
    private boolean truncated;
    private List<Node> nodes = new ArrayList<>();
    private List<Edge> edges = new ArrayList<>();
    /**
     * 写进起点这一列的记录，一条一块。图上的边是按源列展开的（3 源记录 = 3 条边），
     * 详情和删除的单位是记录，所以单独给，前端不用再从边里聚回来。
     */
    private List<Record> records = new ArrayList<>();
    /** 全局图（GET /api/lineage/graph）才有：schema.table → 表的总列数，画「其余 N 列」用。 */
    private java.util.Map<String, Integer> tableColumns = new java.util.LinkedHashMap<>();

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Node {
        /** schema.table.column，同时用作前端 React key 和边的 from/to。 */
        private String id;
        private String schema;
        private String table;
        private String column;
        private int level;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Record {
        private String id;
        private String lineageKind;
        /** 源列，schema.table.column */
        private List<String> sources = new ArrayList<>();
        private String expression;
        private String through;
        private String status;
        private Double confidence;
        private String updatedAt;
        /** 全局图才有：这条记录写的列，schema.table.column */
        private String target;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Edge {
        private String from;
        private String to;
        private String expression;
        private String through;
        /** 血缘记录 id：一条记录展开成多条边时相同，前端按它把「本列的记录」聚回来给删除用。 */
        private String id;
        /** identity / transformation / aggregation / rule；rule 画虚线——它只影响写不写，不提供值。 */
        private String lineageKind;
    }
}
