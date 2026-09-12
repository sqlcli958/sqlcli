package com.sqlcli.graph.workspace;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.EqualsAndHashCode;
import lombok.Data;

@Data
@EqualsAndHashCode(callSuper = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DataSourceNode extends BaseGraphObject {
    private String alias;
    private String displayName;
    private String dbType;
    private String productName = "unknown";
    private String productVersion = "unknown";
    private Boolean indexMetadataSupported;

    public static DataSourceNode create(String alias, String dbType, GraphActor actor) {
        DataSourceNode node = new DataSourceNode();
        node.init(GraphObjectKind.datasource, "datasource:" + alias, actor);
        node.setAlias(alias);
        node.setDisplayName(alias);
        node.setDbType(dbType);
        node.setStatus(GraphStatus.verified);
        node.setConfidence(1.0);
        node.setVerified(true);
        return node;
    }
}
