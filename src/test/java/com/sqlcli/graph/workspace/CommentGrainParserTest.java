package com.sqlcli.graph.workspace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CommentGrainParserTest {

    @Test
    void extractsGrainWhenMarkerPrefixIsPresent() {
        CommentGrainParser.Parsed parsed = CommentGrainParser.parse("订单行 | grain=一行一个订单商品");
        assertEquals("订单行", parsed.comment());
        assertEquals("一行一个订单商品", parsed.grain());
    }

    @Test
    void neverGuessesByPositionWithoutTheMarkerPrefix() {
        // "已废弃" 之前没有 grain= 前缀——不能靠位置猜，整条原样当 comment
        CommentGrainParser.Parsed parsed = CommentGrainParser.parse("订单行 | 已废弃");
        assertEquals("订单行 | 已废弃", parsed.comment());
        assertNull(parsed.grain());
    }

    @Test
    void onlyRecognizesPipeAsSeparatorNotCommaOrDun() {
        CommentGrainParser.Parsed parsed = CommentGrainParser.parse("订单号,冗余存储、请勿手改");
        assertEquals("订单号,冗余存储、请勿手改", parsed.comment());
        assertNull(parsed.grain());
    }

    @Test
    void nullAndPlainCommentsPassThroughUnchanged() {
        assertNull(CommentGrainParser.parse(null).comment());
        CommentGrainParser.Parsed plain = CommentGrainParser.parse("普通注释");
        assertEquals("普通注释", plain.comment());
        assertNull(plain.grain());
    }
}
