package com.sqlcli.connection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TableRowCounterTest {

    @Test
    void quotesPerDialect() {
        assertEquals("`app`.`orders`", TableRowCounter.qualifiedName("mysql", "app", "orders"));
        assertEquals("\"APP\".\"ORDERS\"", TableRowCounter.qualifiedName("oracle", "APP", "ORDERS"));
    }

    @Test
    void rejectsIdentifiersThatWouldEscapeIntoSql() {
        for (String bad : new String[] {"app`; DROP TABLE x --", "app orders", "app\"", "", null}) {
            assertThrows(IllegalArgumentException.class,
                    () -> TableRowCounter.qualifiedName("mysql", "app", bad), String.valueOf(bad));
            assertThrows(IllegalArgumentException.class,
                    () -> TableRowCounter.qualifiedName("mysql", bad, "orders"), String.valueOf(bad));
        }
    }
}
