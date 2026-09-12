package com.sqlcli.graph.ui.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkspaceSummaryDto {
    private String alias;
    private long revision;
    private int modelVersion;
    private int storageVersion;
    private StatsDto stats;

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StatsDto {
        private int schemas;
        private int tables;
        private int columns;
        private int relations;
        private int validationIssues;
    }
}
