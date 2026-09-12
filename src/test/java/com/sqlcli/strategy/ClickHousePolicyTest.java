package com.sqlcli.strategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClickHousePolicyTest {

    private final SqlExecutionPolicy policy = SqlExecutionPolicy.CLICKHOUSE_POLICY;
    private final DatabaseCapabilities capabilities = DatabaseCapabilities.CLICKHOUSE_DEFAULTS();

    @Nested
    @DisplayName("ClickHouse SqlExecutionPolicy")
    class ClickHousePolicyTests {

        @Test
        void testClickHousePolicy_RejectsStandardUpdate() {
            assertFalse(policy.isAllowStandardUpdate(),
                    "ClickHouse policy should reject standard UPDATE");
        }

        @Test
        void testClickHousePolicy_RejectsStandardDelete() {
            assertFalse(policy.isAllowStandardDelete(),
                    "ClickHouse policy should reject standard DELETE");
        }

        @Test
        void testClickHousePolicy_AllowsAlterMutation() {
            assertTrue(policy.isAllowAlterMutation(),
                    "ClickHouse policy should allow ALTER TABLE ... UPDATE/DELETE mutations");
        }

        @Test
        void testClickHousePolicy_NoRecoverySql() {
            assertFalse(policy.isGenerateRecoverySql(),
                    "ClickHouse policy should not generate recovery SQL");
        }

        @Test
        void testClickHousePolicy_RejectsMultipleStatements() {
            assertFalse(policy.isAllowMultipleStatements(),
                    "ClickHouse policy should reject multiple statements");
        }

        @Test
        void testClickHousePolicy_AllowsInsert() {
            assertTrue(policy.isAllowInsert(),
                    "ClickHouse policy should allow INSERT");
        }

        @Test
        void testClickHousePolicy_AllowsDdl() {
            assertTrue(policy.isAllowDdl(),
                    "ClickHouse policy should allow DDL");
        }

        @Test
        void testClickHousePolicy_AllowsShow() {
            assertTrue(policy.isAllowShow(),
                    "ClickHouse policy should allow SHOW");
        }

        @Test
        void testClickHousePolicy_ReadOnlyGuard() {
            assertTrue(policy.isRequireReadonlyGuard(),
                    "ClickHouse policy should require readonly guard");
        }

        @Test
        void testClickHousePolicy_UnsupportedUpdateMessage() {
            assertNotNull(policy.getUnsupportedUpdateMessage(),
                    "Unsupported UPDATE message should not be null");
            assertTrue(policy.getUnsupportedUpdateMessage().contains("ClickHouse"),
                    "Unsupported UPDATE message should mention ClickHouse");
        }

        @Test
        void testClickHousePolicy_UnsupportedDeleteMessage() {
            assertNotNull(policy.getUnsupportedDeleteMessage(),
                    "Unsupported DELETE message should not be null");
            assertTrue(policy.getUnsupportedDeleteMessage().contains("ClickHouse"),
                    "Unsupported DELETE message should mention ClickHouse");
        }
    }

    @Nested
    @DisplayName("ClickHouse DatabaseCapabilities")
    class ClickHouseCapabilitiesTests {

        @Test
        void testClickHouseCapabilities_NoTransactions() {
            assertFalse(capabilities.isSupportsTransactions(),
                    "ClickHouse does not support transactions");
        }

        @Test
        void testClickHouseCapabilities_NoRecoverySql() {
            assertFalse(capabilities.isSupportsRecoverySql(),
                    "ClickHouse does not support recovery SQL");
        }

        @Test
        void testClickHouseCapabilities_SchemaAsDatabase() {
            assertTrue(capabilities.isUsesSchemaAsDatabase(),
                    "ClickHouse uses schema as database");
        }

        @Test
        void testClickHouseCapabilities_DefaultPort8123() {
            assertEquals(8123, capabilities.getDefaultPort(),
                    "ClickHouse default port should be 8123");
        }

        @Test
        void testClickHouseCapabilities_DefaultSecurePort8443() {
            assertEquals(8443, capabilities.getDefaultSecurePort(),
                    "ClickHouse default secure port should be 8443");
        }
    }
}
