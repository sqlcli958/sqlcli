package com.sqlcli.graph.workspace;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class WorkspaceValidator {
    private final RelationValidator relationValidator = new RelationValidator();

    public void validate(GraphWorkspace workspace) {
        String runId = workspace.getManifest() != null
                ? String.valueOf(workspace.getManifest().getRevision())
                : "0";
        workspace.getValidationIssues().removeIf(issue ->
                issue.getProducer() == null || "validator".equals(issue.getProducer()));
        Set<String> nodeIds = new HashSet<>();
        Set<String> relationIds = new HashSet<>();

        validateManifest(workspace, runId);
        collectNodeIds(workspace, nodeIds, runId);
        validateRelations(workspace, nodeIds, relationIds, runId);
        validateMetrics(workspace, runId);
        validateRedundantColumns(workspace, runId);
        validateConfidence(workspace, runId);

        if (workspace.getManifest() != null) {
            workspace.getManifest().setLastValidationAt(LocalDateTime.now());
        }
    }

    private void validateManifest(GraphWorkspace workspace, String runId) {
        if (workspace.getManifest() == null) {
            addIssue(workspace, runId, ValidationSeverity.error, "missing_manifest",
                    "workspace manifest does not exist", "workspace", null);
        }
    }

    private void collectNodeIds(GraphWorkspace workspace, Set<String> nodeIds, String runId) {
        String alias = workspace.getManifest() != null ? workspace.getManifest().getAlias() : "";
        workspace.getSchemas().values().forEach(node -> checkNode(nodeIds, workspace, node, runId));
        workspace.getTables().values().forEach(node -> {
            checkNode(nodeIds, workspace, node, runId);
            for (ColumnWorkspaceNode column : node.getColumns()) {
                String columnId = column.computeId(alias, node.getSchema(), node.getName());
                if (columnId != null && !columnId.isBlank()) {
                    if (!nodeIds.add(columnId)) {
                        addIssue(workspace, runId, ValidationSeverity.error, "duplicate_node_id",
                                "duplicate node id", columnId, "id");
                    }
                }
            }
        });
        workspace.getTerms().values().forEach(node -> checkNode(nodeIds, workspace, node, runId));
        if (workspace.getDataSource() != null) {
            checkNode(nodeIds, workspace, workspace.getDataSource(), runId);
        }
    }

    private void checkNode(Set<String> nodeIds, GraphWorkspace workspace, BaseGraphObject node, String runId) {
        if (node.getId() == null || node.getId().isBlank()) {
            addIssue(workspace, runId, ValidationSeverity.error, "missing_required_field",
                    "node id is required", null, "id");
            return;
        }
        if (!nodeIds.add(node.getId())) {
            addIssue(workspace, runId, ValidationSeverity.error, "duplicate_node_id",
                    "duplicate node id", node.getId(), "id");
        }
    }

    private void validateRelations(GraphWorkspace workspace, Set<String> nodeIds, Set<String> relationIds,
            String runId) {
        for (RelationWorkspaceEdge relation : workspace.getRelations()) {
            // id 全局唯一是结构不变量，与状态无关——即使一条边被 ignored，它的 id 撞车
            // 仍然是数据损坏，必须照样报，不能因为"反正没人用它"就放过。
            if (!relationIds.add(relation.getId())) {
                addIssue(workspace, runId, ValidationSeverity.error, "duplicate_relation_id",
                        "duplicate relation id", relation.getId(), "id");
            }
            // 悬空端点 / 非法端点类型：跳过 ignored 边。一条已经被所有生成路径排除的边，
            // 报出结构问题只是给校验面板添噪音——它不会被路径查找、SQL 生成等任何下游用到，
            // 悬空与否不影响任何实际产出。
            if (relation.getStatus() == GraphStatus.ignored) {
                continue;
            }
            if (!nodeIds.contains(relation.getFrom())) {
                addIssue(workspace, runId, ValidationSeverity.error, "dangling_relation_source",
                        "relation source does not exist", relation.getId(), "from");
            }
            if (!nodeIds.contains(relation.getTo())) {
                addIssue(workspace, runId, ValidationSeverity.error, "dangling_relation_target",
                        "relation target does not exist", relation.getId(), "to");
            }
            GraphObjectKind fromKind = workspace.resolveNodeKind(relation.getFrom());
            GraphObjectKind toKind = workspace.resolveNodeKind(relation.getTo());
            if (fromKind != null && toKind != null) {
                RelationValidator.ValidationResult result = relationValidator.validate(
                        relation.getType(), fromKind, toKind, relation.getConfidence(), relation.getVerified());
                for (String error : result.getErrors()) {
                    addIssue(workspace, runId, ValidationSeverity.error, "invalid_relation_endpoint",
                            error, relation.getId(), "type");
                }
            }
        }
    }

    /**
     * 校验 metric 引用的表 / 列 / 关系是否存在。expression / filters 是自由文本，解析属于
     * SQL 生成层的事，这里只校验模型里能结构化引用的三样：dimensions、grain.timeColumn、
     * joinPath.relationId——metric 声明的 join 是全系统质量最高的 join 知识，指向一条
     * 已经不存在的关系边比指向一个不存在的表更容易被忽略，必须报出来。
     */
    private void validateMetrics(GraphWorkspace workspace, String runId) {
        // 不过滤 ignored：持久化/结构联动之外，这里是按 id 精确定位一条具体的关系边，
        // 不是"拿关系去派生东西"的路径，ignored 边照样要能被找到——否则下面就没法
        // 把"存在但被拒"和"根本不存在"分开报。
        Map<String, RelationWorkspaceEdge> relationsById = new HashMap<>();
        for (RelationWorkspaceEdge relation : workspace.getRelations()) {
            relationsById.put(relation.getId(), relation);
        }
        for (MetricRecord metric : workspace.getMetrics().values()) {
            for (String dimension : metric.getDimensions()) {
                if (workspace.findColumnById(dimension) == null) {
                    addIssue(workspace, runId, ValidationSeverity.error, "dangling_metric_dimension",
                            "metric dimension column does not exist", metric.getId(), "dimensions");
                }
            }
            MetricRecord.MetricGrain grain = metric.getGrain();
            if (grain != null && grain.getTimeColumn() != null
                    && workspace.findColumnById(grain.getTimeColumn()) == null) {
                addIssue(workspace, runId, ValidationSeverity.error, "dangling_metric_grain_column",
                        "metric grain time column does not exist", metric.getId(), "grain.timeColumn");
            }
            for (MetricRecord.MetricJoinStep step : metric.getJoinPath()) {
                RelationWorkspaceEdge relation = step.getRelationId() == null
                        ? null : relationsById.get(step.getRelationId());
                if (relation == null) {
                    addIssue(workspace, runId, ValidationSeverity.error, "dangling_metric_join_relation",
                            "metric join path references a relation that does not exist",
                            metric.getId(), "joinPath.relationId");
                } else if (relation.getStatus() == GraphStatus.ignored) {
                    // id 存在所以不算悬空，但比悬空更糟：这条 metric 赖以成立的连接基础
                    // 被人明确拒绝过，等于有人推翻了这条 metric 的前提，必须单独报出来，
                    // 不能被"id 还在"这个更弱的检查悄悄放过。
                    addIssue(workspace, runId, ValidationSeverity.error, "metric_joinpath_relation_ignored",
                            "metric join path references a relation that has been rejected",
                            metric.getId(), "joinPath.relationId");
                }
            }
        }
    }

    /**
     * 权威源标注（{@link ColumnWorkspaceNode#ATTR_REDUNDANT_OF}）是人/Agent 手填的列 id，
     * 表结构改了（列被删/重命名）标注不会跟着变，得靠这条把悬空的权威源找出来——不然
     * Agent 会照着一个不存在的列名去拼 SQL，或者更隐蔽地一直信一个已经不对的提示。
     * CLI 写入时已经校验过一次（{@code schema edit --redundant-of}），这里补的是导入/
     * 手改图谱文件绕过 CLI 的那条路径。
     */
    private void validateRedundantColumns(GraphWorkspace workspace, String runId) {
        for (TableWorkspaceNode table : workspace.getTables().values()) {
            for (ColumnWorkspaceNode column : table.getColumns()) {
                String redundantOf = column.getRedundantOf();
                if (redundantOf == null) {
                    continue;
                }
                String columnId = column.computeId(table.getSourceAlias(), table.getSchema(), table.getName());
                if (workspace.findColumnById(redundantOf) == null) {
                    addIssue(workspace, runId, ValidationSeverity.error, "dangling_redundant_source",
                            "redundant column's authoritative source does not exist", columnId, "redundantOf");
                } else if (redundantOf.equals(columnId)) {
                    addIssue(workspace, runId, ValidationSeverity.error, "self_redundant_source",
                            "column marked as redundant copy of itself", columnId, "redundantOf");
                }
            }
        }
    }

    private void validateConfidence(GraphWorkspace workspace, String runId) {
        workspace.getTables().values().forEach(node -> {
            checkConfidence(workspace, node, runId);
            for (ColumnWorkspaceNode column : node.getColumns()) {
                if (column.getConfidence() != null && (column.getConfidence() < 0 || column.getConfidence() > 1)) {
                    addIssue(workspace, runId, ValidationSeverity.error, "invalid_confidence",
                            "confidence must be between 0 and 1",
                            column.computeId(node.getSourceAlias(), node.getSchema(), node.getName()), "confidence");
                }
            }
        });
        workspace.getRelations().forEach(node -> checkConfidence(workspace, node, runId));
        workspace.getSchemas().values().forEach(node -> checkConfidence(workspace, node, runId));
        workspace.getTerms().values().forEach(node -> checkConfidence(workspace, node, runId));
        workspace.getMetrics().values().forEach(node -> checkConfidence(workspace, node, runId));
    }

    private void checkConfidence(GraphWorkspace workspace, BaseGraphObject node, String runId) {
        if (node.getConfidence() != null && (node.getConfidence() < 0 || node.getConfidence() > 1)) {
            addIssue(workspace, runId, ValidationSeverity.error, "invalid_confidence",
                    "confidence must be between 0 and 1", node.getId(), "confidence");
        }
    }

    private void addIssue(GraphWorkspace workspace, String runId, ValidationSeverity severity, String code,
            String message, String targetId, String field) {
        ValidationIssueRecord issue = ValidationIssueRecord.create(
                workspace.getManifest() != null ? workspace.getManifest().getAlias() : "unknown",
                severity, code, message, targetId, field);
        issue.setProducer("validator");
        issue.setRunId(runId);
        workspace.getValidationIssues().add(issue);
    }
}
