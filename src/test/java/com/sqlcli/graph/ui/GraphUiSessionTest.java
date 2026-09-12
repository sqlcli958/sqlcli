package com.sqlcli.graph.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** validateWriteRequest 的 token 比较从 String.equals 换成了 MessageDigest.isEqual，行为必须不变。 */
class GraphUiSessionTest {

    @Test
    void acceptsCorrectTokenWithoutOrigin() {
        GraphUiSession session = new GraphUiSession("test", false);
        assertTrue(session.validateWriteRequest(session.getSessionToken(), null));
    }

    @Test
    void rejectsWrongOrNullToken() {
        GraphUiSession session = new GraphUiSession("test", false);
        assertFalse(session.validateWriteRequest("not-the-token", null));
        assertFalse(session.validateWriteRequest(null, null));
        // 长度不同的 token 也要走到「拒绝」而不是抛异常
        assertFalse(session.validateWriteRequest(session.getSessionToken() + "x", null));
    }

    @Test
    void readOnlySessionStillAllowsGraphWrites() {
        // readOnly = 数据库只读，图谱工作区是本地元数据，编辑不受它限制（语义见 GraphUiSession 类注释）。
        GraphUiSession session = new GraphUiSession("test", true);
        assertTrue(session.validateWriteRequest(session.getSessionToken(), null));
    }
}
