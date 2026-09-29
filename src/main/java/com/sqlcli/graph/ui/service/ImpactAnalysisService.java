package com.sqlcli.graph.ui.service;

import com.sqlcli.graph.workspace.GraphStatus;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.LineageRecord;
import com.sqlcli.graph.workspace.MetricRecord;
import com.sqlcli.graph.workspace.RelationAdjacency;
import com.sqlcli.graph.workspace.RelationType;
import com.sqlcli.graph.workspace.RelationWorkspaceEdge;
import com.sqlcli.graph.workspace.TermWorkspaceNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 图谱变更前的依赖影响分析。
 *
 * <p>它回答的是“这个对象如果被删除 / 拒绝，谁会立刻失去语义前提”，不是泛泛的邻居列表。
 * 第一阶段只收系统里已经存在的<strong>结构化引用</strong>：metric grain/dimension/joinPath、
 * term primaryTarget/mapping、lineage sources/target、relation endpoints。自由文本 expression /
 * policy when 不猜引用，宁可少报也不能编一个影响。
 */
public final class ImpactAnalysisService {

    public record ImpactItem(
            String kind,
            String id,
            String label,
            String dependency,
            String status,
            boolean breaking) {
    }

    public record ImpactReport(
            String targetId,
            int total,
            int breaking,
            List<ImpactItem> impacts) {
    }

    public ImpactReport analyze(GraphWorkspace workspace, String targetId) {
        if (workspace == null) throw new IllegalArgumentException("workspace is required");
        if (targetId == null || targetId.isBlank()) throw new IllegalArgumentException("targetId is required");

        List<ImpactItem> impacts = new ArrayList<>();
        collectMetricImpacts(workspace, targetId, impacts);
        collectTermImpacts(workspace, targetId, impacts);
        collectLineageImpacts(workspace, targetId, impacts);
        collectRelationImpacts(workspace, targetId, impacts);

        impacts.sort(Comparator.comparing(ImpactItem::kind).thenComparing(ImpactItem::id)
                .thenComparing(ImpactItem::dependency));
        int breaking = (int) impacts.stream().filter(ImpactItem::breaking).count();
        return new ImpactReport(targetId, impacts.size(), breaking, List.copyOf(impacts));
    }

    private void collectMetricImpacts(GraphWorkspace workspace, String targetId, List<ImpactItem> out) {
        for (MetricRecord metric : workspace.getMetrics().values()) {
            if (metric.getStatus() == GraphStatus.ignored || metric.getId().equals(targetId)) continue;

            MetricRecord.MetricGrain grain = metric.getGrain();
            if (grain != null && references(grain.getTimeColumn(), targetId)) {
                add(out, "metric", metric.getId(), metricLabel(metric), "grain.timeColumn", metric.getStatus(), true);
            }
            for (String dimension : metric.getDimensions()) {
                if (references(dimension, targetId)) {
                    add(out, "metric", metric.getId(), metricLabel(metric), "dimensions", metric.getStatus(), true);
                    break;
                }
            }
            for (MetricRecord.MetricJoinStep step : metric.getJoinPath()) {
                if (targetId.equals(step.getRelationId())) {
                    add(out, "metric", metric.getId(), metricLabel(metric), "joinPath", metric.getStatus(), true);
                    break;
                }
            }
        }
    }

    private void collectTermImpacts(GraphWorkspace workspace, String targetId, List<ImpactItem> out) {
        for (TermWorkspaceNode term : workspace.getTerms().values()) {
            if (term.getStatus() == GraphStatus.ignored || term.getId().equals(targetId)) continue;
            if (references(term.getPrimaryTarget(), targetId)) {
                add(out, "term", term.getId(), termLabel(term), "primaryTarget", term.getStatus(), true);
            }
            for (RelationWorkspaceEdge edge : workspace.getRelations()) {
                if (edge.getStatus() == GraphStatus.ignored || edge.getType() != RelationType.term_mapping) continue;
                if (!term.getId().equals(edge.getFrom())) continue;
                if (targetId.equals(edge.getId()) || references(edge.getTo(), targetId)) {
                    add(out, "term", term.getId(), termLabel(term), "term_mapping", term.getStatus(), true);
                    break;
                }
            }
        }
    }

    private void collectLineageImpacts(GraphWorkspace workspace, String targetId, List<ImpactItem> out) {
        for (LineageRecord lineage : workspace.getLineage().values()) {
            if (lineage.getStatus() == GraphStatus.ignored || lineage.getId().equals(targetId)) continue;
            if (references(lineage.getTarget(), targetId)) {
                add(out, "lineage", lineage.getId(), lineage.getThrough(), "target", lineage.getStatus(), true);
            }
            if (lineage.getSources().stream().anyMatch(source -> references(source, targetId))) {
                add(out, "lineage", lineage.getId(), lineage.getThrough(), "sources", lineage.getStatus(), true);
            }
        }
    }

    private void collectRelationImpacts(GraphWorkspace workspace, String targetId, List<ImpactItem> out) {
        // 如果分析的就是某条关系，它不是自己的 impact；上面的 metric/term 会报告真正依赖它的对象。
        if (targetId.startsWith("relation:")) return;
        for (RelationWorkspaceEdge relation : workspace.getRelations()) {
            if (relation.getStatus() == GraphStatus.ignored || relation.getId().equals(targetId)) continue;
            if (references(relation.getFrom(), targetId) || references(relation.getTo(), targetId)) {
                add(out, "relation", relation.getId(), relation.getType() == null ? null : relation.getType().name(),
                        "endpoint", relation.getStatus(), true);
            }
        }
    }

    private static boolean references(String referenceId, String targetId) {
        if (referenceId == null || targetId == null) return false;
        if (referenceId.equals(targetId)) return true;
        if (!targetId.startsWith("table:")) return false;
        return targetId.equals(RelationAdjacency.tableIdForNode(referenceId));
    }

    private static void add(List<ImpactItem> out, String kind, String id, String label,
            String dependency, GraphStatus status, boolean breaking) {
        ImpactItem item = new ImpactItem(kind, id, label, dependency,
                status == null ? null : status.name(), breaking);
        if (!out.contains(item)) out.add(item);
    }

    private static String termLabel(TermWorkspaceNode term) {
        return term.getDisplayName() == null || term.getDisplayName().isBlank()
                ? term.getName() : term.getDisplayName();
    }

    private static String metricLabel(MetricRecord metric) {
        return metric.getBusinessName() == null || metric.getBusinessName().isBlank()
                ? metric.getName() : metric.getBusinessName();
    }
}
