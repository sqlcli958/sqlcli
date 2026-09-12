package com.sqlcli.graph.workspace.index;

/**
 * 索引状态枚举。
 */
public enum IndexStatus {
    /** 索引已就绪：sourceRevision == currentRevision && documents > 0 */
    ready,
    /** 索引已过期：sourceRevision < currentRevision */
    stale,
    /** 索引文件不存在 */
    missing,
    /** 索引正在构建中 */
    building,
    /** 索引构建失败：manifest 存在但 documentCount == 0 */
    failed
}
