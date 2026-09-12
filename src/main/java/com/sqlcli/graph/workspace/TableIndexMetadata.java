package com.sqlcli.graph.workspace;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TableIndexMetadata {
    private String name;
    private boolean unique;
    private List<String> columns = new ArrayList<>();
}
