package com.sqlcli.graph.ui.api;

import com.sqlcli.graph.workspace.*;
import com.sqlcli.graph.workspace.index.WorkspaceIndexSnapshot;
import com.sqlcli.graph.workspace.index.WorkspaceIndexStore;
import com.sqlcli.graph.workspace.index.WorkspaceIndexedSearchEngine;
import com.sqlcli.graph.ui.GraphUiSession;
import com.sqlcli.graph.ui.JsonHttpSupport;
import com.sqlcli.graph.ui.dto.*;
import com.sqlcli.graph.ui.service.GraphViewService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST API controller for workspace queries.
 * <ul>
 *   <li>GET /api/session - session info</li>
 *   <li>GET /api/workspace - workspace summary</li>
 *   <li>GET /api/workspace/stats - statistics</li>
 *   <li>GET /api/workspace/completeness - metadata coverage / backlog / relation health</li>
 *   <li>GET /api/schemas - schema list</li>
 *   <li>GET /api/tables - table catalog (paginated)</li>
 *   <li>GET /api/tables/{tableId} - table detail</li>
 *   <li>GET /api/search - search</li>
 *   <li>GET /api/graph - graph view</li>
 *   <li>GET /api/lineage - single-column lineage, multi-hop</li>
 * </ul>
 */
