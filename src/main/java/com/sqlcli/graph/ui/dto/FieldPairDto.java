package com.sqlcli.graph.ui.dto;

import lombok.Data;

@Data
public class FieldPairDto {
    private String from;
    private String to;

    public FieldPairDto() {}

    public FieldPairDto(String from, String to) {
        this.from = from;
        this.to = to;
    }
}
