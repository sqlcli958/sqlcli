package com.sqlcli.graph.workspace;

import lombok.Data;

import java.util.*;

public class WorkspacePathFinder {
    private final GraphWorkspace workspace;

    public WorkspacePathFinder(GraphWorkspace workspace) {
        this.workspace = workspace;
    }

    public List<PathResult> findPaths(String fromTableName, String toTableName) {
        return findPaths(fromTableName, toTableName, null);
    }

    List<PathResult> findPaths(String fromTableName, String toTableName, Set<String> allowedTables) {
        TableWorkspaceNode from = workspace.getTableByQualifiedName(fromTableName);
        TableWorkspaceNode to = workspace.getTableByQualifiedName(toTableName);
        if (from == null || to == null) {
            return List.of();
        }
        if (from.getId().equals(to.getId())) {
            PathResult result = new PathResult();
            result.setHops(0);
            result.setPath(List.of());
            return List.of(result);
        }

        RelationAdjacency adjacency = RelationAdjacency.build(workspace);
        Queue<List<RelationWorkspaceEdge>> queue = new ArrayDeque<>();
        Map<String, Integer> depths = new HashMap<>();

        for (RelationWorkspaceEdge edge : edgesFrom(adjacency, from.getId())) {
            if (allowedTables != null && !allowedTables.contains(RelationAdjacency.tableIdForNode(edge.getTo()))) continue;
            queue.add(new ArrayList<>(List.of(edge)));
        }
        List<PathResult> results = new ArrayList<>();
        int shortest = Integer.MAX_VALUE;

        while (!queue.isEmpty()) {
            List<RelationWorkspaceEdge> path = queue.poll();
            if (path.size() > shortest) break;
            RelationWorkspaceEdge last = path.get(path.size() - 1);
            String targetTableId = RelationAdjacency.tableIdForNode(last.getTo());
            if (targetTableId == null) {
                continue;
            }
            if (targetTableId.equals(to.getId())) {
                shortest = Math.min(shortest, path.size());
                results.add(toPathResult(path));
                continue;
            }
            if (path.size() >= shortest || path.size() > depths.getOrDefault(targetTableId, Integer.MAX_VALUE)) {
                continue;
            }
            depths.put(targetTableId, path.size());
            for (RelationWorkspaceEdge next : edgesFrom(adjacency, targetTableId)) {
                if (allowedTables != null && !allowedTables.contains(RelationAdjacency.tableIdForNode(next.getTo()))) continue;
                if (containsNode(path, next.getTo())) {
                    continue;
                }
                List<RelationWorkspaceEdge> extended = new ArrayList<>(path);
                extended.add(next);
                queue.add(extended);
            }
        }

        results.sort(Comparator.comparingInt(PathResult::getHops));
        return results;
    }

