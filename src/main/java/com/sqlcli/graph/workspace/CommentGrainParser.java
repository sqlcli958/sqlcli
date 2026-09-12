package com.sqlcli.graph.workspace;

/**
 * 从 DDL {@code COMMENT} 里拆出表粒度（{@link TableWorkspaceNode#getGrain()}）。
 *
 * <p>约定格式：{@code COMMENT '订单行 | grain=一行一个订单商品'}——{@code |} 之前留作
 * comment，之后按 {@code grain=} 声明粒度。导入（{@link WorkspaceMetadataExtractor}）
 * 和 design review（{@link com.sqlcli.graph.review.CandidateDdlProjector}）
 * 共用这一份解析，两边各写一套的结果是导入认得的格式 design review 不认。
 *
 * <p>照 {@link com.sqlcli.profile.ValueDomainCrosscheck#parseCommentEnum} 那条铁律：
 * <b>宁可解析不出来，也不要解析错</b>。三条闸门：
 * <ul>
 *   <li>{@code grain=} 前缀必须原样出现，不靠位置猜——不然「已废弃」会被当成 grain</li>
 *   <li>认不出就整条原样当 comment，不报错、不告警——十年遗留库的注释是最脏的自由文本，
 *       为它告警等于每次导入刷几百条噪音</li>
 *   <li>分隔符只认 {@code |}，不认逗号和顿号——那两个在中文注释正文里太常见</li>
 * </ul>
 */
public final class CommentGrainParser {

    private static final String GRAIN_MARKER = "grain=";

    private CommentGrainParser() {
    }

    /** 拆分结果：{@code comment} 是 {@code |} 之前的部分（认不出 grain 时是整条原文）。 */
    public record Parsed(String comment, String grain) {
    }

    public static Parsed parse(String rawComment) {
        if (rawComment == null) {
            return new Parsed(null, null);
        }
        int barAt = rawComment.indexOf('|');
        if (barAt < 0) {
            return new Parsed(rawComment, null);
        }
        String after = rawComment.substring(barAt + 1).trim();
        if (!after.startsWith(GRAIN_MARKER)) {
            // 认不出来：不做任何拆分，整条原样当 comment
            return new Parsed(rawComment, null);
        }
        String grain = after.substring(GRAIN_MARKER.length()).trim();
        if (grain.isEmpty()) {
            return new Parsed(rawComment, null);
        }
        String comment = rawComment.substring(0, barAt).trim();
        return new Parsed(comment.isEmpty() ? null : comment, grain);
    }
}
