package com.sqlcli.graph.workspace;

import com.sqlcli.graph.ui.service.WorkspaceLockManager;
import com.sqlcli.graph.ui.service.WorkspaceMutationService;
import com.sqlcli.graph.ui.service.WriteLockGuard;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.io.IOException;

/**
 * 导入服务：从数据库提取元数据并合并到已有 workspace 中。
 *
 * 核心流程：
 * 1. 加载已有 workspace（existing），创建空的 incoming workspace
 * 2. 从数据库提取表和 FK 关系到 incoming workspace
 * 3. 每批次 flush 时调用 GraphWorkspaceMerger.merge(incoming, existing)
 * 4. 每批次提交合并后的当前工作区，保护范围外的数据
 *
 * 合并原则：系统字段从 DB 更新，用户字段 existing wins。
 */
public class WorkspaceImportService {
    private final GraphWorkspaceStore workspaceStore;
    private final WorkspaceImportJobStore jobStore;
    private final WorkspaceValidator validator;
    private final GraphWorkspaceMerger workspaceMerger;
    private final WorkspaceLockManager lockManager;
    private final WorkspaceMutationService mutationService;

    public WorkspaceImportService() {
        this(new GraphWorkspaceStore(), new WorkspaceImportJobStore(), new WorkspaceValidator(),
                new GraphWorkspaceMerger(), new WorkspaceLockManager());
    }

    public WorkspaceImportService(GraphWorkspaceStore workspaceStore, WorkspaceImportJobStore jobStore,
            WorkspaceValidator validator, GraphWorkspaceMerger workspaceMerger) {
        this(workspaceStore, jobStore, validator, workspaceMerger, new WorkspaceLockManager());
    }

    public WorkspaceImportService(GraphWorkspaceStore workspaceStore, WorkspaceImportJobStore jobStore,
            WorkspaceValidator validator, GraphWorkspaceMerger workspaceMerger, WorkspaceLockManager lockManager) {
        this.workspaceStore = workspaceStore;
        this.jobStore = jobStore;
        this.validator = validator;
        this.workspaceMerger = workspaceMerger;
        this.lockManager = lockManager;
        this.mutationService = new WorkspaceMutationService(workspaceStore, validator, lockManager);
    }

    public ImportJob start(String alias, WorkspaceMetadataProvider provider, ImportOptions options) throws Exception {
        try (WriteLockGuard ignored = lockManager.acquireWriteLock(
                alias, workspaceStore.workspacePath(alias), 30_000)) {
            return startLocked(alias, provider, options);
        }
    }

    private ImportJob startLocked(String alias, WorkspaceMetadataProvider provider, ImportOptions options) throws Exception {
        if (jobStore.hasCurrentJob(alias)) {
            ImportJob current = jobStore.loadCurrentJob(alias);
            if (current.getStatus() == ImportJobStatus.running || current.getStatus() == ImportJobStatus.pending
                    || current.getStatus() == ImportJobStatus.paused || current.getStatus() == ImportJobStatus.failed) {
                throw new IllegalStateException("已有未完成导入任务，请先执行 schema import status/resume/reset");
            }
        }

        boolean workspaceExists = workspaceStore.exists(alias);
        GraphWorkspace existing = loadOrCreateWorkspace(alias, provider.getDatabaseType());
        updateDataSource(existing, provider);
        ImportJob job = ImportJob.create(alias, existing.getTables().isEmpty() ? "init" : "refresh",
                provider.getDatabaseType(), normalizeOptions(options));
        job.getStats().setForeignKeysSkipped(!provider.supportsForeignKeys());
        job.setStatus(ImportJobStatus.running);
        job.setCurrentPhase(ImportPhase.discover_schemas);
        job.setMessage("Discovering schemas");

        List<String> schemas = filterSchemas(provider.discoverSchemas(), job.getOptions().getSchemaFilter(),
                job.getOptions().getTableFilter());
        job.setDiscoveredSchemas(new ArrayList<>(schemas));
        // 将 schema 节点添加到 existing（schema 只做 putIfAbsent，不覆盖）
        for (String schemaName : schemas) {
            SchemaWorkspaceNode schemaNode = SchemaWorkspaceNode.create(alias, schemaName, GraphActor.extractor);
            existing.getSchemas().putIfAbsent(schemaNode.getId(), schemaNode);
        }
        if (workspaceExists) {
            commitImport(alias, existing, "database import prepare");
        } else {
            validateForCommit(existing);
            workspaceStore.save(existing); // workspace initialization is the only direct store write.
        }

        List<ImportTask> tasks = buildTasks(job, provider, schemas);
        job.getStats().setTotalTasks(tasks.size());
        job.getStats().setTotalTables((int) tasks.stream().filter(task -> task.getType() == ImportTaskType.extract_table).count());
        job.touch();
        jobStore.saveTasks(alias, job.getJobId(), tasks);
        jobStore.saveCurrentJob(alias, job);
        return run(alias, provider, existing, job, tasks);
    }

