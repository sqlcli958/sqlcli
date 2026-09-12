package com.sqlcli.strategy;

import lombok.Getter;

/**
 * 表信息
 */
@Getter
public class TableInfo {
    private final String name;
    private final String schema;
    private final String type;      // TABLE, VIEW, etc.
    private final String remarks;   // 表描述/备注

    public TableInfo(String name, String schema, String type, String remarks) {
        this.name = name;
        this.schema = schema;
        this.type = type;
        this.remarks = remarks;
    }

    @Override
    public String toString() {
        return String.format("%s.%s (%s): %s",
                schema != null ? schema : "",
                name,
                type != null ? type : "TABLE",
                remarks != null ? remarks : "");
    }
}
