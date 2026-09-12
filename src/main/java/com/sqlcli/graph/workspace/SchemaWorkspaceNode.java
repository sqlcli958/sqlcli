package com.sqlcli.graph.workspace;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.EqualsAndHashCode;
import lombok.Data;

@Data
@EqualsAndHashCode(callSuper = false)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SchemaWorkspaceNode extends BaseGraphObject {
    private String sourceAlias;
    private String name;
    private String displayName;
    private String description;
    private boolean system;

    public static SchemaWorkspaceNode create(String sourceAlias, String schemaName, GraphActor actor) {
        SchemaWorkspaceNode node = new SchemaWorkspaceNode();
        node.init(GraphObjectKind.schema, GraphIds.schemaId(sourceAlias, schemaName), actor);
        node.setSourceAlias(sourceAlias);
        node.setName(schemaName);
        node.setDisplayName(schemaName);
        node.setStatus(GraphStatus.verified);
        node.setConfidence(1.0);
        node.setVerified(true);
        return node;
    }
}
