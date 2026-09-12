package com.sqlcli.graph.workspace;

public enum GraphStatus {
    discovered,
    partial,
    /** 由 agent/extractor 写入、等待人工发布或拒绝的候选对象。 */
    candidate,
    verified,
    deprecated,
    conflict,
    ignored;

    /**
     * 新建对象的初始状态：agent/extractor 写入的是候选，需人工发布；human 写入沿用 partial。
     */
    public static GraphStatus forActor(GraphActor actor) {
        return actor == GraphActor.agent || actor == GraphActor.extractor ? candidate : partial;
    }
}
