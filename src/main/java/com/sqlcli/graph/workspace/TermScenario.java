package com.sqlcli.graph.workspace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一条术语展开出来的场景子图：完成这件事要用哪些表、怎么连、要带哪些过滤。
 *
 * <h2>为什么是子图而不是表清单</h2>
 * 术语原来只给「点」（映射到哪几张表），agent 拿到几个孤立的表名之后还得自己拼 join。
 * 拼错的后果是跨租户笛卡尔积、漏软删除、漏项目隔离——**SQL 照跑、有结果、数是错的**。
 * 子图把「边」一起给出来，边的形状复用 {@link WorkspacePathFinder.PathSegment}
 * （完整 ON 条件 + 两端 optionality），agent 已经会读那个格式。
 *
 * <h2>展开规则</h2>
 * <ul>
 *   <li>入口是 {@code primaryTarget} 指向的表。**指向列或没填就不是场景**，返回 null——
 *       用一个字段的指向决定行为，而不是用一个可以和事实不符的 {@code kind} 声明</li>
 *   <li>表集合 = 入口表 ∪ {@code term_mapping} 映射到的表，**不额外展开一跳**。
 *       531 表的库上一跳能拉出几十张，每多一张就是 agent 多一个连错的选择</li>
 *   <li>先只用集合内部的边连（零桥接）；连不上的才用最短路径补桥接表，并标出来——
 *       不标的话人会以为桥接表也属于这个场景</li>
 * </ul>
 *
 * <p>纯函数，不碰 IO。
 */
public record TermScenario(
        String termId,
        String entryTableId,
        List<ScenarioTable> tables,
        List<WorkspacePathFinder.PathSegment> hops,
        List<String> filters,
        /** 补了桥接也连不上入口的表：不是错误，多半是关系还没录进图谱 */
        List<String> unreachable,
        /** 多条最短连接可选时，保留备选而不替业务挑一条。 */
        List<Ambiguity> ambiguities) {

    public record Ambiguity(String targetTable, List<WorkspacePathFinder.PathResult> alternatives) {
    }

    public enum Role {
        /** 入口表，子图的 FROM */
        primary,
        /** 术语直接映射到的表 */
        mapped,
        /** 为了连通补进来的，术语并没有映射它 */
        bridge
    }

    public record ScenarioTable(String tableId, String qualifiedName, Role role) {
    }

    /**
     * 把一条术语展开成场景；不是场景（{@code primaryTarget} 为空或指向列）返回 null。
     */
    public static TermScenario of(GraphWorkspace workspace, TermWorkspaceNode term) {
        if (workspace == null || term == null || term.getStatus() == GraphStatus.ignored) return null;
        String entryTableId = tableIdOf(workspace, term.getPrimaryTarget());
        if (entryTableId == null) return null;

        Map<String, Role> roles = new LinkedHashMap<>();
        roles.put(entryTableId, Role.primary);
        for (RelationWorkspaceEdge edge : workspace.getRelations()) {
            if (edge.getStatus() == GraphStatus.ignored) continue;
            if (edge.getType() != RelationType.term_mapping) continue;
            if (!term.getId().equals(edge.getFrom())) continue;
            String tableId = RelationAdjacency.tableIdForNode(edge.getTo());
            if (tableId != null) roles.putIfAbsent(tableId, Role.mapped);
        }

        WorkspacePathFinder pathFinder = new WorkspacePathFinder(workspace);
        List<WorkspacePathFinder.PathSegment> hops = new ArrayList<>();
        Set<String> connections = new LinkedHashSet<>();
        List<String> unreachable = new ArrayList<>();
        List<Ambiguity> ambiguities = new ArrayList<>();
        Set<String> inside = new LinkedHashSet<>(roles.keySet());
        for (String tableId : inside.stream().sorted().toList()) {
            if (tableId.equals(entryTableId)) continue;
            String from = qualifiedNameOf(workspace, entryTableId);
            String to = qualifiedNameOf(workspace, tableId);
            List<WorkspacePathFinder.PathResult> paths = pathFinder.findPaths(from, to, inside);
            if (paths.isEmpty()) paths = pathFinder.findPaths(from, to);
            if (paths.isEmpty()) {
                unreachable.add(to);
            } else if (paths.size() > 1) {
                ambiguities.add(new Ambiguity(to, List.copyOf(paths)));
            } else {
                for (WorkspacePathFinder.PathSegment segment : paths.get(0).getPath()) {
                    String toId = idOfQualifiedName(workspace, segment.getToTable());
                    if (toId != null) roles.putIfAbsent(toId, Role.bridge);
                    if (connections.add(segment.getRelationId())) hops.add(segment);
                }
            }
        }

        List<ScenarioTable> tables = new ArrayList<>();
        roles.forEach((tableId, role) ->
                tables.add(new ScenarioTable(tableId, qualifiedNameOf(workspace, tableId), role)));
        return new TermScenario(term.getId(), entryTableId, List.copyOf(tables),
                List.copyOf(hops), List.copyOf(term.getFilters()), List.copyOf(unreachable), List.copyOf(ambiguities));
    }

    private static String tableIdOf(GraphWorkspace workspace, String ref) {
        if (ref == null || ref.isBlank()) return null;
        // 只认表。指向列的 primaryTarget 不是场景——那种术语退化成同义词路由
        return workspace.getTables().containsKey(ref) ? ref : null;
    }

    private static String qualifiedNameOf(GraphWorkspace workspace, String tableId) {
        TableWorkspaceNode table = tableId == null ? null : workspace.getTables().get(tableId);
        return table != null ? table.getQualifiedName() : tableId;
    }

    private static String idOfQualifiedName(GraphWorkspace workspace, String qualifiedName) {
        TableWorkspaceNode table = workspace.getTableByQualifiedName(qualifiedName);
        return table == null ? null : table.getId();
    }

}
