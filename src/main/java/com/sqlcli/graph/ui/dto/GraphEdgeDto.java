package com.sqlcli.graph.ui.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GraphEdgeDto {
    private String id;
    private String source;
    private String target;
    private String type;
    private Double confidence;
    private Boolean verified;
    private List<FieldPairDto> fieldPairs;
    private List<String> relationIds;
}
