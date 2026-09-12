package com.sqlcli.graph.ui;

import lombok.Data;

@Data
public class GraphUiOptions {
    private int port = 9999;
    private String host = "127.0.0.1";
    private boolean noOpen;
    private String alias;
}
