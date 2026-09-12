package com.sqlcli.graph.workspace;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ColumnDataType {
    private String raw;
    private String normalized;
    private Integer length;
    private Integer precision;
    private Integer scale;
}
