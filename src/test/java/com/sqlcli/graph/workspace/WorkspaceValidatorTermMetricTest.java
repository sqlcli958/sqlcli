package com.sqlcli.graph.workspace;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Term → Metric 是结构化依赖，校验行为必须和 metric 的 grain/dimension/joinPath 一样可预期。 */
class WorkspaceValidatorTermMetricTest {

    private static final String ALIAS = "term-metric-validation";

    @Test
    void reportsMissingAndIgnoredMetricBindingsButSkipsRejectedTerms() {
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");

        MetricRecord ignoredMetric = MetricRecord.create(ALIAS, "old_metric", GraphActor.agent);
        ignoredMetric.setExpression("COUNT(*)");
        ignoredMetric.setStatus(GraphStatus.ignored);
        workspace.getMetrics().put(ignoredMetric.getId(), ignoredMetric);

        TermWorkspaceNode active = TermWorkspaceNode.create(ALIAS, "业务场景", GraphActor.human);
        active.setMetricRefs(new ArrayList<>(List.of(
                GraphIds.metricId(ALIAS, "missing_metric"),
                ignoredMetric.getId())));
        workspace.getTerms().put(active.getId(), active);

        TermWorkspaceNode rejected = TermWorkspaceNode.create(ALIAS, "已拒绝场景", GraphActor.agent);
        rejected.setStatus(GraphStatus.ignored);
        rejected.setMetricRefs(new ArrayList<>(List.of(GraphIds.metricId(ALIAS, "also_missing"))));
        workspace.getTerms().put(rejected.getId(), rejected);

        new WorkspaceValidator().validate(workspace);

        assertTrue(workspace.getValidationIssues().stream().anyMatch(issue ->
                "dangling_term_metric".equals(issue.getCode())
                        && active.getId().equals(issue.getTargetId())));
        assertTrue(workspace.getValidationIssues().stream().anyMatch(issue ->
                "term_metric_ignored".equals(issue.getCode())
                        && active.getId().equals(issue.getTargetId())));
        assertFalse(workspace.getValidationIssues().stream().anyMatch(issue ->
                rejected.getId().equals(issue.getTargetId())),
                "ignored term 已退出消费面，不应继续制造开放校验问题");
    }
}
