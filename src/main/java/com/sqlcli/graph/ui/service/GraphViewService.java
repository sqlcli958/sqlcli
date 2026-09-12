package com.sqlcli.graph.ui.service;

import com.sqlcli.graph.workspace.*;
import com.sqlcli.graph.ui.dto.FieldPairDto;
import com.sqlcli.graph.ui.dto.GraphEdgeDto;
import com.sqlcli.graph.ui.dto.GraphNodeDto;
import com.sqlcli.graph.ui.dto.GraphViewDto;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds graph views from workspace data.
 * <ul>
 *   <li>Builds workspace adjacency list</li>
 *   <li>Supports table + depth expansion</li>
 *   <li>Supports schema/domain/relationType filtering</li>
 *   <li>Supports includeIsolated</li>
 *   <li>Aggregates field-level relations into table-level edges</li>
 *   <li>maxNodes/maxEdges limits with truncation</li>
 *   <li>Cache key includes workspace revision</li>
 * </ul>
 */
public class GraphViewService {

    private final GraphWorkspace workspace;

    public GraphViewService(GraphWorkspace workspace) {
        this.workspace = workspace;
    }

    /**
     * Build a graph view with optional filters.
     *
     * @param schemaFilter     optional schema name filter
     * @param tableSeed        optional table qualified name to seed from
     * @param depth            expansion depth from seed (default 1)
     * @param relationTypeFilter optional relation type filter
     * @param includeIsolated  include tables with no relations
     * @param maxNodes         max nodes to return
     * @param maxEdges         max edges to return
     * @return graph view DTO
     */
    public GraphViewDto buildView(String schemaFilter,
                                  String tableSeed, int depth,
                                  String relationTypeFilter,
                                  boolean includeIsolated,
                                  int maxNodes, int maxEdges) {
        // Build adjacency: tableId -> set of related tableIds
        Map<String, Set<String>> adjacency = buildAdjacency();

        // Determine which table IDs to include
        Set<String> includedTableIds = selectTables(schemaFilter,
                tableSeed, depth, includeIsolated, adjacency);

        // Aggregate field-level relations into table-level edges
        List<AggregatedEdge> aggregatedEdges = aggregateRelations(
                includedTableIds, relationTypeFilter);

        // Build nodes
        List<GraphNodeDto> allNodes = new ArrayList<>();
        for (String tableId : includedTableIds) {
            TableWorkspaceNode table = workspace.getTables().get(tableId);
            if (table == null) continue;
            GraphNodeDto node = new GraphNodeDto();
            node.setId(table.getId());
            node.setKind("table");
            node.setLabel(table.getName());
            node.setSchema(table.getSchema());
            // 画布上每个节点只有一行说明的位置，按「我们写的 > 库里写的」取一个：
            // description 是人/Agent 写的业务描述，comment 是导入来的库注释。
            // 前者存在就说明有人专门为这张表写过解释，比库注释更该出现在这一行。
            String tableDescription = table.getDescription();
            node.setDescription(tableDescription == null || tableDescription.isBlank()
                    ? table.getComment() : tableDescription);
            node.setRelationCount(countRelations(tableId, adjacency));
            node.setColumnCount(table.getColumns() == null ? 0 : table.getColumns().size());
            node.setValidationSeverity(worstValidationSeverity(tableId));
            allNodes.add(node);
        }

        // Sort nodes: by relation count descending, then name
        allNodes.sort(Comparator
                .comparingInt(GraphNodeDto::getRelationCount).reversed()
                .thenComparing(GraphNodeDto::getLabel, String.CASE_INSENSITIVE_ORDER));

        // Build edges
        List<GraphEdgeDto> allEdges = new ArrayList<>();
        for (AggregatedEdge agg : aggregatedEdges) {
            GraphEdgeDto edge = new GraphEdgeDto();
            edge.setId(agg.edgeId);
            edge.setSource(agg.sourceTableId);
            edge.setTarget(agg.targetTableId);
            edge.setType(agg.type);
            edge.setFieldPairs(agg.fieldPairs);
            edge.setRelationIds(agg.relationIds);
            // Use the first relation's confidence/verified as representative
            if (!agg.relations.isEmpty()) {
                RelationWorkspaceEdge first = agg.relations.get(0);
                edge.setConfidence(first.getConfidence());
                edge.setVerified(first.getVerified());
            }
            allEdges.add(edge);
        }

        // Apply limits
        boolean truncated = allNodes.size() > maxNodes || allEdges.size() > maxEdges;
        List<GraphNodeDto> nodes = allNodes.subList(0, Math.min(allNodes.size(), maxNodes));
        Set<String> nodeIds = nodes.stream().map(GraphNodeDto::getId).collect(Collectors.toSet());
        List<GraphEdgeDto> edges = allEdges.stream()
                .filter(e -> nodeIds.contains(e.getSource()) && nodeIds.contains(e.getTarget()))
                .limit(maxEdges)
                .collect(Collectors.toList());

        GraphViewDto result = new GraphViewDto();
        result.setRevision(workspace.getManifest().getRevision());
        result.setTruncated(truncated);

        GraphViewDto.Stats stats = new GraphViewDto.Stats();
        stats.setTotalNodes(allNodes.size());
        stats.setTotalEdges(allEdges.size());
        stats.setReturnedNodes(nodes.size());
        stats.setReturnedEdges(edges.size());
        result.setStats(stats);

        result.setNodes(new ArrayList<>(nodes));
        result.setEdges(edges);
        return result;
    }