public class WorkspaceQueryController implements HttpHandler {

    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceQueryController.class);

    private volatile GraphWorkspace workspace;
    private final GraphUiSession session;
    private final JsonHttpSupport json;
    private final WorkspaceIndexStore indexStore;
    private final GraphWorkspaceStore workspaceStore;
    private WorkspaceMutationController mutationController;

    public WorkspaceQueryController(GraphWorkspace workspace, GraphUiSession session,
                                    JsonHttpSupport json, WorkspaceIndexStore indexStore) {
        this(workspace, session, json, indexStore, null);
    }

    public WorkspaceQueryController(GraphWorkspace workspace, GraphUiSession session,
                                    JsonHttpSupport json, WorkspaceIndexStore indexStore,
                                    GraphWorkspaceStore workspaceStore) {
        this.workspace = workspace;
        this.session = session;
        this.json = json;
        this.indexStore = indexStore;
        this.workspaceStore = workspaceStore;
    }

    public void setMutationController(WorkspaceMutationController mutationController) {
        this.mutationController = mutationController;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        // Delegate PATCH requests on /api/tables to mutation controller
        if ("PATCH".equals(method) && path.startsWith("/api/tables/") && mutationController != null) {
            mutationController.handle(exchange);
            return;
        }

        if (!"GET".equals(method)) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        try {
            refreshWorkspace();
            if ("/api/session".equals(path)) {
                handleSession(exchange);
            } else if ("/api/workspace".equals(path)) {
                handleWorkspace(exchange);
            } else if ("/api/workspace/stats".equals(path)) {
                handleWorkspaceStats(exchange);
            } else if ("/api/schemas".equals(path)) {
                handleSchemas(exchange);
            } else if ("/api/tables".equals(path)) {
                handleTables(exchange);
            } else if (path.startsWith("/api/tables/")) {
                handleTableDetail(exchange, path);
            } else if ("/api/terms".equals(path)) {
                handleTerms(exchange);
            } else if ("/api/search".equals(path)) {
                handleSearch(exchange);
            } else if ("/api/graph".equals(path)) {
                handleGraph(exchange);
            } else if ("/api/lineage/overview".equals(path)) {
                handleLineageOverview(exchange);
            } else if ("/api/lineage/graph".equals(path)) {
                handleLineageNetworkGraph(exchange);
            } else if ("/api/lineage".equals(path)) {
                handleLineage(exchange);
            } else if ("/api/workspace/completeness".equals(path)) {
                handleWorkspaceCompleteness(exchange);
            } else {
                json.writeNotFound(exchange, "API endpoint not found: " + path);
            }
        } catch (Exception e) {
            if (JsonHttpSupport.isClientDisconnect(e)) {
                LOG.debug("Client disconnected: {}", path);
            } else {
                LOG.error("Graph UI API error: {} {}", method, path, e);
                try {
                    json.writeError(exchange, e);
                } catch (IOException ignored) {
                    // Connection already closed, cannot send error response
                }
            }
        }
    }

    private void refreshWorkspace() throws IOException {
        if (workspaceStore != null) {
            workspace = workspaceStore.load(session.getAlias());
            session.setWorkspaceRevision(workspace.getManifest().getRevision());
        }
    }

    // --- GET /api/session ---

    private void handleSession(HttpExchange exchange) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("alias", session.getAlias());
        result.put("token", session.getSessionToken());
        result.put("revision", workspace.getManifest().getRevision());
        // readOnly = 数据库只读（别名配置）。图谱工作区编辑不受它影响，
        // 所以 write 能力恒在——它说的是图谱写，不是 SQL 写。
        result.put("readOnly", session.isReadOnly());
        result.put("capabilities", List.of("read", "write"));
        json.writeOk(exchange, result);
    }

    // --- GET /api/workspace ---

    private void handleWorkspace(HttpExchange exchange) throws IOException {
        WorkspaceSummaryDto dto = new WorkspaceSummaryDto();
        dto.setAlias(workspace.getManifest().getAlias());
        dto.setRevision(workspace.getManifest().getRevision());
        dto.setModelVersion(workspace.getManifest().getModelVersion());
        dto.setStorageVersion(workspace.getManifest().getStorageVersion());

        WorkspaceSummaryDto.StatsDto statsDto = new WorkspaceSummaryDto.StatsDto();
        WorkspaceStats stats = workspace.getManifest().getStats();
        statsDto.setSchemas(stats.getSchemas());
        statsDto.setTables(stats.getTables());
        statsDto.setColumns(stats.getColumns());
        statsDto.setRelations(stats.getRelations());
        statsDto.setValidationIssues(stats.getValidationIssues());
        dto.setStats(statsDto);

        json.writeOk(exchange, dto);
    }

    // --- GET /api/workspace/stats ---

    private void handleWorkspaceStats(HttpExchange exchange) throws IOException {
        WorkspaceStats stats = workspace.getManifest().getStats();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemas", stats.getSchemas());
        result.put("tables", stats.getTables());
        result.put("columns", stats.getColumns());
        result.put("relations", stats.getRelations());
        result.put("validationIssues", stats.getValidationIssues());
        result.put("terms", workspace.getTerms().size());
        json.writeOk(exchange, result);
    }

    // --- GET /api/workspace/completeness ---

    /**
     * 图谱自身的完整性：覆盖率 + 待办量 + 关系健康，不碰用户的业务数据。
     * 算的是这份元数据本身齐不齐，跟 {@link #handleWorkspaceStats} 报的规模数字是两件事。
     */
    private void handleWorkspaceCompleteness(HttpExchange exchange) throws IOException {
        WorkspaceCompletenessDto dto = new WorkspaceCompletenessDto();

        WorkspaceCompletenessDto.TableCoverage tableCoverage = new WorkspaceCompletenessDto.TableCoverage();
        WorkspaceCompletenessDto.ColumnCoverage columnCoverage = new WorkspaceCompletenessDto.ColumnCoverage();
        WorkspaceCompletenessDto.Backlog backlog = new WorkspaceCompletenessDto.Backlog();
        WorkspaceCompletenessDto.RelationHealth relationHealth = new WorkspaceCompletenessDto.RelationHealth();

        Map<String, Integer> degree = new HashMap<>();
        Map<String, Boolean> hasForeignKey = new HashMap<>();
        Map<String, Boolean> hasJoinObserved = new HashMap<>();

        tableCoverage.setTotal(workspace.getTables().size());
        for (TableWorkspaceNode table : workspace.getTables().values()) {
            if (table.getComment() != null && !table.getComment().isBlank()) {
                tableCoverage.setWithComment(tableCoverage.getWithComment() + 1);
            }
            if (table.getBusinessName() != null && !table.getBusinessName().isBlank()) {
                tableCoverage.setWithBusinessName(tableCoverage.getWithBusinessName() + 1);
            }
            if (table.getStatus() == GraphStatus.candidate) {
                backlog.setCandidateTables(backlog.getCandidateTables() + 1);
            } else if (table.getStatus() == GraphStatus.ignored) {
                backlog.setIgnoredTables(backlog.getIgnoredTables() + 1);
            }

            for (ColumnWorkspaceNode column : table.getColumns()) {
                columnCoverage.setTotal(columnCoverage.getTotal() + 1);
                if (column.getComment() != null && !column.getComment().isBlank()) {
                    columnCoverage.setWithComment(columnCoverage.getWithComment() + 1);
                }
                if (column.getBusinessName() != null && !column.getBusinessName().isBlank()) {
                    columnCoverage.setWithBusinessName(columnCoverage.getWithBusinessName() + 1);
                }
                if (column.getSemanticType() != null) {
                    columnCoverage.setWithSemanticType(columnCoverage.getWithSemanticType() + 1);
                }
                if (column.getValueHints() != null && column.getValueHints().hasData()) {
                    columnCoverage.setWithValueHints(columnCoverage.getWithValueHints() + 1);
                }
                if (column.hasUnconfirmedSemantics()) {
                    backlog.setUnverifiedColumnSemantics(backlog.getUnverifiedColumnSemantics() + 1);
                }
            }
        }

        for (RelationWorkspaceEdge relation : workspace.getRelations()) {
            if (relation.getType() == RelationType.term_mapping) {
                if (relation.getStatus() == GraphStatus.candidate) {
                    backlog.setCandidateRelations(backlog.getCandidateRelations() + 1);
                } else if (relation.getStatus() == GraphStatus.ignored) {
                    backlog.setIgnoredRelations(backlog.getIgnoredRelations() + 1);
                }
                continue; // 结构健康只看表间关系，术语映射不计入孤立表/degree
            }
            if (relation.getStatus() == GraphStatus.candidate) {
                backlog.setCandidateRelations(backlog.getCandidateRelations() + 1);
            } else if (relation.getStatus() == GraphStatus.ignored) {
                backlog.setIgnoredRelations(backlog.getIgnoredRelations() + 1);
                continue; // 已拒绝的关系不计入关系健康统计
            }
            String fromTableId = resolveTableId(relation.getFrom());
            String toTableId = resolveTableId(relation.getTo());
            if (fromTableId != null) {
                degree.merge(fromTableId, 1, Integer::sum);
                if (relation.getType() == RelationType.foreign_key) hasForeignKey.put(fromTableId, true);
                if (relation.getType() == RelationType.join_observed) hasJoinObserved.put(fromTableId, true);
            }
            if (toTableId != null) {
                degree.merge(toTableId, 1, Integer::sum);
                if (relation.getType() == RelationType.foreign_key) hasForeignKey.put(toTableId, true);
                if (relation.getType() == RelationType.join_observed) hasJoinObserved.put(toTableId, true);
            }
        }

        for (TermWorkspaceNode term : workspace.getTerms().values()) {
            if (term.getStatus() == GraphStatus.candidate) {
                backlog.setCandidateTerms(backlog.getCandidateTerms() + 1);
            } else if (term.getStatus() == GraphStatus.ignored) {
                backlog.setIgnoredTerms(backlog.getIgnoredTerms() + 1);
            }
        }

        for (ValidationIssueRecord issue : workspace.getValidationIssues()) {
            if (issue.getStatus() == ValidationIssueStatus.ignored) {
                backlog.setIgnoredIssues(backlog.getIgnoredIssues() + 1);
            } else if (issue.getStatus() == ValidationIssueStatus.open && issue.getSeverity() != null) {
                backlog.getOpenIssuesBySeverity().merge(issue.getSeverity().name(), 1, Integer::sum);
            }
        }

        relationHealth.setTotalTables(workspace.getTables().size());
        for (String tableId : workspace.getTables().keySet()) {
            if (degree.getOrDefault(tableId, 0) == 0) {
                relationHealth.setIsolatedTables(relationHealth.getIsolatedTables() + 1);
            } else if (Boolean.TRUE.equals(hasForeignKey.get(tableId)) && !Boolean.TRUE.equals(hasJoinObserved.get(tableId))) {
                relationHealth.setFkOnlyTables(relationHealth.getFkOnlyTables() + 1);
            }
        }

        dto.setTables(tableCoverage);
        dto.setColumns(columnCoverage);
        dto.setBacklog(backlog);
        dto.setRelations(relationHealth);
        json.writeOk(exchange, dto);
    }

    // --- GET /api/terms ---

    /**
     * 业务术语列表（只读）。映射目标从 term_mapping 关系反查：
     * from 是术语、to 是表或字段——add-term --map 就是这样写进去的。
     *
     * <p>跳过 {@link GraphStatus#ignored} 的映射：拒绝候选关系不再删边（P1 第 6 条），
     * 一条被拒绝的 term_mapping 如果继续算进反查表，术语页会显示一个人已经明确否掉的映射，
     * 且没有任何标记——比拒绝前删边时更容易误导人。
     */
    private void handleTerms(HttpExchange exchange) throws IOException {
        Map<String, List<String>> mappings = new LinkedHashMap<>();
        for (RelationWorkspaceEdge relation : workspace.getRelations()) {
            if (relation.getType() == RelationType.term_mapping && relation.getStatus() != GraphStatus.ignored) {
                mappings.computeIfAbsent(relation.getFrom(), from -> new ArrayList<>())
                        .add(relation.getTo());
            }
        }
        List<Map<String, Object>> terms = new ArrayList<>();
        for (TermWorkspaceNode term : workspace.getTerms().values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", term.getId());
            item.put("name", term.getName());
            item.put("displayName", term.getDisplayName());
            item.put("aliases", term.getAliases());
            item.put("negativeAliases", term.getNegativeAliases());
            item.put("description", term.getDescription());
            item.put("status", term.getStatus() == null ? null : term.getStatus().name());
            item.put("confidence", term.getConfidence());
            item.put("mappedTargets", mappings.getOrDefault(term.getId(), List.of()));
            // 场景三件套：入口表、场景专属过滤、子图里的表。UI 靠 scenarioTables 把
            // 表目录筛到这个场景——术语在界面上不是第二份目录，是图谱的一个筛选维度。
            item.put("primaryTarget", term.getPrimaryTarget());
            item.put("filters", term.getFilters());
            TermScenario scenario = TermScenario.of(workspace, term);
            item.put("scenarioTables", scenario == null ? List.of()
                    : scenario.tables().stream().map(TermScenario.ScenarioTable::qualifiedName).toList());
            item.put("bridgeTables", scenario == null ? List.of()
                    : scenario.tables().stream()
                            .filter(table -> table.role() == TermScenario.Role.bridge)
                            .map(TermScenario.ScenarioTable::qualifiedName).toList());
            terms.add(item);
        }
        terms.sort(Comparator.comparing(item -> String.valueOf(item.get("name")), String.CASE_INSENSITIVE_ORDER));
        json.writeOk(exchange, Map.of("terms", terms, "total", terms.size()));
    }

    // --- GET /api/lineage/overview ---

    /**
     * 本库全部血缘记录的扁平清单，回答「这个库里哪些字段是推导来的」。
     *
     * <p>跟 {@link #handleLineage} 分工明确：那个是**一个字段**的多跳图（要选表和列才能问），
     * 这个是**全库**的目录。没有它，「有哪些血缘」在 UI 里根本问不出来——只能一张表一张表
     * 点过去看每个字段的血缘按钮。
     *
     * <p>这不是当初排除掉的「全库血缘图」：那指的是把所有节点画到一张画布上（另一个量级），
     * 这里只是列表，规模等于 {@code lineage.jsonl} 的行数（通常几十到几百条）。
     */
    private void handleLineageOverview(HttpExchange exchange) throws IOException {
        List<Map<String, Object>> items = new ArrayList<>();
        for (LineageRecord record : workspace.getLineage().values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", record.getId());
            item.put("target", record.getTarget());
            item.put("targetLabel", columnLabel(record.getTarget()));
            item.put("sources", record.getSources());
            item.put("sourceLabels", record.getSources().stream().map(
                    WorkspaceQueryController::columnLabel).toList());
            item.put("expression", record.getExpression());
            item.put("through", record.getThrough());
            item.put("lineageKind", record.getLineageKind() == null ? null : record.getLineageKind().name());
            item.put("updatedAt", record.getUpdatedAt());
            item.put("status", record.getStatus() == null ? null : record.getStatus().name());
            item.put("confidence", record.getConfidence());
            items.add(item);
        }
        // 按目标字段排序：同一张表的推导字段挨在一起，人扫列表时才成组
        items.sort(Comparator.comparing(item -> String.valueOf(item.get("targetLabel")),
                String.CASE_INSENSITIVE_ORDER));
        json.writeOk(exchange, Map.of("lineage", items, "total", items.size()));
    }

    /** `column:alias:SCHEMA.TABLE.COL` → `SCHEMA.TABLE.COL`；认不出就原样返回。 */
    private static String columnLabel(String columnId) {
        if (columnId == null) return "";
        String[] parts = columnId.split(":", 3);
        return parts.length == 3 ? parts[2] : columnId;
    }

    // --- GET /api/schemas ---

    private void handleSchemas(HttpExchange exchange) throws IOException {
        List<Map<String, Object>> schemas = new ArrayList<>();
        for (SchemaWorkspaceNode schema : workspace.getSchemas().values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", schema.getId());
            item.put("name", schema.getName());
            item.put("displayName", schema.getDisplayName());
            item.put("description", schema.getDescription());
            // Count tables in this schema
            long tableCount = workspace.getTables().values().stream()
                    .filter(t -> schema.getName().equalsIgnoreCase(t.getSchema()))
                    .count();
            item.put("tableCount", tableCount);
            schemas.add(item);
        }
        json.writeOk(exchange, schemas);
    }

    // --- GET /api/tables?schema=&q=&offset=0&limit=100 ---

    private void handleTables(HttpExchange exchange) throws IOException {
        Map<String, String> params = json.parseQueryParams(exchange);
        String schemaFilter = params.get("schema");
        String query = params.get("q");
        int offset = json.getIntParam(params, "offset", 0);
        int limit = json.getIntParam(params, "limit", 100);
        limit = Math.min(limit, 500); // cap at 500

        List<TableWorkspaceNode> tables = new ArrayList<>(workspace.getTables().values());

        // Filter by schema
        if (schemaFilter != null && !schemaFilter.isBlank()) {
            tables = tables.stream()
                    .filter(t -> schemaFilter.equalsIgnoreCase(t.getSchema()))
                    .collect(Collectors.toList());
        }

        // Filter by query (name, comment or description)
        if (query != null && !query.isBlank()) {
            String lowerQuery = query.toLowerCase(Locale.ROOT);
            tables = tables.stream()
                    .filter(t -> {
                        if (t.getName() != null && t.getName().toLowerCase().contains(lowerQuery)) return true;
                        if (t.getQualifiedName() != null && t.getQualifiedName().toLowerCase().contains(lowerQuery)) return true;
                        if (t.getComment() != null && t.getComment().toLowerCase().contains(lowerQuery)) return true;
                        if (t.getDescription() != null && t.getDescription().toLowerCase().contains(lowerQuery)) return true;
                        if (t.getBusinessName() != null && t.getBusinessName().toLowerCase().contains(lowerQuery)) return true;
                        return false;
                    })
                    .collect(Collectors.toList());
        }

        // Sort by schema then name
        tables.sort(Comparator
                .comparing(TableWorkspaceNode::getSchema, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(TableWorkspaceNode::getName, String.CASE_INSENSITIVE_ORDER));

        int total = tables.size();
        int end = Math.min(offset + limit, total);
        List<TableWorkspaceNode> page = offset < total ? tables.subList(offset, end) : List.of();

        // Build lightweight response
        List<Map<String, Object>> items = new ArrayList<>();
        for (TableWorkspaceNode table : page) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", table.getId());
            item.put("schema", table.getSchema());
            item.put("name", table.getName());
            item.put("qualifiedName", table.getQualifiedName());
            item.put("comment", table.getComment());
            item.put("description", table.getDescription());
            item.put("tableType", table.getTableType() != null ? table.getTableType().name() : null);
            item.put("columnCount", table.getColumns().size());
            item.put("tags", table.getTags());
            items.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("offset", offset);
        result.put("limit", limit);
        result.put("items", items);
        json.writeOk(exchange, result);
    }

    // --- GET /api/tables/{tableId} ---

    private void handleTableDetail(HttpExchange exchange, String path) throws IOException {
        // Extract tableId from path: /api/tables/{tableId}
        // tableId may be URL-encoded qualified name like schema.table
        String encoded = path.substring("/api/tables/".length());
        String tableRef;
        try {
            tableRef = java.net.URLDecoder.decode(encoded, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            tableRef = encoded;
        }

        TableWorkspaceNode table = resolveTableRef(tableRef);

        if (table == null) {
            json.writeNotFound(exchange, "Table not found: " + tableRef);
            return;
        }

        final TableWorkspaceNode finalTable = table;
        TableDetailDto dto = new TableDetailDto();

        // Table info
        TableDetailDto.TableInfo info = new TableDetailDto.TableInfo();
        info.setId(finalTable.getId());
        info.setSchema(finalTable.getSchema());
        info.setName(finalTable.getName());
        info.setQualifiedName(finalTable.getQualifiedName());
        info.setBusinessName(finalTable.getBusinessName());
        info.setComment(finalTable.getComment());
        info.setDescription(finalTable.getDescription());
        info.setTableType(finalTable.getTableType() != null ? finalTable.getTableType().name() : null);
        info.setTags(finalTable.getTags());
        info.setPrimaryKey(finalTable.getPrimaryKey());
        info.setIndexes(finalTable.getIndexes());
        info.setRowEstimate(finalTable.getRowEstimate());
        dto.setTable(info);

        // Columns (sorted by ordinal)
        List<ColumnWorkspaceNode> columns = new ArrayList<>(finalTable.getColumns());
        columns.sort(Comparator.comparing(ColumnWorkspaceNode::getOrdinal, Comparator.nullsLast(Integer::compareTo)));
        dto.setColumns(columns);

        // In/out edges
        List<RelationWorkspaceEdge> inEdges = new ArrayList<>();
        List<RelationWorkspaceEdge> outEdges = new ArrayList<>();
        for (RelationWorkspaceEdge rel : workspace.getRelations()) {
            String fromTableId = resolveTableId(rel.getFrom());
            String toTableId = resolveTableId(rel.getTo());
            if (finalTable.getId().equals(toTableId) && fromTableId != null) {
                inEdges.add(rel);
            }
            if (finalTable.getId().equals(fromTableId) && toTableId != null) {
                outEdges.add(rel);
            }
        }
        dto.setInEdges(inEdges);
        dto.setOutEdges(outEdges);

        // Validation issues for this table
        List<ValidationIssueRecord> issues = workspace.getValidationIssues().stream()
                .filter(v -> finalTable.getId().equals(v.getTargetId()))
                .collect(Collectors.toList());
        dto.setValidationIssues(issues);

        // Recent changes for this table
        List<TableDetailDto.ChangeInfo> recentChanges = new ArrayList<>();
        List<ChangeRecord> changes = workspace.getChanges();
        // Take last 10 changes related to this table
        for (int i = changes.size() - 1; i >= 0 && recentChanges.size() < 10; i--) {
            ChangeRecord change = changes.get(i);
            if (finalTable.getId().equals(change.getTargetId())) {
                TableDetailDto.ChangeInfo ci = new TableDetailDto.ChangeInfo();
                ci.setId(change.getId());
                ci.setOperation(change.getOperation() != null ? change.getOperation().name() : null);
                ci.setTargetId(change.getTargetId());
                ci.setActor(change.getActor() != null ? change.getActor().name() : null);
                ci.setTimestamp(change.getCreatedAt() != null ? change.getCreatedAt().toString() : null);
                recentChanges.add(ci);
            }
        }
        dto.setRecentChanges(recentChanges);

        dto.setLineage(buildColumnLineage(finalTable, columns));

        json.writeOk(exchange, dto);
    }

    /**
     * 列级血缘，一跳（直接上下游）。复用 CLI `schema lineage` 同一套 {@link LineageGraph} 遍历，
     * 不再深挖多跳——单表列多时血缘量会炸，多跳交给用户点进具体列再查。
     */
    private Map<String, TableDetailDto.ColumnLineage> buildColumnLineage(
            TableWorkspaceNode table, List<ColumnWorkspaceNode> columns) {
        LineageGraph graph = new LineageGraph(workspace.getLineage().values());
        String tableAlias = workspace.getManifest().getAlias();
        Map<String, TableDetailDto.ColumnLineage> result = new LinkedHashMap<>();
        for (ColumnWorkspaceNode column : columns) {
            String columnId = column.computeId(tableAlias, table.getSchema(), table.getName());
            List<LineageRecord> upRecords = graph.upstream(columnId, 1);
            List<LineageRecord> downRecords = graph.downstream(columnId, 1);
            if (upRecords.isEmpty() && downRecords.isEmpty()) {
                continue;
            }
            TableDetailDto.ColumnLineage lineage = new TableDetailDto.ColumnLineage();
            for (LineageRecord record : upRecords) {
                for (String sourceId : record.getSources()) {
                    lineage.getUpstream().add(toLineageEndpoint(sourceId, record));
                }
            }
            for (LineageRecord record : downRecords) {
                lineage.getDownstream().add(toLineageEndpoint(record.getTarget(), record));
            }
            result.put(column.getName(), lineage);
        }
        return result;
    }

    private TableDetailDto.LineageEndpoint toLineageEndpoint(String columnId, LineageRecord record) {
        TableDetailDto.LineageEndpoint endpoint = new TableDetailDto.LineageEndpoint();
        String[] parts = parseColumnRef(columnId);
        endpoint.setSchema(parts[0]);
        endpoint.setTable(parts[1]);
        endpoint.setColumn(parts[2]);
        endpoint.setExpression(record.getExpression());
        endpoint.setThrough(record.getThrough());
        endpoint.setLineageKind(record.getLineageKind() == null ? null : record.getLineageKind().name());
        return endpoint;
    }

    /** 按 qualifiedName / id 精确匹配解析表引用，表详情和血缘图两个入口共用。 */
    private TableWorkspaceNode resolveTableRef(String tableRef) {
        TableWorkspaceNode table = workspace.getTableByQualifiedName(tableRef);
        if (table == null) {
            table = workspace.getTables().get(tableRef);
        }
        if (table == null) {
            for (TableWorkspaceNode t : workspace.getTables().values()) {
                if (t.getId().equals(tableRef)) {
                    return t;
                }
            }
        }
        return table;
    }

    // --- GET /api/search?q=&type=table,column&limit=30 ---

    private void handleSearch(HttpExchange exchange) throws IOException {
        Map<String, String> params = json.parseQueryParams(exchange);
        String query = params.get("q");
        // 默认必须带上 term。漏掉它的后果实测过：图谱里明明有「扫码」这条术语、
        // CLI 搜得到、/api/terms 也返回，但顶栏搜索框一条术语都不出——
        // 引擎返回了，这里过滤掉了。而「用业务词找到对的表」正是术语存在的全部理由。
        String typeFilter = json.getParam(params, "type", "table,column,term");
        int limit = json.getIntParam(params, "limit", 30);
        limit = Math.min(limit, 100);

        if (query == null || query.isBlank()) {
            json.writeOk(exchange, Map.of("results", List.of(), "total", 0));
            return;
        }

        // Try indexed search first
        if (indexStore != null && indexStore.exists(session.getAlias())) {
            try {
                WorkspaceIndexSnapshot snapshot = indexStore.load(session.getAlias());
                if (snapshot != null) {
                    List<WorkspaceIndexedSearchEngine.SearchHit> hits =
                            new WorkspaceIndexedSearchEngine(snapshot).search(query);

                    // Filter by type
                    Set<String> allowedTypes = Arrays.stream(typeFilter.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .collect(Collectors.toSet());

                    List<WorkspaceIndexedSearchEngine.SearchHit> filtered = hits.stream()
                            .filter(h -> allowedTypes.isEmpty() || allowedTypes.contains(h.getType()))
                            .limit(limit)
                            .collect(Collectors.toList());

                    List<Map<String, Object>> results = new ArrayList<>();
                    for (WorkspaceIndexedSearchEngine.SearchHit hit : filtered) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("id", hit.getId());
                        item.put("type", hit.getType());
                        String[] target = "term".equals(hit.getType())
                                ? termTarget(hit.getId())
                                : new String[]{hit.getSchema(), hit.getTable(), hit.getColumn()};
                        item.put("schema", target[0]);
                        item.put("table", target[1]);
                        if (target[2] != null) item.put("column", target[2]);
                        item.put("name", hit.getName());
                        item.put("score", hit.getScore());
                        // 前端契约字段名保持 matchField，不跟着引擎改名
                        item.put("matchField", hit.getMatchedField());
                        // CLI 侧 2026-08 已带出的字段，UI 补齐对等
                        item.put("comment", hit.getComment());
                        item.put("description", hit.getDescription());
                        item.put("businessName", hit.getBusinessName());
                        item.put("semanticType", hit.getSemanticType());
                        item.put("candidate", hit.isCandidate());
                        results.add(item);
                    }

                    json.writeOk(exchange, Map.of(
                            "results", results,
                            "total", results.size(),
                            "indexStatus", "ready"
                    ));
                    return;
                }
            } catch (Exception e) {
                LOG.debug("Index search failed, falling back to name search", e);
            }
        }

        // Fallback：复用 CLI 同款实时引擎（bigram 切词 + 跨字段打分），不再维护一套独立的 substring 匹配
        Set<String> allowedTypes = Arrays.stream(typeFilter.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        List<Map<String, Object>> results = new ArrayList<>();
        for (WorkspaceSearchEngine.SearchResult hit : new WorkspaceSearchEngine(workspace).search(query)) {
            if (results.size() >= limit) break;
            if (!allowedTypes.isEmpty() && !allowedTypes.contains(hit.getType())) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", hit.getId());
            item.put("type", hit.getType());
            String[] target = "term".equals(hit.getType())
                    ? termTarget(hit.getId())
                    : new String[]{hit.getSchema(), hit.getTableName(), hit.getColumnName()};
            item.put("schema", target[0]);
            item.put("table", target[1]);
            if (target[2] != null) item.put("column", target[2]);
            // 术语没有 schema.table，名字只能从 getTitle() 取（它对这类回落到展示名）。
            // 只看列名 / 表名的话术语命中会带着 name=null 出去，前端渲染成一行空白。
            item.put("name", hit.getColumnName() != null ? hit.getColumnName()
                    : hit.getTableName() != null ? hit.getTableName() : hit.getTitle());
            item.put("score", hit.getScore());
            item.put("matchField", hit.getMatchedField());
            item.put("comment", hit.getComment());
            item.put("description", hit.getDescription());
            item.put("businessName", hit.getBusinessName());
            item.put("semanticType", hit.getSemanticType());
            item.put("candidate", hit.isCandidate());
            results.add(item);
        }

        json.writeOk(exchange, Map.of(
                "results", results,
                "total", results.size(),
                "indexStatus", "missing"
        ));
    }

    // --- GET /api/graph?schema=&table=&depth=1&relationType=&includeIsolated=false&maxNodes=500&maxEdges=1000 ---

    private void handleGraph(HttpExchange exchange) throws IOException {
        Map<String, String> params = json.parseQueryParams(exchange);
        String schema = params.get("schema");
        String table = params.get("table");
        int depth = json.getIntParam(params, "depth", 1);
        String relationType = params.get("relationType");
        boolean includeIsolated = "true".equalsIgnoreCase(params.getOrDefault("includeIsolated", "false"));
        int maxNodes = json.getIntParam(params, "maxNodes", 500);
        int maxEdges = json.getIntParam(params, "maxEdges", 1000);

        GraphViewService service = new GraphViewService(workspace);
        GraphViewDto dto = service.buildView(schema, table, depth,
                relationType, includeIsolated, maxNodes, maxEdges);
        json.writeOk(exchange, dto);
    }

    // --- GET /api/lineage/graph ---

    /**
     * 全库血缘大图：全部列、全部边、全部记录，外加每张表的总列数。
     *
     * <p>08 方向文档把「血缘可视化大图」划在元数据平台那条赛道外，2026-09-08 用户拍板要做：
     * 按记录 / 按表列出来的血缘会把一条长链切成几段各画一遍，只有整张画布才能顺着节点走。
     * 布局在前端（dagre），这里不算层次。记在 dev-checklist-2026-09 L1 节的交付说明里。
     *
     * <p>不截断：大图的意义就是全部。规模超过几百列时再谈按表折叠。
     */
    private void handleLineageNetworkGraph(HttpExchange exchange) throws IOException {
        java.util.Collection<LineageRecord> all = workspace.getLineage().values();
        LineageGraphDto dto = new LineageGraphDto();
        dto.setDepth(0);
        Set<String> seenColumns = new LinkedHashSet<>();
        for (LineageRecord record : all) {
            if (record.getTarget() == null) continue;
            List<String> columns = new ArrayList<>(record.getSources());
            columns.add(record.getTarget());
            for (String columnId : columns) {
                if (!seenColumns.add(lineageKey(columnId))) continue;
                LineageGraphDto.Node node = new LineageGraphDto.Node();
                String[] parts = parseColumnRef(columnId);
                node.setId(columnRef(columnId));
                node.setSchema(parts[0]);
                node.setTable(parts[1]);
                node.setColumn(parts[2]);
                dto.getNodes().add(node);
                String tableId = parts[0] + "." + parts[1];
                if (!dto.getTableColumns().containsKey(tableId)) {
                    TableWorkspaceNode table = workspace.getTableByQualifiedName(tableId);
                    dto.getTableColumns().put(tableId, table == null ? 0 : table.getColumns().size());
                }
            }
            for (String source : record.getSources()) {
                LineageGraphDto.Edge edge = new LineageGraphDto.Edge();
                edge.setFrom(columnRef(source));
                edge.setTo(columnRef(record.getTarget()));
                edge.setExpression(record.getExpression());
                edge.setThrough(record.getThrough());
                edge.setId(record.getId());
                edge.setLineageKind(record.getLineageKind() == null ? null : record.getLineageKind().name());
                dto.getEdges().add(edge);
            }
            LineageGraphDto.Record row = new LineageGraphDto.Record();
            row.setId(record.getId());
            row.setTarget(columnRef(record.getTarget()));
            row.setLineageKind(record.getLineageKind() == null ? null : record.getLineageKind().name());
            row.setSources(record.getSources().stream().map(WorkspaceQueryController::columnRef).toList());
            row.setExpression(record.getExpression());
            row.setThrough(record.getThrough());
            row.setStatus(record.getStatus() == null ? null : record.getStatus().name());
            row.setConfidence(record.getConfidence());
            row.setUpdatedAt(record.getUpdatedAt() == null ? null : record.getUpdatedAt().toString());
            dto.getRecords().add(row);
        }
        json.writeOk(exchange, dto);
    }

    // --- GET /api/lineage?table=&column=&depth=2 ---

    /** 图节点数上限：折叠而不是把大图画烂。血缘规模一般很小,这个阈值基本不会真的触发。 */
    private static final int LINEAGE_MAX_NODES = 40;

    /**
     * 单字段血缘的多跳视图，入口是「表 + 列」（与 TableInspector 里已有的血缘按钮一一对应），
     * 不提供全库血缘——那是另一个量级的功能。深度语义与 CLI `schema lineage --depth` 一致，
     * 复用同一个 {@link LineageGraph} 遍历。
     */
    private void handleLineage(HttpExchange exchange) throws IOException {
        Map<String, String> params = json.parseQueryParams(exchange);
        String tableRef = params.get("table");
        String columnName = params.get("column");
        if (tableRef == null || tableRef.isBlank() || columnName == null || columnName.isBlank()) {
            json.writeError(exchange, new IllegalArgumentException("缺少 table 或 column 参数"));
            return;
        }
        int depth = Math.max(1, Math.min(json.getIntParam(params, "depth", 2), 5));

        TableWorkspaceNode table = resolveTableRef(tableRef);
        if (table == null) {
            json.writeNotFound(exchange, "Table not found: " + tableRef);
            return;
        }
        ColumnWorkspaceNode column = table.getColumns().stream()
                .filter(c -> columnName.equalsIgnoreCase(c.getName()))
                .findFirst().orElse(null);
        if (column == null) {
            json.writeNotFound(exchange, "Column not found: " + columnName);
            return;
        }

        String tableAlias = workspace.getManifest().getAlias();
        String columnId = column.computeId(tableAlias, table.getSchema(), table.getName());
        json.writeOk(exchange, buildLineageGraph(columnId, depth));
    }

    /**
     * BFS 展开到 depth 跳，节点带 signed level：走 upRecords 时已知侧是 target（level 已定），
     * 未知侧是 sources（level = target - 1）；走 downRecords 时已知侧是 sources 之一
     * （level 已定，同一条记录的其余 sources 视为同级——它们是同一个表达式的兄弟输入），
     * 未知侧是 target（level = 已知 + 1）。
     */
    private LineageGraphDto buildLineageGraph(String columnId, int depth) {
        LineageGraph graph = new LineageGraph(workspace.getLineage().values());
        List<LineageRecord> upRecords = graph.upstream(columnId, depth);
        List<LineageRecord> downRecords = graph.downstream(columnId, depth);

        LineageGraphDto dto = new LineageGraphDto();
        dto.setCenter(columnRef(columnId));
        dto.setDepth(depth);

        Map<String, Integer> levelOf = new LinkedHashMap<>();
        Map<String, LineageGraphDto.Node> nodes = new LinkedHashMap<>();
        List<LineageGraphDto.Edge> edges = new ArrayList<>();
        Set<String> seenEdges = new LinkedHashSet<>();
        boolean[] truncated = {false};

        levelOf.put(lineageKey(columnId), 0);
        addLineageNode(nodes, columnId, 0, truncated);

        for (LineageRecord record : upRecords) {
            String target = record.getTarget();
            Integer targetLevel = levelOf.get(lineageKey(target));
            if (targetLevel == null) {
                continue;
            }
            int sourceLevel = targetLevel - 1;
            for (String source : record.getSources()) {
                levelOf.putIfAbsent(lineageKey(source), sourceLevel);
                addLineageEdge(nodes, levelOf, edges, seenEdges, source, target, record, truncated);
            }
        }
        for (LineageRecord record : downRecords) {
            String target = record.getTarget();
            if (target == null) {
                continue;
            }
            Integer knownLevel = null;
            for (String source : record.getSources()) {
                Integer level = levelOf.get(lineageKey(source));
                if (level != null) {
                    knownLevel = level;
                    break;
                }
            }
            if (knownLevel == null) {
                continue;
            }
            levelOf.putIfAbsent(lineageKey(target), knownLevel + 1);
            for (String source : record.getSources()) {
                levelOf.putIfAbsent(lineageKey(source), knownLevel);
                addLineageEdge(nodes, levelOf, edges, seenEdges, source, target, record, truncated);
            }
        }

        dto.setNodes(new ArrayList<>(nodes.values()));
        dto.setEdges(edges);
        dto.setTruncated(truncated[0]);
        // 写进起点这一列的记录：upstream 深度 1 正好是 target == 起点的那些
        for (LineageRecord record : graph.upstream(columnId, 1)) {
            if (record.getTarget() == null || !lineageKey(record.getTarget()).equals(lineageKey(columnId))) continue;
            LineageGraphDto.Record row = new LineageGraphDto.Record();
            row.setId(record.getId());
            row.setLineageKind(record.getLineageKind() == null ? null : record.getLineageKind().name());
            row.setSources(record.getSources().stream().map(WorkspaceQueryController::columnRef).toList());
            row.setExpression(record.getExpression());
            row.setThrough(record.getThrough());
            row.setStatus(record.getStatus() == null ? null : record.getStatus().name());
            row.setConfidence(record.getConfidence());
            row.setUpdatedAt(record.getUpdatedAt() == null ? null : record.getUpdatedAt().toString());
            dto.getRecords().add(row);
        }
        return dto;
    }

    private void addLineageNode(Map<String, LineageGraphDto.Node> nodes, String columnId, int level,
            boolean[] truncated) {
        String key = lineageKey(columnId);
        if (nodes.containsKey(key)) {
            return;
        }
        if (nodes.size() >= LINEAGE_MAX_NODES) {
            truncated[0] = true;
            return;
        }
        LineageGraphDto.Node node = new LineageGraphDto.Node();
        String[] parts = parseColumnRef(columnId);
        node.setId(columnRef(columnId));
        node.setSchema(parts[0]);
        node.setTable(parts[1]);
        node.setColumn(parts[2]);
        node.setLevel(level);
        nodes.put(key, node);
    }

    private void addLineageEdge(Map<String, LineageGraphDto.Node> nodes, Map<String, Integer> levelOf,
            List<LineageGraphDto.Edge> edges, Set<String> seenEdges,
            String source, String target, LineageRecord record, boolean[] truncated) {
        addLineageNode(nodes, source, levelOf.getOrDefault(lineageKey(source), 0), truncated);
        addLineageNode(nodes, target, levelOf.getOrDefault(lineageKey(target), 0), truncated);
        if (!nodes.containsKey(lineageKey(source)) || !nodes.containsKey(lineageKey(target))) {
            return; // 节点数已到上限,不画悬空边
        }
        String edgeKey = lineageKey(source) + ">" + lineageKey(target) + "#" + record.getId();
        if (!seenEdges.add(edgeKey)) {
            return;
        }
        LineageGraphDto.Edge edge = new LineageGraphDto.Edge();
        edge.setFrom(columnRef(source));
        edge.setTo(columnRef(target));
        edge.setExpression(record.getExpression());
        edge.setThrough(record.getThrough());
        edge.setId(record.getId());
        edge.setLineageKind(record.getLineageKind() == null ? null : record.getLineageKind().name());
        edges.add(edge);
    }

    /** column:alias:schema.table.column -> [schema, table, column]，非列 id 只填 column 段。 */
    private static String[] parseColumnRef(String columnId) {
        String ref = columnRef(columnId);
        if (ref == null) {
            return new String[]{null, null, null};
        }
        int lastDot = ref.lastIndexOf('.');
        int tableDot = lastDot >= 0 ? ref.lastIndexOf('.', lastDot - 1) : -1;
        if (tableDot >= 0) {
            return new String[]{ref.substring(0, tableDot), ref.substring(tableDot + 1, lastDot),
                    ref.substring(lastDot + 1)};
        }
        return new String[]{null, null, ref};
    }

    private static String columnRef(String columnId) {
        if (columnId == null) {
            return null;
        }
        return columnId.startsWith("column:") ? columnId.split(":", 3)[2] : columnId;
    }

    private static String lineageKey(String columnId) {
        return columnId == null ? "" : columnId.toLowerCase(Locale.ROOT);
    }

    // --- Helper ---

    /**
     * 术语命中落到哪个对象：`primaryTarget` 优先，没标主次就取第一条 `term_mapping`。
     *
     * <p>术语自己没有 schema / table，不填的话搜索框里点它什么也不会发生——而
     * 「搜业务词、落到对的表」正是术语存在的全部理由，一条点不动的结果等于没出。
     * 填的是**它指向的那个对象**的坐标，不是术语自己的，所以 name 仍然显示术语名。
     *
     * @return {@code {schema, table, column}}，解析不出来时各位为 null
     */
    private String[] termTarget(String termId) {
        TermWorkspaceNode term = termId == null ? null : workspace.getTerms().get(termId);
        String target = term == null ? null : term.getPrimaryTarget();
        if (target == null && term != null) {
            target = workspace.getRelations().stream()
                    .filter(edge -> edge.getType() == RelationType.term_mapping)
                    .filter(edge -> termId.equals(edge.getFrom()))
                    .map(RelationWorkspaceEdge::getTo)
                    .findFirst().orElse(null);
        }
        if (target == null) return new String[]{null, null, null};
        String[] parts = target.split(":", 3);
        if (parts.length < 3) return new String[]{null, null, null};
        String[] segments = parts[2].split("\\.");
        if (target.startsWith("column:") && segments.length >= 3) {
            return new String[]{segments[0], segments[1], segments[2]};
        }
        if (target.startsWith("table:") && segments.length >= 2) {
            return new String[]{segments[0], segments[1], null};
        }
        return new String[]{null, null, null};
    }

    private String resolveTableId(String nodeId) {
        if (nodeId == null) return null;
        if (nodeId.startsWith("table:")) return nodeId;
        if (nodeId.startsWith("column:")) {
            String[] parts = nodeId.split(":", 3);
            if (parts.length < 3) return null;
            String qualified = parts[2];
            int lastDot = qualified.lastIndexOf('.');
            if (lastDot < 0) return null;
            return "table:" + parts[1] + ":" + qualified.substring(0, lastDot);
        }
        return null;
    }

}
