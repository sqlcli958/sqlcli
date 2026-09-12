package com.sqlcli.strategy;

import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.graph.workspace.WorkspaceMetadataExtractor;
import com.sqlcli.graph.workspace.WorkspaceMetadataProvider;

import java.sql.Connection;
import java.sql.SQLException;

public class WorkspaceMetadataProviderFactory {

    private WorkspaceMetadataProviderFactory() {
    }

    public static WorkspaceMetadataProvider create(Connection conn, DatabaseConfig config) throws SQLException {
        DatabaseStrategy strategy = DatabaseStrategies.resolve(config);
        try {
            return strategy.createMetadataProvider(conn, config);
        } catch (UnsupportedOperationException e) {
            return new WorkspaceMetadataExtractor(conn, config.getAliasName());
        }
    }
}