    /**
     * Build adjacency: tableId -> set of related tableIds.
     *
     * <p>跳过 {@code GraphStatus.ignored}：邻接表喂给两件事——节点的 relationCount 和
     * includeIsolated 的孤立判定——两个都是"画给人看、辅助人判断"的派生结果，人已经明确
     * 拒绝过的关系不能反过来让一张表看起来"有关联"。策略见
     * {@link WorkspaceMutationService#isIgnored(GraphWorkspace, String, RelationType, String, String)}
     * 的方法注释。
     */
    private Map<String, Set<String>> buildAdjacency() {
        Map<String, Set<String>> adj = new LinkedHashMap<>();
        for (TableWorkspaceNode table : workspace.getTables().values()) {
            adj.putIfAbsent(table.getId(), new LinkedHashSet<>());
        }
        for (RelationWorkspaceEdge rel : workspace.getRelations()) {
            if (rel.getStatus() == GraphStatus.ignored) continue;
            String fromTableId = resolveTableId(rel.getFrom());
            String toTableId = resolveTableId(rel.getTo());
            if (fromTableId != null && toTableId != null
                    && !fromTableId.equals(toTableId)) {
                adj.computeIfAbsent(fromTableId, k -> new LinkedHashSet<>()).add(toTableId);
                adj.computeIfAbsent(toTableId, k -> new LinkedHashSet<>()).add(fromTableId);
            }
        }
        return adj;
    }

    /**
     * Select table IDs based on filters.
     */
    private Set<String> selectTables(String schemaFilter,
                                     String tableSeed, int depth,
                                     boolean includeIsolated,
                                     Map<String, Set<String>> adjacency) {
        Set<String> result = new LinkedHashSet<>();

        // Start with schema filter
        for (TableWorkspaceNode table : workspace.getTables().values()) {
            boolean matches = true;
            if (schemaFilter != null && !schemaFilter.isBlank()) {
                matches = schemaFilter.equalsIgnoreCase(table.getSchema());
            }
            if (matches) {
                result.add(table.getId());
            }
        }

        // If table seed specified, expand from seed
        if (tableSeed != null && !tableSeed.isBlank()) {
            TableWorkspaceNode seedTable = workspace.getTableByQualifiedName(tableSeed);
            if (seedTable != null) {
                Set<String> expanded = expandFromSeed(seedTable.getId(), depth, adjacency);
                if (schemaFilter == null) {
                    result = expanded;
                } else {
                    // Intersect: only include expanded tables that also pass filter
                    result.retainAll(expanded);
                    // But always include the seed
                    result.add(seedTable.getId());
                }
            }
        }

        // Remove isolated tables unless includeIsolated
        if (!includeIsolated && tableSeed == null) {
            result.removeIf(id -> {
                Set<String> neighbors = adjacency.get(id);
                return neighbors == null || neighbors.isEmpty();
            });
        }

        return result;
    }

    /**
     * BFS expansion from a seed table.
     */
    private Set<String> expandFromSeed(String seedId, int depth, Map<String, Set<String>> adjacency) {
        Set<String> visited = new LinkedHashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        queue.add(seedId);
        visited.add(seedId);
        int level = 0;
        while (!queue.isEmpty() && level < Math.max(1, depth)) {
            int size = queue.size();
            for (int i = 0; i < size; i++) {
                String current = queue.poll();
                Set<String> neighbors = adjacency.getOrDefault(current, Set.of());
                for (String neighbor : neighbors) {
                    if (visited.add(neighbor)) {
                        queue.add(neighbor);
                    }
                }
            }
            level++;
        }
        return visited;
    }