    public ImportJob resume(String alias, WorkspaceMetadataProvider provider) throws Exception {
        try (WriteLockGuard ignored = lockManager.acquireWriteLock(
                alias, workspaceStore.workspacePath(alias), 30_000)) {
            return resumeLocked(alias, provider);
        }
    }

    private ImportJob resumeLocked(String alias, WorkspaceMetadataProvider provider) throws Exception {
        if (!jobStore.hasCurrentJob(alias)) {
            throw new IllegalStateException("没有可恢复的导入任务");
        }
        ImportJob job = jobStore.loadCurrentJob(alias);
        job.getStats().setForeignKeysSkipped(!provider.supportsForeignKeys());
        List<ImportTask> tasks = new ArrayList<>(jobStore.loadTasks(alias, job.getJobId()));
        if (tasks.isEmpty()) {
            throw new IllegalStateException("导入任务缺少 task 文件，无法继续");
        }
        GraphWorkspace existing = loadOrCreateWorkspace(alias, provider.getDatabaseType());
        updateDataSource(existing, provider);
        job.setStatus(ImportJobStatus.running);
        job.setMessage("Resuming import job");
        job.touch();
        jobStore.saveCurrentJob(alias, job);

        return run(alias, provider, existing, job, tasks);
    }

    public ImportJob status(String alias) throws IOException {
        if (!jobStore.hasCurrentJob(alias)) {
            return null;
        }
        return jobStore.loadCurrentJob(alias);
    }

    public void reset(String alias) throws IOException {
        jobStore.reset(alias);
    }

