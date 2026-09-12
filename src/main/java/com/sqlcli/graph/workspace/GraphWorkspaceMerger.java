package com.sqlcli.graph.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 合并两个 workspace，保护已有 workspace 中用户维护的数据。
 *
 * 合并原则：
 * - 系统字段（来自 DB 元数据）：以 incoming 为准，始终更新
 * - 用户字段（businessName, description, grain, tags 等）：existing wins
 * - FK 声明关系：incoming 替换 existing（DB 是权威来源）
 * - 非 FK 关系：existing wins（用户/agent 创建的不被覆盖）
 * - comment 字段：系统字段的一种，incoming 永远覆盖 existing——它是数据库注释的镜像，
 *   只有导入会写它，不存在"用户改过要保护"的情况。注释错了的正确修法是去库里改，
 *   然后重新导入，而不是让合并逻辑绕开覆盖去保护一份陈旧文本。业务解读走
 *   description 字段，与 businessName 同属用户字段，existing wins。
 */
public class GraphWorkspaceMerger {
    private static final Logger log = LoggerFactory.getLogger(GraphWorkspaceMerger.class);

    public GraphWorkspace merge(GraphWorkspace incoming, GraphWorkspace existing) {
        Set<String> scannedFkTableIds = incoming == null ? Set.of() : incoming.getRelations().stream()
                .filter(r -> r.getType() == RelationType.foreign_key)
                .map(this::extractOwningTableId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        return mergeBatch(incoming, existing, scannedFkTableIds);
    }

    public GraphWorkspace mergeBatch(GraphWorkspace incoming, GraphWorkspace existing, Set<String> scannedFkTableIds) {
        if (existing == null) {
            return incoming;
        }
        if (incoming == null) {
            return existing;
        }

        existing.setDataSource(incoming.getDataSource());

        // Schemas: putIfAbsent（不覆盖已有的）
        incoming.getSchemas().values().forEach(node -> existing.getSchemas().putIfAbsent(node.getId(), node));

        // Lineage: id 由内容决定，putIfAbsent 即幂等合并（existing wins，人/Agent 维护的数据）
        incoming.getLineage().values().forEach(record -> existing.getLineage().putIfAbsent(record.getId(), record));

        // Metrics: id 由 name 决定，putIfAbsent 即幂等合并（existing wins）。
        // DB 扫描产生的 incoming 从不含 metric（导入只提取 schema/table/column/relation），
        // 这里要处理的是 export/import 场景：导入快照里的指标不应覆盖当前工作区里已在评审/
        // 已发布的同名口径。
        incoming.getMetrics().values().forEach(record -> existing.getMetrics().putIfAbsent(record.getId(), record));

        // Tables: 逐字段合并
        incoming.getTables().values().forEach(table -> mergeTable(table, existing));

        // Relations: FK 替换，非 FK 保护
        mergeRelations(incoming, existing, scannedFkTableIds == null ? Set.of() : scannedFkTableIds);

        existing.getManifest().touch();
        return existing;
    }

    /**
     * 合并时传入导入涉及的 schema 名称集合，用于标记不在 incoming 中但存在于 existing 中的表为 deprecated。
     * 仅对导入范围内的表做删除检测，范围外的表不触碰。
     */
    public GraphWorkspace merge(GraphWorkspace incoming, GraphWorkspace existing, Set<String> importedSchemaNames) {
        GraphWorkspace result = merge(incoming, existing);
        markMissingTables(result, incoming.getTables().keySet(), importedSchemaNames, null);
        return result;
    }

    public void markMissingTables(GraphWorkspace workspace, Set<String> discoveredTableIds,
            Set<String> importedSchemaNames, String tableFilter) {
        Set<String> discovered = discoveredTableIds == null ? Set.of() : discoveredTableIds.stream()
                .map(id -> id.toLowerCase(java.util.Locale.ROOT))
                .collect(Collectors.toSet());
        Set<String> schemas = importedSchemaNames == null ? Set.of() : importedSchemaNames.stream()
                .filter(java.util.Objects::nonNull)
                .map(s -> s.toLowerCase(java.util.Locale.ROOT))
                .collect(Collectors.toSet());
        String filter = tableFilter == null ? null : tableFilter.toLowerCase(java.util.Locale.ROOT);
        for (TableWorkspaceNode table : workspace.getTables().values()) {
            boolean inScope = filter != null && !filter.isBlank()
                    ? filter.equals(table.getName().toLowerCase(java.util.Locale.ROOT))
                            || filter.equals(table.getQualifiedName().toLowerCase(java.util.Locale.ROOT))
                    : table.getSchema() != null
                            && schemas.contains(table.getSchema().toLowerCase(java.util.Locale.ROOT));
            if (inScope && !discovered.contains(table.getId().toLowerCase(java.util.Locale.ROOT))
                    && table.getStatus() != GraphStatus.deprecated) {
                table.setStatus(GraphStatus.deprecated);
                table.setUpdatedAt(LocalDateTime.now());
                ChangeRecord change = ChangeRecord.create(workspace.getManifest().getAlias(),
                        ChangeOperation.deprecate, table.getId(), GraphActor.extractor);
                change.setReason("not found in completed database import");
                workspace.getChanges().add(change);
                log.info("Table {} marked as deprecated (not found in database)", table.getQualifiedName());
            }
        }
    }

    private void mergeTable(TableWorkspaceNode incoming, GraphWorkspace existing) {
        TableWorkspaceNode target = existing.getTables().get(incoming.getId());
        if (target == null) {
            // 新表，直接添加
            existing.getTables().put(incoming.getId(), incoming);
            return;
        }

        // 系统字段：始终从 incoming 更新（DB 元数据是最权威的）
        target.setTableType(incoming.getTableType());
        target.setSystem(incoming.isSystem());
        target.setIndexes(new java.util.ArrayList<>(incoming.getIndexes()));
        if (target.getStatus() == GraphStatus.deprecated) {
            target.setStatus(incoming.getStatus());
            ChangeRecord change = ChangeRecord.create(existing.getManifest().getAlias(),
                    ChangeOperation.update, target.getId(), GraphActor.extractor);
            change.setReason("table restored by database import");
            existing.getChanges().add(change);
        } else if (incoming.getStatus() != null && incoming.getStatus().ordinal() > target.getStatus().ordinal()) {
            // 不降级 status，但可以升级（如 discovered -> partial）
            // 注意：deprecated 状态不应被覆盖
            if (target.getStatus() != GraphStatus.deprecated) {
                target.setStatus(incoming.getStatus());
            }
        }
        target.setQualifiedName(incoming.getQualifiedName());

        // comment: 数据库注释的镜像，incoming 永远覆盖，没有分支判断
        target.setComment(incoming.getComment());

        // businessName / description: existing wins（用户维护）
        if (isBlank(target.getBusinessName())) {
            target.setBusinessName(incoming.getBusinessName());
        }
        if (isBlank(target.getDescription())) {
            target.setDescription(incoming.getDescription());
        }
        if (isBlank(target.getGrain())) {
            target.setGrain(incoming.getGrain());
        }

        // boost: existing wins（人工权重，DB 导入不产生）
        if (target.getBoost() == null) {
            target.setBoost(incoming.getBoost());
        }

        // primaryKey: 从 DB 更新（结构变更必须同步）
        target.setPrimaryKey(incoming.getPrimaryKey());

        // rowEstimate: 从 DB 更新——它是数据库统计事实，不是用户维护的内容，
        // 留着旧值只会让详情页显示过期行数。采集不到时（旧图谱、不支持的库）保留原值。
        if (incoming.getRowEstimate() != null) {
            target.setRowEstimate(incoming.getRowEstimate());
        }

        // 用户维护字段：existing wins，不覆盖
        // tags, owner — 不动

        // 列合并
        mergeColumns(incoming, target, existing);

        target.setUpdatedAt(LocalDateTime.now());
    }

    private void mergeColumns(TableWorkspaceNode incoming, TableWorkspaceNode target, GraphWorkspace workspace) {
        // 收集 incoming 中的列名，用于检测删除列
        Set<String> incomingColumnNames = incoming.getColumns().stream()
                .map(ColumnWorkspaceNode::getName)
                .filter(name -> name != null)
                .map(name -> name.toLowerCase(java.util.Locale.ROOT))
                .collect(Collectors.toSet());

        for (ColumnWorkspaceNode incomingCol : incoming.getColumns()) {
            ColumnWorkspaceNode existingCol = target.findColumn(incomingCol.getName());
            if (existingCol == null) {
                // 新列，直接添加
                target.getColumns().add(incomingCol);
                continue;
            }

            // 系统字段：始终从 DB 更新
            existingCol.setDataType(incomingCol.getDataType());
            existingCol.setNullable(incomingCol.isNullable());
            existingCol.setDefaultValue(incomingCol.getDefaultValue());
            existingCol.setOrdinal(incomingCol.getOrdinal());
            existingCol.setPrimaryKey(incomingCol.isPrimaryKey());
            existingCol.setIndexed(incomingCol.isIndexed());
            existingCol.setUnique(incomingCol.isUnique());
            if (incomingCol.getAttributes().containsKey("onUpdate")) {
                existingCol.getAttributes().put("onUpdate", incomingCol.getAttributes().get("onUpdate"));
            } else {
                existingCol.getAttributes().remove("onUpdate");
            }
            if (Boolean.TRUE.equals(existingCol.getAttributes().remove("deprecated"))) {
                existingCol.getAttributes().remove("deprecatedAt");
                ChangeRecord change = ChangeRecord.create(workspace.getManifest().getAlias(),
                        ChangeOperation.update,
                        existingCol.computeId(workspace.getManifest().getAlias(), target.getSchema(), target.getName()),
                        GraphActor.extractor);
                change.setReason("column restored by database import");
                workspace.getChanges().add(change);
            }

            // comment: 数据库注释的镜像，incoming 永远覆盖，没有分支判断
            existingCol.setComment(incomingCol.getComment());

            // 用户字段：existing wins
            if (isBlank(existingCol.getBusinessName())) {
                existingCol.setBusinessName(incomingCol.getBusinessName());
            }
            if (isBlank(existingCol.getDescription())) {
                existingCol.setDescription(incomingCol.getDescription());
            }
            if (existingCol.getSemanticType() == null) {
                existingCol.setSemanticType(incomingCol.getSemanticType());
            }
            if (existingCol.getBoost() == null) {
                existingCol.setBoost(incomingCol.getBoost());
            }

            // valueHints: existing wins（agent/用户补充的值提示）
            // verified/confidence: 如果 existing 没有特殊设置，采用 incoming
            if (!Boolean.TRUE.equals(existingCol.getVerified()) && Boolean.TRUE.equals(incomingCol.getVerified())) {
                existingCol.setVerified(true);
                existingCol.setConfidence(incomingCol.getConfidence());
            }
        }

        // 检测删除列：incoming 中不存在的列标记为 deprecated
        for (ColumnWorkspaceNode existingCol : target.getColumns()) {
            if (existingCol.getName() != null
                    && !incomingColumnNames.contains(existingCol.getName().toLowerCase(java.util.Locale.ROOT))) {
                if (existingCol.getAttributes() == null) {
                    existingCol.setAttributes(new java.util.HashMap<>());
                }
                if (!Boolean.TRUE.equals(existingCol.getAttributes().get("deprecated"))) {
                    existingCol.getAttributes().put("deprecated", true);
                    existingCol.getAttributes().put("deprecatedAt", LocalDateTime.now().toString());
                    ChangeRecord change = ChangeRecord.create(workspace.getManifest().getAlias(),
                            ChangeOperation.deprecate,
                            existingCol.computeId(workspace.getManifest().getAlias(), target.getSchema(), target.getName()),
                            GraphActor.extractor);
                    change.setReason("column not found in completed table import");
                    workspace.getChanges().add(change);
                    log.info("Column {}.{} marked as deprecated (not found in database)",
                            target.getQualifiedName(), existingCol.getName());
                }
            }
        }

        // 按 ordinal 排序
        target.getColumns().sort(java.util.Comparator.comparing(
                ColumnWorkspaceNode::getOrdinal, java.util.Comparator.nullsLast(Integer::compareTo)));
    }

    private void mergeRelations(GraphWorkspace incoming, GraphWorkspace existing, Set<String> scannedFkTableIds) {
        // 声明 FK 归属 from（外键）表。已扫描表即使 incoming 返回 0 条边，也必须清理旧 FK——
        // 这是探知「约束被整个删掉了」的唯一信号。
        //
        // 但不能整表一刀切删完再整表加回：复合外键的多列边共享同一个 fkGroup，
        // 如果 incoming 这一批只带回组里的一列（正常的单次 extractForeignKeysForTable
        // 调用不会产生这种输入，但合并器是通用接口，不能假设调用方一定守规矩），
        // 整表删除会把组里没被提到的另一列也删掉，复合外键被腰斩，
        // 这正是任务本身要修的那类静默错误，合并这一步不能重新引入它。
        // 因此按 (owning table, fkGroup) 分桶：整组在 incoming 里完全消失才整组删，
        // 组内只到了一部分列，只替换 incoming 明确带来的那些，其余原样保留。
        Map<String, List<RelationWorkspaceEdge>> incomingGroups = incoming.getRelations().stream()
                .filter(r -> r.getType() == RelationType.foreign_key)
                .collect(Collectors.groupingBy(this::fkGroupKey));
        Map<String, List<RelationWorkspaceEdge>> existingScannedGroups = existing.getRelations().stream()
                .filter(r -> r.getType() == RelationType.foreign_key
                        && scannedFkTableIds.contains(extractOwningTableId(r)))
                .collect(Collectors.groupingBy(this::fkGroupKey));

        for (Map.Entry<String, List<RelationWorkspaceEdge>> entry : existingScannedGroups.entrySet()) {
            List<RelationWorkspaceEdge> existingGroupEdges = entry.getValue();
            Set<String> existingGroupIds = existingGroupEdges.stream()
                    .map(RelationWorkspaceEdge::getId).collect(Collectors.toSet());
            List<RelationWorkspaceEdge> incomingSameGroup = incomingGroups.get(entry.getKey());
            if (incomingSameGroup == null) {
                // incoming 完全没提到这个组：约束在 DB 里确实被删掉了，整组清理
                existing.getRelations().removeIf(r -> existingGroupIds.contains(r.getId()));
            } else {
                // 组还在，但 incoming 只明确带来了组里的一部分：只替换那一部分，
                // 组里没被 incoming 提到的边保留，不当作「被删除」处理
                Set<String> incomingIds = incomingSameGroup.stream()
                        .map(RelationWorkspaceEdge::getId).collect(Collectors.toSet());
                existing.getRelations().removeIf(r -> existingGroupIds.contains(r.getId()) && incomingIds.contains(r.getId()));
            }
        }

        // 添加 incoming 的所有关系
        incoming.getRelations().forEach(incomingRel -> {
            if (incomingRel.getType() == RelationType.foreign_key) {
                existing.getRelations().removeIf(r -> r.getId().equals(incomingRel.getId()));
                existing.getRelations().add(incomingRel);
            } else {
                // 非 FK 关系：existing wins（保留用户/agent 创建的）
                boolean exists = existing.getRelations().stream()
                        .anyMatch(r -> r.getId().equals(incomingRel.getId()));
                if (!exists) {
                    existing.getRelations().add(incomingRel);
                } else {
                    // 已存在同 ID 的关系，增强合并
                    RelationWorkspaceEdge existingRel = existing.getRelations().stream()
                            .filter(r -> r.getId().equals(incomingRel.getId()))
                            .findFirst().orElse(null);
                    if (existingRel != null) {
                        mergeRelationFields(incomingRel, existingRel);
                    }
                }
            }
        });
    }

    /**
     * 分组的合并键，按表加前缀：fkGroup 只保证在同一张子表内唯一（见
     * WorkspaceMetadataExtractor.stableGroupKey），不同表恰好同名约束（比如都叫
     * "fk_tenant"）不应该被当成同一组处理。没有 fkGroup 的边（非分组场景、
     * 旧图谱遗留数据）退化成按边自身 id 分组，等价于原来逐边处理的行为。
     */
    private String fkGroupKey(RelationWorkspaceEdge edge) {
        String group = edge.getFkGroup();
        if (group == null || group.isBlank()) {
            return edge.getId();
        }
        String owningTable = extractOwningTableId(edge);
        return (owningTable == null ? "" : owningTable) + "#" + group;
    }

    private String extractOwningTableId(RelationWorkspaceEdge relation) {
        String columnRef = relation.getFrom();
        if (columnRef == null || !columnRef.startsWith("column:")) {
            return null;
        }
        int firstColon = columnRef.indexOf(':');
        int secondColon = columnRef.indexOf(':', firstColon + 1);
        int lastDot = columnRef.lastIndexOf('.');
        if (secondColon < 0 || lastDot <= secondColon) {
            return null;
        }
        return "table:" + columnRef.substring(firstColon + 1, secondColon) + ":"
                + columnRef.substring(secondColon + 1, lastDot);
    }

    private void mergeRelationFields(RelationWorkspaceEdge incoming, RelationWorkspaceEdge existing) {
        if (incoming.getType() != existing.getType()) {
            log.warn("Relation {} type conflict: existing={}, incoming={}, keeping existing",
                    incoming.getId(), existing.getType(), incoming.getType());
        }
        if (incoming.getConfidence() != null
                && (existing.getConfidence() == null || incoming.getConfidence() > existing.getConfidence())) {
            existing.setConfidence(incoming.getConfidence());
        }
        // verified 需要 >= 0.9 的置信度，低置信度的 incoming 不能把 existing 抬成已验证。
        //
        // existing.getStatus() != ignored 这个条件是补的：重导入/候选挖掘撞上一条已被人
        // 拒绝的边时，本方法只动 confidence/verified/joinExpression/evidence，从不动 status
        // （见 mergeRelations 调用点），所以「ignored 边被重新发现、confidence 恰好也被
        // 推高到 >= 0.9」是完全可能出现的组合——原先这里不看 status，会顺带把 verified
        // 翻成 true，而这是人从没设过的标记，人将来撤销忽略时会看到莫名其妙的
        // "已验证"。今天没有代码脱离 status 单独读 verified，这个组合暂时无害，
        // 但补这一行比留着这个坑等以后有人踩到更省事。
        if (!Boolean.TRUE.equals(existing.getVerified()) && Boolean.TRUE.equals(incoming.getVerified())
                && existing.getStatus() != GraphStatus.ignored
                && existing.getConfidence() != null
                && existing.getConfidence() >= RelationValidator.VERIFIED_MIN_CONFIDENCE) {
            existing.setVerified(true);
        }
        if (isBlank(existing.getJoinExpression()) && !isBlank(incoming.getJoinExpression())) {
            existing.setJoinExpression(incoming.getJoinExpression());
        }
        for (RelationEvidence evidence : incoming.getEvidence()) {
            boolean duplicate = existing.getEvidence().stream().anyMatch(current ->
                    java.util.Objects.equals(current.getSourceType(), evidence.getSourceType())
                            && java.util.Objects.equals(current.getSourceRef(), evidence.getSourceRef()));
            if (!duplicate) {
                existing.getEvidence().add(evidence);
            }
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