    /**
     * Aggregate field-level relations into table-level edges.
     * Key: (sourceTableId, targetTableId, type) -> AggregatedEdge
     *
     * <p>跳过已忽略的边：画布边是"图谱里有什么关联"的直接呈现，人拒绝过的关联不能画出来
     * 让 Agent 或看图的人误以为它还成立。
     */
    private List<AggregatedEdge> aggregateRelations(Set<String> includedTableIds,
                                                    String relationTypeFilter) {
        Map<String, AggregatedEdge> aggMap = new LinkedHashMap<>();

        for (RelationWorkspaceEdge rel : workspace.getRelations()) {
            if (rel.getStatus() == GraphStatus.ignored) continue;
            // Filter by relation type
            if (relationTypeFilter != null && !relationTypeFilter.isBlank()) {
                if (rel.getType() == null || !relationTypeFilter.equalsIgnoreCase(rel.getType().name())) {
                    continue;
                }
            }

            String fromTableId = resolveTableId(rel.getFrom());
            String toTableId = resolveTableId(rel.getTo());
            if (fromTableId == null || toTableId == null) continue;
            if (!includedTableIds.contains(fromTableId) || !includedTableIds.contains(toTableId)) continue;

            String sourceId = fromTableId;
            String targetId = toTableId;

            String type = rel.getType() != null ? rel.getType().name() : "unknown";
            String key = sourceId + "|" + targetId + "|" + type;

            AggregatedEdge agg = aggMap.computeIfAbsent(key, k -> {
                AggregatedEdge e = new AggregatedEdge();
                e.edgeId = "edge:" + sourceId + "->" + targetId + ":" + type;
                e.sourceTableId = sourceId;
                e.targetTableId = targetId;
                e.type = type;
                return e;
            });

            agg.relationIds.add(rel.getId());
            agg.relations.add(rel);

            // Add field pair
            String fromCol = resolveColumnName(rel.getFrom());
            String toCol = resolveColumnName(rel.getTo());
            if (fromCol != null && toCol != null) {
                agg.fieldPairs.add(new FieldPairDto(fromCol, toCol));
            }
        }

        return new ArrayList<>(aggMap.values());
    }

    private String resolveTableId(String nodeId) {
        if (nodeId == null) return null;
        if (nodeId.startsWith("table:")) return nodeId;
        if (nodeId.startsWith("column:")) {
            // Parse column:alias:SCHEMA.TABLE.COLUMN -> table:alias:SCHEMA.TABLE
            String[] parts = nodeId.split(":", 3);
            if (parts.length < 3) return null;
            String qualified = parts[2];
            int lastDot = qualified.lastIndexOf('.');
            if (lastDot < 0) return null;
            return "table:" + parts[1] + ":" + qualified.substring(0, lastDot);
        }
        return null;
    }

    private String resolveColumnName(String columnId) {
        if (columnId == null || !columnId.startsWith("column:")) return null;
        String[] parts = columnId.split(":", 3);
        if (parts.length < 3) return null;
        String qualified = parts[2];
        int lastDot = qualified.lastIndexOf('.');
        if (lastDot < 0) return null;
        // Return schema.table.column format for readability
        return qualified;
    }

    private int countRelations(String tableId, Map<String, Set<String>> adjacency) {
        Set<String> neighbors = adjacency.get(tableId);
        return neighbors != null ? neighbors.size() : 0;
    }

    private String worstValidationSeverity(String tableId) {
        String worst = null;
        for (ValidationIssueRecord issue : workspace.getValidationIssues()) {
            if (tableId.equals(issue.getTargetId())) {
                if (worst == null) {
                    worst = issue.getSeverity().name();
                } else if ("error".equalsIgnoreCase(issue.getSeverity().name())) {
                    return "error";
                } else if ("warning".equalsIgnoreCase(issue.getSeverity().name()) && !"error".equalsIgnoreCase(worst)) {
                    worst = "warning";
                }
            }
        }
        return worst;
    }

    /**
     * Internal aggregated edge representation.
     */
    private static class AggregatedEdge {
        String edgeId;
        String sourceTableId;
        String targetTableId;
        String type;
        List<FieldPairDto> fieldPairs = new ArrayList<>();
        List<String> relationIds = new ArrayList<>();
        List<RelationWorkspaceEdge> relations = new ArrayList<>();
    }
}
