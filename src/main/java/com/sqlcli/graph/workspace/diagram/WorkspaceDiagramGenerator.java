package com.sqlcli.graph.workspace.diagram;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sqlcli.graph.workspace.GraphStatus;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.RelationWorkspaceEdge;
import com.sqlcli.graph.workspace.SchemaWorkspaceNode;
import com.sqlcli.graph.workspace.TableWorkspaceNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class WorkspaceDiagramGenerator {
    private static final int MAX_RELATION_NODES = 40;
    private static final int MAX_RELATION_EDGES = 80;
    private static final int MAX_CATALOG_TABLES = 200;

    private final ObjectMapper mapper;

    public WorkspaceDiagramGenerator() {
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public DiagramResult generate(GraphWorkspace workspace, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        cleanupGeneratedFiles(outputDir);

        List<TableRelation> relations = aggregateRelations(workspace);
        Set<String> relatedTableIds = new LinkedHashSet<>();
        relations.forEach(relation -> {
            relatedTableIds.add(relation.fromTableId);
            relatedTableIds.add(relation.toTableId);
        });

        List<GeneratedFile> files = new ArrayList<>();
        write(outputDir.resolve("overview.md"), renderOverview(workspace), files, "overview");

        List<List<TableRelation>> relationChunks = partitionRelations(relations);
        for (int i = 0; i < relationChunks.size(); i++) {
            String name = String.format(Locale.ROOT, "relations-%05d.md", i + 1);
            write(outputDir.resolve(name), renderRelations(workspace, relationChunks.get(i), i + 1),
                    files, "relations");
        }

        List<TableWorkspaceNode> tables = workspace.getTables().values().stream()
                .sorted(Comparator.comparing(TableWorkspaceNode::getQualifiedName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        for (int offset = 0, part = 1; offset < tables.size(); offset += MAX_CATALOG_TABLES, part++) {
            int end = Math.min(tables.size(), offset + MAX_CATALOG_TABLES);
            String name = String.format(Locale.ROOT, "catalog-%05d.md", part);
            write(outputDir.resolve(name), renderCatalog(tables.subList(offset, end), relatedTableIds, part),
                    files, "catalog");
        }

        write(outputDir.resolve("README.md"), renderReadme(workspace, relations, relatedTableIds, files),
                files, "readme");
        DiagramManifest manifest = buildManifest(workspace, relations, relatedTableIds, files);
        writeJson(outputDir.resolve("manifest.json"), manifest);
        return new DiagramResult(outputDir, manifest);
    }

    private long activeRelationCount(GraphWorkspace workspace) {
        return workspace.getRelations().stream()
                .filter(edge -> edge.getStatus() != GraphStatus.ignored)
                .count();
    }

    private List<TableRelation> aggregateRelations(GraphWorkspace workspace) {
        Map<String, TableRelation> aggregated = new LinkedHashMap<>();
        for (RelationWorkspaceEdge edge : workspace.getRelations()) {
            // 必须过滤 ignored：这是静态 Mermaid 图，没有「显示但标注」的余地——
            // 画出一条被人明确拒绝过的关系是误导，判据同 WorkspacePathFinder。
            if (edge.getStatus() == GraphStatus.ignored) {
                continue;
            }
            Endpoint from = parseEndpoint(edge.getFrom());
            Endpoint to = parseEndpoint(edge.getTo());
            if (from == null || to == null) {
                continue;
            }
            TableWorkspaceNode fromTable = workspace.getTables().get(from.tableId);
            TableWorkspaceNode toTable = workspace.getTables().get(to.tableId);
            if (fromTable == null || toTable == null) {
                continue;
            }
            String key = from.tableId + "\u0000" + to.tableId + "\u0000" + edge.getType();
            TableRelation relation = aggregated.computeIfAbsent(key, ignored ->
                    new TableRelation(from.tableId, to.tableId, edge.getType().name()));
            relation.verified |= Boolean.TRUE.equals(edge.getVerified());
            if (edge.getConfidence() != null) {
                relation.confidence = Math.max(relation.confidence, edge.getConfidence());
            }
            if (from.column != null || to.column != null) {
                relation.columnPairs.add(value(from.column) + " -> " + value(to.column));
            }
        }
        return aggregated.values().stream()
                .sorted(Comparator.comparing((TableRelation relation) -> relation.fromTableId)
                        .thenComparing(relation -> relation.toTableId)
                        .thenComparing(relation -> relation.type))
                .toList();
    }

    private List<List<TableRelation>> partitionRelations(List<TableRelation> relations) {
        List<List<TableRelation>> chunks = new ArrayList<>();
        List<TableRelation> current = new ArrayList<>();
        Set<String> nodes = new LinkedHashSet<>();
        for (TableRelation relation : relations) {
            Set<String> nextNodes = new LinkedHashSet<>(nodes);
            nextNodes.add(relation.fromTableId);
            nextNodes.add(relation.toTableId);
            if (!current.isEmpty()
                    && (nextNodes.size() > MAX_RELATION_NODES || current.size() >= MAX_RELATION_EDGES)) {
                chunks.add(current);
                current = new ArrayList<>();
                nodes = new LinkedHashSet<>();
            }
            current.add(relation);
            nodes.add(relation.fromTableId);
            nodes.add(relation.toTableId);
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }

    private String renderReadme(GraphWorkspace workspace, List<TableRelation> relations,
            Set<String> relatedTableIds, List<GeneratedFile> files) {
        int isolated = Math.max(0, workspace.getTables().size() - relatedTableIds.size());
        StringBuilder text = new StringBuilder();
        text.append("# ").append(workspace.getManifest().getAlias()).append(" Developer Graph\n\n");
        text.append("Generated from the schema graph workspace. Do not edit generated files manually.\n\n");
        text.append("## Summary\n\n");
        text.append("| Metric | Count |\n| --- | ---: |\n");
        text.append("| Schemas | ").append(workspace.getSchemas().size()).append(" |\n");
        text.append("| Tables | ").append(workspace.getTables().size()).append(" |\n");
        text.append("| Columns | ").append(workspace.getColumnCount()).append(" |\n");
        // 只数非 ignored：这个数字是「你正在看的这张图里有什么」，必须和图本身一致，
        // 不能把被拒的边也算进去让读者以为图里画了它。
        text.append("| Raw relations | ").append(activeRelationCount(workspace)).append(" |\n");
        text.append("| Aggregated table relations | ").append(relations.size()).append(" |\n");
        text.append("| Tables participating in relations | ").append(relatedTableIds.size()).append(" |\n");
        text.append("| Tables without known relations | ").append(isolated).append(" |\n");
        text.append("| Validation issues | ").append(workspace.getValidationIssues().size()).append(" |\n\n");
        text.append("## Entry Points\n\n");
        text.append("- [Schema overview](overview.md)\n");
        for (GeneratedFile file : files) {
            if ("relations".equals(file.kind)) {
                text.append("- [Relation graph ").append(file.path.replaceAll("\\D", ""))
                        .append("](").append(file.path).append(")\n");
            }
        }
        if (relations.isEmpty()) {
            text.append("- No table relations are currently available. Check import and relation extraction quality.\n");
        }
        text.append("- Table catalogs are split into `catalog-*.md` files.\n\n");
        text.append("## Reading Guide\n\n");
        text.append("- Relation nodes are tables; edge labels show relation type and representative column pairs.\n");
        text.append("- A solid edge comes from the stored graph. `verified` and confidence are shown in the label.\n");
        text.append("- Tables without known relations remain in the catalog instead of inflating the relation graph.\n");
        return text.toString();
    }

    private String renderOverview(GraphWorkspace workspace) {
        Map<String, Integer> tableCounts = new LinkedHashMap<>();
        workspace.getTables().values().forEach(table ->
                tableCounts.merge(table.getSchema(), 1, Integer::sum));
        StringBuilder text = new StringBuilder("# Schema Overview\n\n```mermaid\nflowchart TB\n");
        text.append("  workspace[\"").append(escape(workspace.getManifest().getAlias())).append("\"]\n");
        int index = 0;
        for (SchemaWorkspaceNode schema : workspace.getSchemas().values().stream()
                .sorted(Comparator.comparing(SchemaWorkspaceNode::getName, String.CASE_INSENSITIVE_ORDER))
                .toList()) {
            String node = "s" + index++;
            text.append("  ").append(node).append("[\"")
                    .append(escape(schema.getName())).append("\\n")
                    .append(tableCounts.getOrDefault(schema.getName(), 0)).append(" tables\"]\n");
            text.append("  workspace --> ").append(node).append("\n");
        }
        text.append("```\n");
        return text.toString();
    }

    private String renderRelations(GraphWorkspace workspace, List<TableRelation> relations, int part) {
        Map<String, String> nodeIds = new LinkedHashMap<>();
        for (TableRelation relation : relations) {
            nodeIds.computeIfAbsent(relation.fromTableId, ignored -> "t" + nodeIds.size());
            nodeIds.computeIfAbsent(relation.toTableId, ignored -> "t" + nodeIds.size());
        }
        StringBuilder text = new StringBuilder("# Table Relations ").append(part)
                .append("\n\n```mermaid\nflowchart LR\n");
        for (Map.Entry<String, String> entry : nodeIds.entrySet()) {
            TableWorkspaceNode table = workspace.getTables().get(entry.getKey());
            text.append("  ").append(entry.getValue()).append("[\"")
                    .append(escape(table.getQualifiedName())).append("\"]\n");
        }
        for (TableRelation relation : relations) {
            text.append("  ").append(nodeIds.get(relation.fromTableId)).append(" -->|")
                    .append(escape(relation.label())).append("| ")
                    .append(nodeIds.get(relation.toTableId)).append("\n");
        }
        text.append("```\n\n## Relations\n\n");
        text.append("| From | To | Type | Confidence | Verified | Columns |\n");
        text.append("| --- | --- | --- | ---: | --- | --- |\n");
        for (TableRelation relation : relations) {
            text.append("| ").append(tableName(workspace, relation.fromTableId))
                    .append(" | ").append(tableName(workspace, relation.toTableId))
                    .append(" | ").append(relation.type)
                    .append(" | ").append(String.format(Locale.ROOT, "%.2f", relation.confidence))
                    .append(" | ").append(relation.verified)
                    .append(" | ").append(escapeMarkdown(String.join("<br>", relation.columnPairs))).append(" |\n");
        }
        return text.toString();
    }

    private String renderCatalog(List<TableWorkspaceNode> tables, Set<String> relatedTableIds, int part) {
        StringBuilder text = new StringBuilder("# Table Catalog ").append(part).append("\n\n");
        text.append("| Schema | Table | Type | Columns | Related | Description |\n");
        text.append("| --- | --- | --- | ---: | --- | --- |\n");
        for (TableWorkspaceNode table : tables) {
            text.append("| ").append(escapeMarkdown(table.getSchema()))
                    .append(" | ").append(escapeMarkdown(table.getName()))
                    .append(" | ").append(table.getTableType())
                    .append(" | ").append(table.getColumns().size())
                    .append(" | ").append(relatedTableIds.contains(table.getId()))
                    .append(" | ").append(escapeMarkdown(table.getComment())).append(" |\n");
        }
        return text.toString();
    }

    private DiagramManifest buildManifest(GraphWorkspace workspace, List<TableRelation> relations,
            Set<String> relatedTableIds, List<GeneratedFile> files) {
        DiagramManifest manifest = new DiagramManifest();
        manifest.alias = workspace.getManifest().getAlias();
        manifest.generatedAt = Instant.now();
        manifest.schemas = workspace.getSchemas().size();
        manifest.tables = workspace.getTables().size();
        manifest.columns = workspace.getColumnCount();
        // 同上：manifest 里的计数要和实际生成的图保持一致，只数非 ignored。
        manifest.rawRelations = (int) activeRelationCount(workspace);
        manifest.tableRelations = relations.size();
        manifest.relatedTables = relatedTableIds.size();
        manifest.isolatedTables = Math.max(0, manifest.tables - manifest.relatedTables);
        manifest.files = files;
        return manifest;
    }

    private Endpoint parseEndpoint(String id) {
        if (id == null) {
            return null;
        }
        if (id.startsWith("table:")) {
            return new Endpoint(id, null);
        }
        if (!id.startsWith("column:")) {
            return null;
        }
        String[] parts = id.split(":", 3);
        if (parts.length != 3) {
            return null;
        }
        int lastDot = parts[2].lastIndexOf('.');
        if (lastDot < 0) {
            return null;
        }
        return new Endpoint("table:" + parts[1] + ":" + parts[2].substring(0, lastDot),
                parts[2].substring(lastDot + 1));
    }

    private void write(Path path, String content, List<GeneratedFile> files, String kind) throws IOException {
        writeAtomically(path, content);
        files.add(new GeneratedFile(path.getFileName().toString(), kind, Files.size(path)));
    }

    private void writeJson(Path path, Object value) throws IOException {
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            mapper.writeValue(temp.toFile(), value);
            moveAtomically(temp, path);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void writeAtomically(Path path, String content) throws IOException {
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.writeString(temp, content);
            moveAtomically(temp, path);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void cleanupGeneratedFiles(Path outputDir) throws IOException {
        try (var stream = Files.list(outputDir)) {
            for (Path path : stream.toList()) {
                String name = path.getFileName().toString();
                if (name.equals("README.md") || name.equals("overview.md") || name.equals("manifest.json")
                        || name.matches("relations-\\d+\\.md") || name.matches("catalog-\\d+\\.md")) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String tableName(GraphWorkspace workspace, String tableId) {
        TableWorkspaceNode table = workspace.getTables().get(tableId);
        return table == null ? tableId : escapeMarkdown(table.getQualifiedName());
    }

    private String escape(String value) {
        return value(value).replace("\\", "\\\\").replace("\"", "'")
                .replace("[", "(").replace("]", ")").replace("|", "/");
    }

    private String escapeMarkdown(String value) {
        return value(value).replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private record Endpoint(String tableId, String column) {}

    private static class TableRelation {
        private final String fromTableId;
        private final String toTableId;
        private final String type;
        private final Set<String> columnPairs = new LinkedHashSet<>();
        private double confidence;
        private boolean verified;

        private TableRelation(String fromTableId, String toTableId, String type) {
            this.fromTableId = fromTableId;
            this.toTableId = toTableId;
            this.type = type;
        }

        private String label() {
            StringBuilder label = new StringBuilder(type);
            if (!columnPairs.isEmpty()) {
                label.append("\\n").append(columnPairs.iterator().next());
                if (columnPairs.size() > 1) {
                    label.append(" +").append(columnPairs.size() - 1);
                }
            }
            if (verified) {
                label.append("\\nverified");
            }
            return label.toString();
        }
    }

    public static class GeneratedFile {
        public String path;
        public String kind;
        public long bytes;

        public GeneratedFile() {}

        private GeneratedFile(String path, String kind, long bytes) {
            this.path = path;
            this.kind = kind;
            this.bytes = bytes;
        }
    }

    public static class DiagramManifest {
        public String alias;
        public Instant generatedAt;
        public int schemas;
        public int tables;
        public int columns;
        public int rawRelations;
        public int tableRelations;
        public int relatedTables;
        public int isolatedTables;
        public List<GeneratedFile> files = new ArrayList<>();
    }

    public record DiagramResult(Path outputDir, DiagramManifest manifest) {}
}
