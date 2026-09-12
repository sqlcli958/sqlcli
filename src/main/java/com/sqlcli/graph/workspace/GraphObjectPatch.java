package com.sqlcli.graph.workspace;

import java.util.List;

/**
 * 按 id 定位图谱里的**一个**对象，读出来或写回去。
 *
 * <h2>为什么需要它</h2>
 * 图谱审批是「先落 db、批准后才写图谱」：提交时变更不能落盘，得先把「改的是哪个对象、
 * 改成什么样」存进审批记录，批准时再写进当时最新的图谱。存整份工作区快照做不到这件事——
 * 批准时把快照整个盖回去，会把提交到批准之间别人做的改动一起抹掉。存单个对象的
 * before/after 才能重放到新版本上，顺带还能做冲突检测（当前值 != before 就说明被人动过）。
 *
 * <p>所有图谱写操作都通过 {@code MutationOutcome} 报告**一个** targetId，
 * 所以「一次变更 = 一个对象」不是这里的假设，是既有的约定；这个类只是把那个约定
 * 兑现成可读可写的定位逻辑。
 *
 * <h2>不支持的 id</h2>
 * targetId 是 manifest id 的那几条路——整份快照导入、建索引、跑校验——落在
 * {@link Kind#kindOf} 的 null 分支上，调用方据此把它们排除在审批之外。
 * 这不是遗漏：导入是成千上万个对象的批量替换（CLAUDE.md「要管导入，管的是导入这个动作本身」），
 * 建索引和校验是记账动作，不是要评审的内容变更。
 */
public final class GraphObjectPatch {

    private GraphObjectPatch() {
    }

    /** 支持定位的六类对象，按 id 前缀区分。 */
    public enum Kind {
        schema(SchemaWorkspaceNode.class),
        table(TableWorkspaceNode.class),
        column(ColumnWorkspaceNode.class),
        relation(RelationWorkspaceEdge.class),
        term(TermWorkspaceNode.class),
        lineage(LineageRecord.class),
        metric(MetricRecord.class);

        private final Class<?> type;

        Kind(Class<?> type) {
            this.type = type;
        }

        public Class<?> type() {
            return type;
        }
    }

    /** 认不出前缀就返回 null——调用方据此判断「这类变更不能走审批暂存」。 */
    public static Kind kindOf(String targetId) {
        if (targetId == null) return null;
        int colon = targetId.indexOf(':');
        if (colon <= 0) return null;
        try {
            return Kind.valueOf(targetId.substring(0, colon));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static boolean supports(String targetId) {
        return kindOf(targetId) != null;
    }

    /** 对象不存在返回 null（新增变更的 before 就是这个情形）。 */
    public static Object read(GraphWorkspace workspace, String targetId) {
        Kind kind = kindOf(targetId);
        if (kind == null || workspace == null) return null;
        return switch (kind) {
            case schema -> workspace.getSchemas().get(targetId);
            case table -> workspace.getTables().get(targetId);
            case column -> workspace.findColumnById(targetId);
            case term -> workspace.getTerms().get(targetId);
            case lineage -> workspace.getLineage().get(targetId);
            case metric -> workspace.getMetrics().get(targetId);
            case relation -> findRelation(workspace, targetId);
        };
    }

    /**
     * 把对象写回工作区。{@code value} 为 null 表示删除。
     *
     * @throws IllegalArgumentException 列所属的表不在图谱里（表被删了，这条变更没法落地）
     */
    public static void write(GraphWorkspace workspace, String targetId, Object value) {
        Kind kind = kindOf(targetId);
        if (kind == null) {
            throw new IllegalArgumentException("无法定位的图谱对象: " + targetId);
        }
        switch (kind) {
            case schema -> put(workspace.getSchemas(), targetId, (SchemaWorkspaceNode) value);
            case table -> put(workspace.getTables(), targetId, (TableWorkspaceNode) value);
            case term -> put(workspace.getTerms(), targetId, (TermWorkspaceNode) value);
            case lineage -> put(workspace.getLineage(), targetId, (LineageRecord) value);
            case metric -> put(workspace.getMetrics(), targetId, (MetricRecord) value);
            case column -> writeColumn(workspace, targetId, (ColumnWorkspaceNode) value);
            case relation -> writeRelation(workspace, targetId, (RelationWorkspaceEdge) value);
        }
    }

    private static <T> void put(java.util.Map<String, T> map, String id, T value) {
        if (value == null) {
            map.remove(id);
        } else {
            map.put(id, value);
        }
    }

    private static RelationWorkspaceEdge findRelation(GraphWorkspace workspace, String relationId) {
        for (RelationWorkspaceEdge edge : workspace.getRelations()) {
            if (relationId.equals(edge.getId())) return edge;
        }
        return null;
    }

    private static void writeRelation(GraphWorkspace workspace, String relationId,
            RelationWorkspaceEdge value) {
        List<RelationWorkspaceEdge> relations = workspace.getRelations();
        for (int i = 0; i < relations.size(); i++) {
            if (relationId.equals(relations.get(i).getId())) {
                if (value == null) relations.remove(i);
                else relations.set(i, value);
                return;
            }
        }
        if (value != null) relations.add(value);
    }

    /**
     * 列住在表里，写回要先找到那张表。解析规则跟
     * {@link GraphWorkspace#findColumnById} 是同一套（{@code column:alias:SCHEMA.TABLE.COL}），
     * 只是那边只读、这边要拿到容器。
     */
    private static void writeColumn(GraphWorkspace workspace, String columnId,
            ColumnWorkspaceNode value) {
        String[] parts = columnId.split(":", 3);
        if (parts.length < 3) {
            throw new IllegalArgumentException("列 id 格式不对: " + columnId);
        }
        String qualified = parts[2];
        int lastDot = qualified.lastIndexOf('.');
        if (lastDot < 0) {
            throw new IllegalArgumentException("列 id 格式不对: " + columnId);
        }
        String columnName = qualified.substring(lastDot + 1);
        TableWorkspaceNode table = workspace.getTableByQualifiedName(qualified.substring(0, lastDot));
        if (table == null) {
            throw new IllegalArgumentException("列所属的表已不在图谱中: " + columnId);
        }
        List<ColumnWorkspaceNode> columns = table.getColumns();
        for (int i = 0; i < columns.size(); i++) {
            if (columnName.equalsIgnoreCase(columns.get(i).getName())) {
                if (value == null) columns.remove(i);
                else columns.set(i, value);
                return;
            }
        }
        if (value != null) columns.add(value);
    }
}
