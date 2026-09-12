package com.sqlcli.graph.ui.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GraphViewDto {
    private long revision;
    private boolean truncated;
    private Stats stats;
    private List<GraphNodeDto> nodes;
    private List<GraphEdgeDto> edges;

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Stats {
        private int totalNodes;
        private int totalEdges;
        private int returnedNodes;
        private int returnedEdges;
    }
}
