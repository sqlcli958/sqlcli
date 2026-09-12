package com.sqlcli.graph.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OriginCheckTest {

    private static final String SERVER = "http://localhost:8080";

    /** 这个用例就是那次 bug 本身：服务器打印 127.0.0.1 的地址，写操作却全被 403。 */
    @Test
    void loopbackSpellingsAreTheSameServer() {
        assertTrue(OriginCheck.allows(SERVER, "http://127.0.0.1:8080"));
        assertTrue(OriginCheck.allows("http://127.0.0.1:8080", "http://localhost:8080"));
        assertTrue(OriginCheck.allows(SERVER, "http://[::1]:8080"));
        assertTrue(OriginCheck.allows(SERVER, "http://LOCALHOST:8080"));
    }

    @Test
    void nonBrowserClientsHaveNoOrigin() {
        assertTrue(OriginCheck.allows(SERVER, null));
        assertTrue(OriginCheck.allows(SERVER, ""));
    }

    @Test
    void otherHostsPortsAndSchemesAreRejected() {
        assertFalse(OriginCheck.allows(SERVER, "http://evil.example:8080"));
        assertFalse(OriginCheck.allows(SERVER, "http://localhost:9999"));
        assertFalse(OriginCheck.allows(SERVER, "https://localhost:8080"));
        // 回环等价不能顺手放行任意 IP
        assertFalse(OriginCheck.allows(SERVER, "http://127.0.0.2:8080"));
    }

    @Test
    void malformedOriginsAreRejected() {
        assertFalse(OriginCheck.allows(SERVER, "http://user@localhost:8080"));
        assertFalse(OriginCheck.allows(SERVER, "http://localhost:8080/path"));
        assertFalse(OriginCheck.allows(SERVER, "not a uri"));
        assertFalse(OriginCheck.allows(null, "http://localhost:8080"));
    }
}
