package com.sqlcli.graph.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sqlcli.config.SettingsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class GraphWorkspaceStore {
    private static final Logger LOG = LoggerFactory.getLogger(GraphWorkspaceStore.class);
    private static final String CURRENT_GENERATION = "current-generation";
    private static final String GENERATIONS_DIR = "generations";

    private final ObjectMapper yamlMapper;
    private final ObjectMapper jsonMapper;
    private final ObjectMapper lineJsonMapper;
    private final Path baseRoot;

    public GraphWorkspaceStore() {
        this(null);
    }

    public GraphWorkspaceStore(Path baseRoot) {
        this.baseRoot = baseRoot;
        yamlMapper = new ObjectMapper(new YAMLFactory());
        yamlMapper.registerModule(new JavaTimeModule());
        yamlMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        yamlMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        yamlMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

        jsonMapper = new ObjectMapper();
        jsonMapper.registerModule(new JavaTimeModule());
        jsonMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        jsonMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        jsonMapper.enable(SerializationFeature.INDENT_OUTPUT);
        jsonMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

        lineJsonMapper = new ObjectMapper();
        lineJsonMapper.registerModule(new JavaTimeModule());
        lineJsonMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        lineJsonMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        lineJsonMapper.disable(SerializationFeature.INDENT_OUTPUT);
        lineJsonMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public GraphWorkspace load(String alias) throws IOException {
        Path root = resolveWorkspacePath(alias);
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("图谱工作区不存在: " + root);
        }
        Path dataRoot = locateDataRoot(root);
        if (!hasCompleteWorkspace(dataRoot)) {
            throw new IOException("图谱工作区不存在: " + root);
        }
        return loadFromStructuredFiles(dataRoot);
    }

    public boolean exists(String alias) throws IOException {
        Path root = resolveWorkspacePath(alias);
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        rejectSymlink(root);
        return hasCompleteWorkspace(locateDataRoot(root));
    }

    public void save(GraphWorkspace workspace) throws IOException {
        Path root = resolveWorkspacePath(workspace.getManifest().getAlias());
        ensureRootLayout(root);
        refreshStats(workspace);
        Path generations = root.resolve(GENERATIONS_DIR);
        String id = String.format("%020d-%s", workspace.getManifest().getRevision(), UUID.randomUUID());
        Path staging = safeResolve(generations, ".staging-" + UUID.randomUUID());
        Path committed = safeResolve(generations, id);
        Throwable failure = null;
        try {
            Files.createDirectories(staging);
            writeWorkspace(staging, workspace);
            if (!hasCompleteGeneration(staging)) {
                throw new IOException("Workspace generation is incomplete: " + staging);
            }
            loadFromStructuredFiles(staging);
            try {
                Files.move(staging, committed, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(staging, committed);
            }
            writeCurrentGeneration(root, id);
            // 图谱不做多版本管理（决策 2026-08-20）：指针切换成功后只保留当前代。
            // 旧代从无读取方（load 只走 current-generation，import resume 依赖 jobs 状态 + 当前代），
            // 之前"每次 save 全量留一代"曾把单别名撑到 78MB/35 代。
            pruneOldGenerations(generations, id);
        } catch (IOException | RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            try {
                deleteTree(staging);
            } catch (IOException cleanupError) {
                if (failure == null) {
                    throw cleanupError;
                }
                failure.addSuppressed(cleanupError);
            }
        }
    }

    /**
     * 删除 keepId 之外的所有已提交 generation 目录。
     * 不碰 .staging-*：它们由各自 save 的 finally 清理，删除并发进程正在写的 staging 会让对方失败。
     * 尽力而为：目录正被其他进程读取时（Windows 文件锁）删除会失败，静默跳过，下一次 save 自然重试。
     */
    private void pruneOldGenerations(Path generations, String keepId) {
        try (java.util.stream.Stream<Path> entries = Files.list(generations)) {
            entries.filter(Files::isDirectory)
                    .filter(dir -> {
                        String name = dir.getFileName().toString();
                        return !name.equals(keepId) && !name.startsWith(".staging-");
                    })
                    .forEach(dir -> {
                        try {
                            deleteTree(dir);
                        } catch (IOException busy) {
                            LOG.debug("Skip pruning busy generation {}", dir, busy);
                        }
                    });
        } catch (IOException e) {
            LOG.debug("Generation prune skipped", e);
        }
    }

    public void exportSnapshot(GraphWorkspace workspace, Path path) throws IOException {
        writeJsonAtomically(path, WorkspaceSnapshot.fromWorkspace(workspace));
    }

    public GraphWorkspace importSnapshot(Path path) throws IOException {
        JsonNode root = jsonMapper.readTree(path.toFile());
        validateManifestNode(root.path("manifest"), "snapshot " + path);
        GraphWorkspace workspace = jsonMapper.treeToValue(root, WorkspaceSnapshot.class).toWorkspace();
        upgradePreviousVersion(workspace.getManifest());
        return workspace;
    }

    public static Path getWorkspacePath(String alias) {
        try {
            validatePathComponent(alias, "alias");
        } catch (IOException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        // resolveSchemaGraphPath() 已经挂了 sqlcli.configRoot 覆盖开关，
        // 不能再自己拼 Path.of(graphDir)——那样会绕开覆盖，重新踩相对 CWD 的坑。
        return SettingsConfig.getInstance().resolveSchemaGraphPath().resolve(alias);
    }

    public Path workspacePath(String alias) throws IOException {
        return resolveWorkspacePath(alias);
    }

    private void ensureRootLayout(Path root) throws IOException {
        rejectSymlink(root);
        Files.createDirectories(root);
        Files.createDirectories(root.resolve("jobs"));
        Files.createDirectories(root.resolve("jobs/import-history"));
        Files.createDirectories(root.resolve("jobs/tasks"));
        Files.createDirectories(root.resolve("index"));
        Path policy = root.resolve("policy");
        rejectSymlink(policy);
        Path rules = policy.resolve("rules");
        rejectSymlink(rules);
        Files.createDirectories(rules);
        Path bindings = policy.resolve("bindings.yaml");
        if (!Files.exists(bindings, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            Files.writeString(bindings, "ruleSets: []\n", StandardOpenOption.CREATE_NEW);
        } else {
            rejectSymlink(bindings);
            if (!Files.isRegularFile(bindings, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Invalid policy bindings file: " + bindings);
            }
        }
        Path generations = root.resolve(GENERATIONS_DIR);
        rejectSymlink(generations);
        Files.createDirectories(generations);
    }

    private void ensureDataLayout(Path root) throws IOException {
        Files.createDirectories(root.resolve("nodes/schemas"));
        Files.createDirectories(root.resolve("nodes/tables"));
        Files.createDirectories(root.resolve("nodes/terms"));
        Files.createDirectories(root.resolve("edges"));
        Files.createDirectories(root.resolve("changes"));
        Files.createDirectories(root.resolve("validation"));
    }

    private void writeWorkspace(Path root, GraphWorkspace workspace) throws IOException {
        ensureDataLayout(root);
        writeYamlAtomically(root.resolve("manifest.yaml"), workspace.getManifest());
        writeYamlAtomically(root.resolve("datasource.yaml"), workspace.getDataSource());
        writeNodeTree(root, workspace);
        writeJsonLines(root.resolve("edges/relations.jsonl"), workspace.getRelations());
        writeJsonLines(root.resolve("edges/lineage.jsonl"), workspace.getLineage().values());
        writeJsonLines(root.resolve("edges/metrics.jsonl"), workspace.getMetrics().values());
        writeJsonLines(root.resolve("changes/changes.jsonl"), workspace.getChanges());
        writeJsonLines(root.resolve("validation/issues.jsonl"), workspace.getValidationIssues());
    }

    private void writeNodeTree(Path root, GraphWorkspace workspace) throws IOException {
        cleanDirectory(root.resolve("nodes/schemas"));
        cleanDirectory(root.resolve("nodes/tables"));
        cleanDirectory(root.resolve("nodes/terms"));

        Map<String, String> schemaNames = new HashMap<>();
        for (SchemaWorkspaceNode schema : workspace.getSchemas().values()) {
            validatePathComponent(schema.getName(), "schema");
            reserveFileName(schemaNames, schema.getName(), "schema", "nodes/schemas");
            yamlMapper.writeValue(safeResolve(root.resolve("nodes/schemas"), schema.getName() + ".yaml").toFile(), schema);
        }
        Map<String, Map<String, String>> tableNames = new HashMap<>();
        for (TableWorkspaceNode table : workspace.getTables().values()) {
            validatePathComponent(table.getSchema(), "schema");
            validatePathComponent(table.getName(), "table");
            Map<String, String> perSchema = tableNames.computeIfAbsent(
                    table.getSchema().toLowerCase(Locale.ROOT), ignored -> new HashMap<>());
            reserveFileName(perSchema, table.getName(), "table", "nodes/tables/" + table.getSchema());
            Path dir = safeResolve(root.resolve("nodes/tables"), table.getSchema());
            Files.createDirectories(dir);
            yamlMapper.writeValue(safeResolve(dir, table.getName() + ".yaml").toFile(), table);
        }
        Map<String, String> termNames = new HashMap<>();
        for (TermWorkspaceNode term : workspace.getTerms().values()) {
            validatePathComponent(term.getName(), "term");
            reserveFileName(termNames, term.getName(), "term", "nodes/terms");
            yamlMapper.writeValue(safeResolve(root.resolve("nodes/terms"), term.getName() + ".yaml").toFile(), term);
        }
    }

    /**
     * Windows 与 macOS 默认文件系统不区分大小写，两个仅大小写不同的对象会写入同一个文件并静默覆盖，
     * 保存后再次加载就会少一个对象。这里显式检测冲突，宁可保存失败也不丢数据。
     */
    private void reserveFileName(Map<String, String> used, String name, String label, String dir) throws IOException {
        String previous = used.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
        if (previous != null && !previous.equals(name)) {
            throw new IOException("Conflicting " + label + " names in " + dir
                    + " differ only by case and cannot be stored on a case-insensitive filesystem: "
                    + previous + " / " + name);
        }
    }

    private void writeJsonLines(Path path, Iterable<?> values) throws IOException {
        Files.createDirectories(path.getParent());
        try (var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            for (Object value : values) {
                writer.write(lineJsonMapper.writeValueAsString(value));
                writer.write(System.lineSeparator());
            }
        }
    }

    private void cleanDirectory(Path path) throws IOException {
        if (!Files.exists(path)) {
            Files.createDirectories(path);
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder())
                    .filter(p -> !p.equals(path))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw e;
        }
    }

    private void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        cleanDirectory(root);
        Files.deleteIfExists(root);
    }

    private void refreshStats(GraphWorkspace workspace) {
        WorkspaceStats stats = new WorkspaceStats();
        stats.setSchemas(workspace.getSchemas().size());
        stats.setTables(workspace.getTables().size());
        stats.setColumns(workspace.getColumnCount());
        stats.setRelations(workspace.getRelations().size());
        // 显式展示：relations 现在含 ignored，单独数一份出来给人看，不改 relations 本身的语义。
        stats.setIgnoredRelations((int) workspace.getRelations().stream()
                .filter(relation -> relation.getStatus() == GraphStatus.ignored)
                .count());
        stats.setMetrics(workspace.getMetrics().size());
        stats.setValidationIssues(workspace.getValidationIssues().size());
        workspace.getManifest().setStats(stats);
        workspace.getManifest().touch();
    }

    private Path resolveWorkspacePath(String alias) throws IOException {
        validatePathComponent(alias, "alias");
        Path root = baseRoot != null ? baseRoot : getWorkspacePath(alias).getParent();
        return safeResolve(root, alias);
    }

    private Path locateDataRoot(Path root) throws IOException {
        rejectSymlink(root);
        Path pointer = root.resolve(CURRENT_GENERATION);
        if (!Files.exists(pointer, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return root;
        }
        rejectSymlink(pointer);
        if (!Files.isRegularFile(pointer, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Invalid workspace generation pointer: " + pointer);
        }
        String id = Files.readString(pointer, StandardCharsets.UTF_8).trim();
        validateGenerationId(id);
        Path generations = root.resolve(GENERATIONS_DIR);
        rejectSymlink(generations);
        Path generation = safeResolve(generations, id);
        if (!Files.isDirectory(generation, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                || !hasCompleteGeneration(generation)) {
            throw new IOException("Workspace generation is missing or incomplete: " + id);
        }
        return generation;
    }

    private boolean hasCompleteWorkspace(Path root) throws IOException {
        Path manifest = root.resolve("manifest.yaml");
        Path datasource = root.resolve("datasource.yaml");
        Path nodes = root.resolve("nodes");
        boolean hasWorkspaceData = Files.exists(manifest, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                || Files.exists(datasource, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                || Files.exists(nodes, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        if (!hasWorkspaceData) {
            return false;
        }
        rejectSymlink(manifest);
        rejectSymlink(datasource);
        rejectSymlink(nodes);
        if (!Files.isRegularFile(manifest, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(datasource, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(nodes, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("图谱工作区不完整: " + root);
        }
        return true;
    }

    private boolean hasCompleteGeneration(Path root) throws IOException {
        if (!hasCompleteWorkspace(root)) {
            return false;
        }
        for (String directory : List.of(
                "nodes/schemas", "nodes/tables", "nodes/terms", "edges", "changes", "validation")) {
            Path path = root.resolve(directory);
            rejectSymlink(path);
            if (!Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
        }
        for (String file : List.of(
                "edges/relations.jsonl", "changes/changes.jsonl", "validation/issues.jsonl")) {
            Path path = root.resolve(file);
            rejectSymlink(path);
            if (!Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
        }
        return true;
    }

    private void writeCurrentGeneration(Path root, String id) throws IOException {
        Path pointer = root.resolve(CURRENT_GENERATION);
        Path temp = root.resolve(CURRENT_GENERATION + ".tmp-" + UUID.randomUUID());
        try {
            Files.writeString(temp, id + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.move(temp, pointer, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void validateGenerationId(String id) throws IOException {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IOException("Invalid workspace generation: " + id);
        }
    }

    private GraphWorkspace loadFromStructuredFiles(Path root) throws IOException {
        rejectSymlink(root);
        GraphWorkspace workspace = new GraphWorkspace();
        Path manifestPath = root.resolve("manifest.yaml");
        rejectSymlink(manifestPath);
        rejectSymlink(root.resolve("datasource.yaml"));
        JsonNode manifestNode = yamlMapper.readTree(manifestPath.toFile());
        validateManifestNode(manifestNode, "workspace " + root);
        WorkspaceManifest manifest = yamlMapper.treeToValue(manifestNode, WorkspaceManifest.class);
        upgradePreviousVersion(manifest);
        workspace.setManifest(manifest);
        workspace.setDataSource(yamlMapper.readValue(root.resolve("datasource.yaml").toFile(), DataSourceNode.class));

        loadYamlDirectory(root.resolve("nodes/schemas"), SchemaWorkspaceNode.class, workspace.getSchemas());
        loadYamlDirectory(root.resolve("nodes/terms"), TermWorkspaceNode.class, workspace.getTerms());
        loadTableTree(root.resolve("nodes/tables"), workspace);

        workspace.setRelations(readJsonLines(root.resolve("edges/relations.jsonl"), RelationWorkspaceEdge.class));
        // 旧图谱没有 lineage / metrics 文件：readJsonLines 对缺失文件返回空表，直接兼容
        for (LineageRecord record : readJsonLines(root.resolve("edges/lineage.jsonl"), LineageRecord.class)) {
            // 类别是后加的列：老记录在内存里按形状补默认值，不回写——推断认不出 aggregation / rule，
            // 那几条要靠重跑 add-lineage --kind 纠正，回写会把猜的固化成事实。
            if (record.getLineageKind() == null) {
                record.setLineageKind(LineageKind.infer(record.getSources(), record.getExpression()));
            }
            workspace.getLineage().put(record.getId(), record);
        }
        for (MetricRecord record : readJsonLines(root.resolve("edges/metrics.jsonl"), MetricRecord.class)) {
            workspace.getMetrics().put(record.getId(), record);
        }
        workspace.setChanges(readJsonLines(root.resolve("changes/changes.jsonl"), ChangeRecord.class));
        workspace.setValidationIssues(readJsonLines(root.resolve("validation/issues.jsonl"), ValidationIssueRecord.class));
        return workspace;
    }

    private void validateManifestNode(JsonNode manifest, String source) throws IOException {
        if (!manifest.isObject()) {
            throw new IOException("Missing manifest in " + source);
        }
        requireVersion(manifest, "modelVersion", WorkspaceManifest.CURRENT_MODEL_VERSION, source);
        requireVersion(manifest, "storageVersion", WorkspaceManifest.CURRENT_STORAGE_VERSION, source);
        if (!manifest.has("revision") || !manifest.get("revision").canConvertToLong()
                || manifest.get("revision").longValue() < 1) {
            throw new IOException("Invalid or missing revision in " + source);
        }
    }

    private void requireVersion(JsonNode manifest, String field, int expected, String source) throws IOException {
        if (!manifest.has(field) || !manifest.get(field).canConvertToInt()) {
            throw new IOException("Missing " + field + " in " + source);
        }
        int actual = manifest.get(field).intValue();
        if (actual != expected && actual != expected - 1) {
            throw new IOException("Unsupported " + field + " " + actual + " in " + source
                    + "; expected " + (expected - 1) + " or " + expected);
        }
    }

    private void upgradePreviousVersion(WorkspaceManifest manifest) {
        if (manifest.getModelVersion() == WorkspaceManifest.CURRENT_MODEL_VERSION - 1) {
            manifest.setModelVersion(WorkspaceManifest.CURRENT_MODEL_VERSION);
        }
        if (manifest.getStorageVersion() == WorkspaceManifest.CURRENT_STORAGE_VERSION - 1) {
            manifest.setStorageVersion(WorkspaceManifest.CURRENT_STORAGE_VERSION);
        }
    }

    private <T extends BaseGraphObject> void loadYamlDirectory(Path dir, Class<T> type, java.util.Map<String, T> target)
            throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            for (Path path : stream.filter(path -> path.getFileName().toString().endsWith(".yaml")).toList()) {
                rejectSymlink(path);
                T value = yamlMapper.readValue(path.toFile(), type);
                target.put(value.getId(), value);
            }
        }
    }

    private void loadTableTree(Path root, GraphWorkspace workspace) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var schemas = Files.list(root)) {
            for (Path schemaDir : schemas.filter(Files::isDirectory).toList()) {
                rejectSymlink(schemaDir);
                try (var files = Files.list(schemaDir)) {
                    for (Path path : files.filter(file -> file.getFileName().toString().endsWith(".yaml")).toList()) {
                        rejectSymlink(path);
                        TableWorkspaceNode table = yamlMapper.readValue(path.toFile(), TableWorkspaceNode.class);
                        workspace.getTables().put(table.getId(), table);
                    }
                }
            }
        }
    }

    private <T> List<T> readJsonLines(Path path, Class<T> type) throws IOException {
        if (!Files.exists(path)) {
            return new java.util.ArrayList<>();
        }
        rejectSymlink(path);
        List<T> items = new java.util.ArrayList<>();
        try (InputStream in = Files.newInputStream(path);
             MappingIterator<T> iterator = jsonMapper.readerFor(type).readValues(in)) {
            while (iterator.hasNextValue()) {
                items.add(iterator.nextValue());
            }
        }
        return items;
    }

    private void writeYamlAtomically(Path path, Object value) throws IOException {
        writeAtomically(path, value, yamlMapper);
    }

    private void writeJsonAtomically(Path path, Object value) throws IOException {
        writeAtomically(path, value, jsonMapper);
    }

    private void writeAtomically(Path path, Object value, ObjectMapper mapper) throws IOException {
        Files.createDirectories(path.getParent());
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        mapper.writeValue(temp.toFile(), value);
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path safeResolve(Path root, String component) throws IOException {
        validatePathComponent(component, "path component");
        Path normalizedRoot = root.toAbsolutePath().normalize();
        rejectSymlink(normalizedRoot);
        Path target = normalizedRoot.resolve(component).normalize();
        if (!target.startsWith(normalizedRoot)) {
            throw new IOException("Path escapes workspace root: " + component);
        }
        Path current = normalizedRoot;
        for (Path part : normalizedRoot.relativize(target)) {
            current = current.resolve(part);
            rejectSymlink(current);
        }
        return target;
    }

    private static void validatePathComponent(String value, String label) throws IOException {
        if (value == null || value.isBlank() || ".".equals(value) || "..".equals(value)
                || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 || Path.of(value).isAbsolute()) {
            throw new IOException("Invalid " + label + ": " + value);
        }
        // Windows 保留字符在 POSIX 下合法，不拦截会导致工作区只能在 macOS/Linux 上保存
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || ":*?\"<>|".indexOf(c) >= 0) {
                throw new IOException("Invalid " + label + " for cross-platform storage: " + value);
            }
        }
    }

    private void rejectSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("Symbolic links are not allowed in workspace paths: " + path);
        }
    }

}