    private boolean containsNode(List<RelationWorkspaceEdge> path, String nodeId) {
        String tableId = RelationAdjacency.tableIdForNode(nodeId);
        for (RelationWorkspaceEdge edge : path) {
            if (Objects.equals(RelationAdjacency.tableIdForNode(edge.getFrom()), tableId)
                    || Objects.equals(RelationAdjacency.tableIdForNode(edge.getTo()), tableId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 邻接层过滤 ignored 并按表分桶；路径层将同一复合外键视为一次连接。
     * 反向记录保留原关系身份与状态，只翻转方向、基数和 optionality，不落盘。
     */
    private static List<RelationWorkspaceEdge> edgesFrom(RelationAdjacency adjacency, String tableId) {
        List<RelationWorkspaceEdge> edges = new ArrayList<>();
        Set<String> groups = new HashSet<>();
        for (RelationAdjacency.Entry entry : adjacency.neighbors(tableId).stream()
                .sorted(Comparator.comparing(item -> item.getEdge().getId())).toList()) {
            RelationWorkspaceEdge edge = entry.getEdge();
            if (edge.getType() == RelationType.foreign_key && edge.getFkGroup() != null
                    && !groups.add(entry.getNeighborTableId() + "#" + entry.isReversed() + "#" + edge.getFkGroup())) continue;
            edges.add(entry.isReversed() ? reverseOf(entry.getEdge()) : entry.getEdge());
        }
        return edges;
    }

    private static RelationWorkspaceEdge reverseOf(RelationWorkspaceEdge edge) {
        RelationWorkspaceEdge reverse = RelationWorkspaceEdge.create(
                edge.getSourceAlias(), edge.getType(), edge.getTo(), edge.getFrom(), GraphActor.system);
        reverse.setDirection(RelationDirection.reverse);
        reverse.setId(edge.getId());
        reverse.setStatus(edge.getStatus());
        reverse.setCardinality(edge.getCardinality() == RelationCardinality.one_to_many
                ? RelationCardinality.many_to_one : edge.getCardinality() == RelationCardinality.many_to_one
                ? RelationCardinality.one_to_many : edge.getCardinality());
        reverse.setConfidence(edge.getConfidence());
        reverse.setVerified(edge.getVerified());
        // joinExpression 是等式，正反方向读出来都成立，原样带过去；
        // optionality 两端要跟着 from/to 一起翻转——反向边的 from 是原边的 to，
        // 不翻转的话消费方读到的「哪端可选」和实际方向对不上，选错 INNER/LEFT。
        reverse.setJoinExpression(edge.getJoinExpression());
        if (edge.getFkGroup() != null) {
            reverse.getAttributes().put(RelationWorkspaceEdge.ATTR_FK_GROUP, edge.getFkGroup());
        }
        if (edge.getFromOptional() != null) {
            reverse.getAttributes().put(RelationWorkspaceEdge.ATTR_TO_OPTIONAL, edge.getFromOptional());
        }
        if (edge.getToOptional() != null) {
            reverse.getAttributes().put(RelationWorkspaceEdge.ATTR_FROM_OPTIONAL, edge.getToOptional());
        }
        // 来源跟着 optionality 一起带过去：不带的话反向走的那一跳会退回「按声明推断」的说法，
        // 而值是实测出来的。
        Object source = edge.getAttributes().get(RelationWorkspaceEdge.ATTR_OPTIONALITY_SOURCE);
        if (source != null) {
            reverse.getAttributes().put(RelationWorkspaceEdge.ATTR_OPTIONALITY_SOURCE, source);
        }
        return reverse;
    }

    private PathResult toPathResult(List<RelationWorkspaceEdge> edges) {
        PathResult result = new PathResult();
        result.setHops(edges.size());
        List<PathSegment> segments = new ArrayList<>();
        for (RelationWorkspaceEdge edge : edges) {
            ColumnRef fromRef = resolveColumnRef(edge.getFrom());
            ColumnRef toRef = resolveColumnRef(edge.getTo());
            if (fromRef == null || toRef == null) {
                continue;
            }
            PathSegment segment = new PathSegment();
            segment.setFromTable(fromRef.table);
            segment.setFromColumn(fromRef.column);
            segment.setToTable(toRef.table);
            segment.setToColumn(toRef.column);
            segment.setRelationshipType(edge.getType().name());
            segment.setRelationId(edge.getId());
            segment.setStatus(edge.getStatus());
            segment.setVerified(edge.getVerified());
            segment.setConfidence(edge.getConfidence());
            segment.setCardinality(edge.getCardinality());
            // 复合外键的每条列边现在都带着同组拼好的完整 joinExpression（见
            // WorkspaceMetadataExtractor），这里不用另起一套按组重新拼接的逻辑，
            // 直接透传即可拿到全部列对，不会漏掉复合键里的租户列这类字段。
            segment.setJoinExpression(edge.getJoinExpression());
            segment.setFromOptional(edge.getFromOptional());
            segment.setToOptional(edge.getToOptional());
            Object source = edge.getAttributes().get(RelationWorkspaceEdge.ATTR_OPTIONALITY_SOURCE);
            segment.setOptionalitySource(source == null ? null : source.toString());
            segments.add(segment);
        }
        result.setPath(segments);
        return result;
    }

    private ColumnRef resolveColumnRef(String columnId) {
        if (columnId == null || !columnId.startsWith("column:")) {
            return null;
        }
        String[] parts = columnId.split(":", 3);
        if (parts.length < 3) {
            return null;
        }
        String qualifiedName = parts[2]; // SAP.JS_JSDZB.PK_JSDZB_H
        int lastDot = qualifiedName.lastIndexOf('.');
        if (lastDot < 0) {
            return null;
        }
        int firstDot = qualifiedName.indexOf('.');
        if (firstDot < 0 || firstDot >= lastDot) {
            return null;
        }
        ColumnRef ref = new ColumnRef();
        ref.table = qualifiedName.substring(0, lastDot);
        ref.column = qualifiedName.substring(lastDot + 1);
        return ref;
    }

    @Data
    public static class PathResult {
        private int hops;
        private List<PathSegment> path;
    }

    @Data
    public static class PathSegment {
        private String fromTable;
        private String fromColumn;
        private String toTable;
        private String toColumn;
        private String relationshipType;
        private String relationId;
        private GraphStatus status;
        private Boolean verified;
        private Double confidence;
        /** 按本跳 from/to 方向表达，反向遍历时一对多与多对一互换。 */
        private RelationCardinality cardinality;
        /** 可执行的 join 条件，复合外键已经是拼好的完整 AND 条件。 */
        private String joinExpression;
        /** fromTable 端参与是否可选，null 表示还没有可选性信息（非外键关系）。 */
        private Boolean fromOptional;
        /** toTable 端参与是否可选。两者都是推断值，消费方据此选 INNER / LEFT。 */
        private Boolean toOptional;
        /** 上面两个值哪来的：{@code inferred}=只看 nullable 声明，{@code profiled}=看实测空值率。 */
        private String optionalitySource;
    }

    private static class ColumnRef {
        String table;
        String column;
    }
}
