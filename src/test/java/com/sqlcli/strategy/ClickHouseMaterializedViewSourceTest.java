package com.sqlcli.strategy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * CH-P2-008：验证 {@link ClickHouseWorkspaceMetadataProvider#parseMaterializedViewSource}
 * 能从真实形态的 ClickHouse {@code create_table_query} 文本里解析出源表。
 * 不连真实数据库，纯字符串解析。
 */
class ClickHouseMaterializedViewSourceTest {

    @Test
    void parsesSourceTableFromCreateTableQueryWithToClause() {
        String createTableQuery = "CREATE MATERIALIZED VIEW db.x TO db.y "
                + "AS SELECT id, count() AS cnt FROM db.src GROUP BY id";

        assertEquals("db.src", ClickHouseWorkspaceMetadataProvider.parseMaterializedViewSource(createTableQuery));
    }

    @Test
    void parsesUnqualifiedSourceTable() {
        String createTableQuery = "CREATE MATERIALIZED VIEW x AS SELECT * FROM src";

        assertEquals("src", ClickHouseWorkspaceMetadataProvider.parseMaterializedViewSource(createTableQuery));
    }

    @Test
    void parsesBacktickQuotedIdentifiers() {
        String createTableQuery = "CREATE MATERIALIZED VIEW `db`.`x` TO `db`.`y` "
                + "AS SELECT * FROM `db`.`src` WHERE 1";

        assertEquals("db.src", ClickHouseWorkspaceMetadataProvider.parseMaterializedViewSource(createTableQuery));
    }

    @Test
    void returnsNullWhenNoFromClause() {
        assertNull(ClickHouseWorkspaceMetadataProvider.parseMaterializedViewSource("CREATE MATERIALIZED VIEW x AS SELECT 1"));
    }

    @Test
    void returnsNullForBlankInput() {
        assertNull(ClickHouseWorkspaceMetadataProvider.parseMaterializedViewSource(null));
        assertNull(ClickHouseWorkspaceMetadataProvider.parseMaterializedViewSource("  "));
    }
}
