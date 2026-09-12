package com.sqlcli.graph.workspace;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 关系遍历的共享邻接层：把 column 级的 relations 过滤 ignored 后按表 id 建索引，
 * 供两种独立的遍历方式各自在上面走——两表最短路径（{@code WorkspacePathFinder}）
 * 和单表限深多跳（{@code SchemaActionCommand#expandRelations}）。
 *
 * <p>两处过去分别实现了一遍"按边遍历"，`ignored` 过滤两处各改一遍正是教训所在——
 * 以后再有一条"哪些边不该参与遍历"的规则，改这一个 {@link #build} 就够了，
 * 不用再满仓库找第二个实现。
 *
 * <p>这层只回答"从这个表出发，经哪些边能碰到哪些表"，不管消费方要不要构造反向边、
 * 要不要判可选性、要不要限深——那些是两种遍历各自的契约，写进这里就是文档里
 * 说的"强行合并成一个方法"。
 */
public final class RelationAdjacency {

    /**
     * 一条邻接记录。{@code edge} 是原始存储的关系边（未做任何改写），
     * {@code neighborTableId} 是从所在表出发经这条边到达的另一张表，
     * {@code reversed} 标记这条记录是不是站在 {@code edge.getTo()} 一侧看到的——
     * 需要反向边（比如最短路径要把 optionality 两端互换）的消费方靠这个字段自己判断，
     * 这层本身不做任何字段翻转。
     */
    public static final class Entry {
        private final RelationWorkspaceEdge edge;
        private final String neighborTableId;
        private final boolean reversed;

        private Entry(RelationWorkspaceEdge edge, String neighborTableId, boolean reversed) {
            this.edge = edge;
            this.neighborTableId = neighborTableId;
            this.reversed = reversed;
        }

        public RelationWorkspaceEdge getEdge() {
            return edge;
        }

        public String getNeighborTableId() {
            return neighborTableId;
        }

        public boolean isReversed() {
            return reversed;
        }
    }

    private final Map<String, List<Entry>> byTable;

    private RelationAdjacency(Map<String, List<Entry>> byTable) {
        this.byTable = byTable;
    }

    /**
     * 从 workspace 的全部 relations 建索引。ignored 边在这里被过滤掉——判据见
     * WorkspaceMutationService#isIgnored 类注释的三分表：人已经明确拒绝过的边，
     * 不能反过来参与路径选择或 Agent 消费的展开结果。
     *
     * <p>不做端点存在性校验：WorkspaceValidator 已经把非 ignored 边的悬空端点
     * 当结构错误报出来（ignored 边本身被跳过，见其 javadoc），这里没必要再查一遍
     * workspace 有没有这个列。
     */
    public static RelationAdjacency build(GraphWorkspace workspace) {
        Map<String, List<Entry>> byTable = new HashMap<>();
        for (RelationWorkspaceEdge edge : workspace.getRelations()) {
            if (edge.getStatus() == GraphStatus.ignored) {
                continue;
            }
            String fromTableId = tableIdForNode(edge.getFrom());
            String toTableId = tableIdForNode(edge.getTo());
            if (fromTableId != null) {
                byTable.computeIfAbsent(fromTableId, k -> new ArrayList<>())
                        .add(new Entry(edge, toTableId, false));
            }
            if (toTableId != null) {
                byTable.computeIfAbsent(toTableId, k -> new ArrayList<>())
                        .add(new Entry(edge, fromTableId, true));
            }
        }
        return new RelationAdjacency(byTable);
    }

    /** tableId 出发能碰到的所有边（正向、反向都算），没有邻接边时返回空表。 */
    public List<Entry> neighbors(String tableId) {
        return byTable.getOrDefault(tableId, List.of());
    }

    /**
     * 从 {@code column:xxx} / {@code table:xxx} 节点 id 解析出所属表 id。
     * relations 里的 from/to 目前都是列节点，表节点分支是预留。
     */
    public static String tableIdForNode(String nodeId) {
        if (nodeId == null) {
            return null;
        }
        if (nodeId.startsWith("table:")) {
            return nodeId;
        }
        if (nodeId.startsWith("column:")) {
            // column:sap:SAP.JS_JSDZB.PK_JSDZB_H -> table:sap:SAP.JS_JSDZB
            String[] parts = nodeId.split(":", 3);
            if (parts.length < 3) {
                return null;
            }
            String qualifiedName = parts[2];
            int lastDot = qualifiedName.lastIndexOf('.');
            if (lastDot < 0) {
                return null;
            }
            String schemaAndTable = qualifiedName.substring(0, lastDot);
            return "table:" + parts[1] + ":" + schemaAndTable;
        }
        return null;
    }
}
