package com.sqlcli.graph.ui.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GraphNodeDto {
    private String id;
    private String kind;
    private String label;
    private String schema;
    private String description;
    private int relationCount;
    private int columnCount;
    private String validationSeverity;
}