    private ImportJob run(String alias, WorkspaceMetadataProvider provider, GraphWorkspace existing,
            ImportJob job, List<ImportTask> tasks) throws Exception {
        int batchSize = Math.max(1, job.getOptions().getBatchSize());
        boolean forceOverwrite = job.getOptions().isForceOverwrite();
        int processedSinceFlush = 0;

        Set<String> batchScannedFkTableIds = new HashSet<>();
        Map<String, RelationWorkspaceEdge> batchRelations = new LinkedHashMap<>();
        GraphWorkspace foreignKeyContext = null;

        // incoming workspace：存放本批次的提取结果，用于合并
        GraphWorkspace incoming = GraphWorkspace.create(alias, provider.getDatabaseType());
        incoming.setDataSource(existing.getDataSource());

        for (ImportTask task : tasks) {
            if (task.getStatus() == ImportTaskStatus.completed || task.getStatus() == ImportTaskStatus.skipped) {
                continue;
            }

            task.setStatus(ImportTaskStatus.running);
            task.setAttempt(task.getAttempt() + 1);
            task.setStartedAt(java.time.LocalDateTime.now());
            updatePhase(job, task);
            job.setMessage("Running " + task.getType() + " for " + task.getSchema() + "." + task.getTable());
            job.touch();
            jobStore.saveTasks(alias, job.getJobId(), tasks);
            jobStore.saveCurrentJob(alias, job);

            try {
                if (task.getType() == ImportTaskType.extract_foreign_key
                        && tasks.stream().anyMatch(t -> t.getType() == ImportTaskType.extract_table
                                && t.getStatus() == ImportTaskStatus.failed)) {
                    // FK 解析依赖全部表就位：有表提取失败时，引用缺失表的 FK 会被静默跳过，
                    // 任务却记成 completed，resume 就再也不会重跑它——FK 永久丢失。
                    // 标记失败，让 resume 带着补全的表重跑本任务。
                    throw new SQLException("前置表提取存在失败，FK 提取延后到 resume 重跑");
                }
                if (!forceOverwrite && task.getType() == ImportTaskType.extract_foreign_key
                        && foreignKeyContext == null) {
                    foreignKeyContext = GraphWorkspace.create(alias, provider.getDatabaseType());
                    foreignKeyContext.getTables().putAll(existing.getTables());
                    foreignKeyContext.getTables().putAll(incoming.getTables());
                }
                runTask(provider, existing, incoming, foreignKeyContext, batchRelations,
                        task, batchScannedFkTableIds, forceOverwrite);
                if (task.getStatus() == ImportTaskStatus.running) {
                    task.setStatus(ImportTaskStatus.completed);
                    task.setErrorMessage(null);
                }
                task.setFinishedAt(java.time.LocalDateTime.now());
            } catch (SQLException | RuntimeException e) {
                task.setStatus(ImportTaskStatus.failed);
                task.setFinishedAt(java.time.LocalDateTime.now());
                task.setErrorMessage(e.getMessage());
            }

            recomputeStats(job, tasks);
            updateCheckpoint(job, task);
            processedSinceFlush++;

            if (processedSinceFlush >= batchSize) {
                flushProgress(alias, existing, incoming, batchRelations, job, tasks,
                        batchScannedFkTableIds, forceOverwrite);
                processedSinceFlush = 0;
                // 清空 incoming，准备下一批
                incoming.getTables().clear();
                incoming.getRelations().clear();
                batchRelations.clear();
                batchScannedFkTableIds.clear();
            }
        }

        // 最终 flush：只在尚有未提交的数据库变更时执行，避免空批次增加 revision。
        if (!incoming.getTables().isEmpty() || !batchRelations.isEmpty()
                || !batchScannedFkTableIds.isEmpty() || (forceOverwrite && processedSinceFlush > 0)) {
            flushProgress(alias, existing, incoming, batchRelations, job, tasks,
                    batchScannedFkTableIds, forceOverwrite);
        }

        boolean hasFailed = tasks.stream().anyMatch(task -> task.getStatus() == ImportTaskStatus.failed);
        boolean completed = tasks.stream().allMatch(task ->
                task.getStatus() == ImportTaskStatus.completed || task.getStatus() == ImportTaskStatus.skipped);
        if (completed) {
            int changesBefore = existing.getChanges().size();
            Set<String> discoveredTableIds = tasks.stream()
                    .filter(task -> task.getType() == ImportTaskType.extract_table
                            && task.getStatus() == ImportTaskStatus.completed)
                    .map(task -> GraphIds.tableId(alias, task.getSchema(), task.getTable()))
                    .collect(Collectors.toSet());
            Set<String> importedSchemas = new HashSet<>(job.getDiscoveredSchemas());
            if (importedSchemas.isEmpty()) {
                importedSchemas.addAll(tasks.stream()
                        .filter(task -> task.getType() == ImportTaskType.extract_table)
                        .map(ImportTask::getSchema)
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toSet()));
            }
            workspaceMerger.markMissingTables(existing, discoveredTableIds, importedSchemas,
                    job.getOptions().getTableFilter());
            validator.validate(existing);
            boolean validationFailed = existing.getValidationIssues().stream()
                    .anyMatch(issue -> issue.getSeverity() == ValidationSeverity.error);
            if (!validationFailed && existing.getChanges().size() > changesBefore) {
                commitImport(alias, existing, "database import finalize");
            }
            job.setCurrentPhase(ImportPhase.finalize);
            job.setStatus(hasFailed || validationFailed ? ImportJobStatus.failed : ImportJobStatus.completed);
            job.setFinishedAt(java.time.LocalDateTime.now());
            job.setMessage(hasFailed ? "Import finished with failed tasks"
                    : validationFailed ? "Import finished with validation errors" : "Import completed");
            job.touch();
            jobStore.saveCurrentJob(alias, job);
            jobStore.archiveCurrentJob(alias, job);
        } else {
            job.setStatus(ImportJobStatus.failed);
            job.setMessage("Import paused with unfinished tasks");
            job.touch();
            jobStore.saveCurrentJob(alias, job);
        }
        return job;
    }

    private void runTask(WorkspaceMetadataProvider provider, GraphWorkspace existing, GraphWorkspace incoming,
            GraphWorkspace foreignKeyContext, Map<String, RelationWorkspaceEdge> batchRelations,
            ImportTask task, Set<String> batchScannedFkTableIds, boolean forceOverwrite) throws SQLException {
        if (task.getType() == ImportTaskType.extract_table) {
            TableExtractResult result = provider.extractTable(task.getSchema(), task.getTable());
            if (result == null) {
                // 表不存在，跳过
                task.setStatus(ImportTaskStatus.skipped);
                task.setFinishedAt(java.time.LocalDateTime.now());
                task.setErrorMessage("Table not found in database");
                return;
            }

            if (forceOverwrite) {
                // 强制覆盖模式：直接替换 existing 中的表
                existing.getTables().put(result.table().getId(), result.table());
            } else {
                // 合并模式：将提取结果放入 incoming，后续合并
                incoming.getTables().put(result.table().getId(), result.table());
            }

            return;
        }

        // FK 提取任务
        String tableId = GraphIds.tableId(existing.getManifest().getAlias(), task.getSchema(), task.getTable());
        TableWorkspaceNode table;
        if (forceOverwrite) {
            table = existing.getTables().get(tableId);
        } else {
            // 从 incoming 中找表（可能有本批次刚提取的）
            table = incoming.getTables().get(tableId);
            if (table == null) {
                // 也可能在上个批次已合并到 existing 中
                table = existing.getTables().get(tableId);
            }
        }

        if (table == null) {
            task.setStatus(ImportTaskStatus.skipped);
            task.setFinishedAt(java.time.LocalDateTime.now());
            task.setErrorMessage("Table metadata not found, skipped foreign key extraction");
            return;
        }
        batchScannedFkTableIds.add(tableId);

        if (forceOverwrite) {
            // 强制覆盖模式：直接删除旧 FK 再添加新 FK
            final String fkAlias = table.getSourceAlias();
            final String fkPrefix = "column:" + fkAlias + ":" + table.getSchema() + "." + table.getName() + ".";
            existing.getRelations().removeIf(relation -> relation.getType() == RelationType.foreign_key
                    && relation.getFrom() != null && relation.getFrom().startsWith(fkPrefix));
            provider.extractForeignKeysForTable(table, existing);
        } else {
            foreignKeyContext.getRelations().clear();
            provider.extractForeignKeysForTable(table, foreignKeyContext);
            for (RelationWorkspaceEdge relation : foreignKeyContext.getRelations()) {
                batchRelations.put(relation.getId(), relation);
            }
        }
    }

    private void flushProgress(String alias, GraphWorkspace existing, GraphWorkspace incoming,
            Map<String, RelationWorkspaceEdge> batchRelations,
            ImportJob job, List<ImportTask> tasks,
            Set<String> scannedFkTableIds, boolean forceOverwrite) throws Exception {

        incoming.getRelations().clear();
        incoming.getRelations().addAll(batchRelations.values());
        // 合并 incoming → existing
        if (!forceOverwrite && (!incoming.getTables().isEmpty() || !incoming.getRelations().isEmpty()
                || !scannedFkTableIds.isEmpty())) {
            workspaceMerger.mergeBatch(incoming, existing, scannedFkTableIds);
        }

        commitImport(alias, existing, "database import batch");

        jobStore.saveTasks(alias, job.getJobId(), tasks);
        jobStore.saveCurrentJob(alias, job);
    }

    private List<ImportTask> buildTasks(ImportJob job, WorkspaceMetadataProvider provider, List<String> schemas) throws SQLException {
        List<ImportTask> tasks = new ArrayList<>();
        List<ImportTask> foreignKeyTasks = new ArrayList<>();
        for (String schemaName : schemas) {
            List<String> tables = provider.discoverTables(schemaName).stream()
                    .filter(table -> matchesTableFilter(schemaName, table, job.getOptions().getTableFilter()))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toList());
            for (String tableName : tables) {
                tasks.add(ImportTask.create(job.getJobId(), ImportTaskType.extract_table, schemaName, tableName));
            }
            if (provider.supportsForeignKeys()) {
                for (String tableName : tables) {
                    foreignKeyTasks.add(ImportTask.create(job.getJobId(), ImportTaskType.extract_foreign_key,
                            schemaName, tableName));
                }
            }
        }
        // FK 解析需要两端表均已进入 workspace，所有表任务必须先完成。
        tasks.addAll(foreignKeyTasks);
        return tasks;
    }

    private void commitImport(String alias, GraphWorkspace workspace, String reason) {
        WorkspaceMutationService.MutationResult result = mutationService.commitLocked(alias,
                workspace.getManifest().getRevision(), GraphActor.extractor, reason, workspace,
                new WorkspaceMutationService.MutationOutcome(workspace.getManifest().getId(), ChangeOperation.update));
        if (!result.isSuccess()) {
            throw new IllegalStateException(String.join("; ", result.getErrors()));
        }
    }

    private GraphWorkspace loadOrCreateWorkspace(String alias, String databaseType) throws IOException {
        if (!workspaceStore.exists(alias)) {
            return GraphWorkspace.create(alias, databaseType);
        }
        return workspaceStore.load(alias);
    }

    private void updateDataSource(GraphWorkspace workspace, WorkspaceMetadataProvider provider) throws SQLException {
        DataSourceNode dataSource = workspace.getDataSource();
        dataSource.setDbType(provider.getDatabaseType());
        dataSource.setProductName(provider.getProductName());
        dataSource.setProductVersion(provider.getProductVersion());
        dataSource.setIndexMetadataSupported(provider.supportsIndexes());
        dataSource.touch(GraphActor.extractor);
    }

    private void validateForCommit(GraphWorkspace workspace) {
        validator.validate(workspace);
        workspace.getValidationIssues().stream()
                .filter(issue -> issue.getSeverity() == ValidationSeverity.error)
                .findFirst()
                .ifPresent(issue -> {
                    throw new IllegalStateException(issue.getCode() + ": " + issue.getMessage());
                });
    }

    private ImportOptions normalizeOptions(ImportOptions options) {
        ImportOptions normalized = options == null ? new ImportOptions() : options;
        if (normalized.getBatchSize() <= 0) {
            normalized.setBatchSize(20);
        }
        return normalized;
    }

    private List<String> filterSchemas(List<String> schemas, String schemaFilter, String tableFilter) {
        String effectiveSchema = schemaFilter;
        if ((effectiveSchema == null || effectiveSchema.isBlank()) && tableFilter != null && tableFilter.contains(".")) {
            effectiveSchema = tableFilter.substring(0, tableFilter.indexOf('.'));
        }
        if (effectiveSchema == null || effectiveSchema.isBlank()) {
            return schemas.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        }
        String expected = effectiveSchema.toLowerCase(Locale.ROOT);
        return schemas.stream()
                .filter(schema -> schema != null && schema.toLowerCase(Locale.ROOT).equals(expected))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private boolean matchesTableFilter(String schemaName, String tableName, String tableFilter) {
        if (tableFilter == null || tableFilter.isBlank()) {
            return true;
        }
        String normalized = tableFilter.toLowerCase(Locale.ROOT);
        String qualified = (schemaName + "." + tableName).toLowerCase(Locale.ROOT);
        return normalized.equals(qualified) || normalized.equals(tableName.toLowerCase(Locale.ROOT));
    }

    private void recomputeStats(ImportJob job, List<ImportTask> tasks) {
        ImportStats stats = job.getStats();
        stats.setCompletedTasks((int) tasks.stream().filter(task -> task.getStatus() == ImportTaskStatus.completed).count());
        stats.setFailedTasks((int) tasks.stream().filter(task -> task.getStatus() == ImportTaskStatus.failed).count());
        stats.setCompletedTables((int) tasks.stream()
                .filter(task -> task.getType() == ImportTaskType.extract_table && task.getStatus() == ImportTaskStatus.completed)
                .count());
        stats.setFailedTables((int) tasks.stream()
                .filter(task -> task.getType() == ImportTaskType.extract_table && task.getStatus() == ImportTaskStatus.failed)
                .count());
    }

    private void updateCheckpoint(ImportJob job, ImportTask task) {
        ImportCheckpoint checkpoint = job.getCheckpoint();
        checkpoint.setPhase(task.getType() == ImportTaskType.extract_foreign_key ? ImportPhase.relations : ImportPhase.tables);
        checkpoint.setSchemaCursor(task.getSchema());
        checkpoint.setTableCursor(task.getTable());
        checkpoint.setLastCompletedTaskId(task.getTaskId());
        checkpoint.setCompletedTaskCount(job.getStats().getCompletedTasks());
        checkpoint.setFailedTaskCount(job.getStats().getFailedTasks());
        checkpoint.setBatchNo(checkpoint.getBatchNo() + 1);
        job.setCurrentPhase(checkpoint.getPhase());
        job.touch();
    }

    private void updatePhase(ImportJob job, ImportTask task) {
        job.setCurrentPhase(task.getType() == ImportTaskType.extract_foreign_key ? ImportPhase.relations : ImportPhase.tables);
    }
}
