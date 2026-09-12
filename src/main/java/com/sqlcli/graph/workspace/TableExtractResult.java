package com.sqlcli.graph.workspace;

/**
 * 提取器提取单个表后的返回结果。
 * 包含从 JDBC 元数据创建的 table 节点，
 * 不包含任何已有 workspace 中的用户维护数据。
 */
public record TableExtractResult(TableWorkspaceNode table) {
}
