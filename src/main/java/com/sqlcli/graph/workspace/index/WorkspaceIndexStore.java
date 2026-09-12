package com.sqlcli.graph.workspace.index;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class WorkspaceIndexStore {
    static final int DEFAULT_MAX_SHARD_BYTES = 1024 * 1024;
    private static final Logger log = LoggerFactory.getLogger(WorkspaceIndexStore.class);
    private static final DateTimeFormatter GENERATION_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneOffset.UTC);

    private final ObjectMapper mapper;
    private final Path baseRoot;
    private final int maxShardBytes;

    public WorkspaceIndexStore() {
        this(null, DEFAULT_MAX_SHARD_BYTES);
    }

    public WorkspaceIndexStore(Path baseRoot) {
        this(baseRoot, DEFAULT_MAX_SHARD_BYTES);
    }

    public WorkspaceIndexStore(Path baseRoot, int maxShardBytes) {
        this.baseRoot = baseRoot;
        this.maxShardBytes = Math.max(1024, maxShardBytes);
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public void save(String alias, WorkspaceIndexSnapshot snapshot) throws IOException {
        Path indexDir = indexDir(alias);
        Files.createDirectories(indexDir);
        String generation = GENERATION_FORMAT.format(Instant.now()) + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        List<String> shardNames = writeShards(indexDir, generation, snapshot.getDocuments());

        WorkspaceIndexManifest manifest = toManifest(snapshot, shardNames);
        Path manifestTemp = indexDir.resolve("manifest.json.tmp");
        try {
            mapper.writeValue(manifestTemp.toFile(), manifest);
            moveAtomically(manifestTemp, manifestPath(alias));
            cleanupObsoleteFiles(indexDir, Set.copyOf(shardNames));
        } finally {
            Files.deleteIfExists(manifestTemp);
        }
    }

    public WorkspaceIndexSnapshot load(String alias) throws IOException {
        if (!Files.exists(manifestPath(alias))) {
            return null;
        }
        WorkspaceIndexManifest manifest = loadManifest(alias);
        WorkspaceIndexSnapshot snapshot = new WorkspaceIndexSnapshot();
        snapshot.setAlias(manifest.getAlias());
        snapshot.setVersion(manifest.getVersion());
        snapshot.setBuiltAt(manifest.getBuiltAt());
        snapshot.setSourceRevision(manifest.getSourceRevision());
        for (String shard : manifest.getShards()) {
            Path shardPath = resolveShardPath(alias, shard);
            snapshot.getDocuments().addAll(mapper.readValue(
                    shardPath.toFile(), new TypeReference<List<WorkspaceIndexDocument>>() {}));
        }
        return snapshot;
    }

    public WorkspaceIndexManifest loadManifest(String alias) throws IOException {
        if (!Files.exists(manifestPath(alias))) {
            return null;
        }
        return mapper.readValue(manifestPath(alias).toFile(), WorkspaceIndexManifest.class);
    }

    public boolean exists(String alias) {
        return Files.exists(manifestPath(alias));
    }

    /**
     * 获取索引状态。
     *
     * @param alias           数据库别名
     * @param currentRevision 当前 workspace 的 revision
     * @return IndexStatus
     */
    public IndexStatus getIndexStatus(String alias, long currentRevision) {
        if (!exists(alias)) {
            return IndexStatus.missing;
        }

        try {
            WorkspaceIndexManifest manifest = loadManifest(alias);
            if (manifest == null) {
                return IndexStatus.missing;
            }

            if (manifest.getDocumentCount() == 0) {
                return IndexStatus.failed;
            }

            if (manifest.getSourceRevision() == currentRevision) {
                return IndexStatus.ready;
            }

            if (manifest.getSourceRevision() < currentRevision) {
                return IndexStatus.stale;
            }

            // sourceRevision > currentRevision 不应发生，视为 stale
            return IndexStatus.stale;
        } catch (IOException e) {
            log.warn("Failed to load index manifest for status check: {}", alias, e);
            return IndexStatus.failed;
        }
    }

    public Path indexPath(String alias) {
        return manifestPath(alias);
    }

    private List<String> writeShards(Path indexDir, String generation, List<WorkspaceIndexDocument> documents)
            throws IOException {
        List<String> names = new ArrayList<>();
        List<WorkspaceIndexDocument> shard = new ArrayList<>();
        int shardNo = 1;
        int shardBytes = 2;
        for (WorkspaceIndexDocument document : documents) {
            int documentBytes = mapper.writeValueAsBytes(document).length;
            if (documentBytes + 2 > maxShardBytes) {
                throw new IOException("Index document exceeds shard size limit: " + document.getId());
            }
            int separatorBytes = shard.isEmpty() ? 0 : 1;
            if (!shard.isEmpty() && shardBytes + separatorBytes + documentBytes > maxShardBytes) {
                names.add(writeShard(indexDir, generation, shardNo++, shard));
                shard = new ArrayList<>();
                shardBytes = 2;
            }
            shard.add(document);
            shardBytes += (shard.size() == 1 ? 0 : 1) + documentBytes;
        }
        if (!shard.isEmpty() || documents.isEmpty()) {
            names.add(writeShard(indexDir, generation, shardNo, shard));
        }
        return names;
    }

    private String writeShard(Path indexDir, String generation, int shardNo,
            List<WorkspaceIndexDocument> documents) throws IOException {
        String name = "documents-" + generation + "-" + String.format("%05d", shardNo) + ".json";
        Path target = indexDir.resolve(name);
        Path temp = target.resolveSibling(name + ".tmp");
        try {
            mapper.writeValue(temp.toFile(), documents);
            moveAtomically(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
        return name;
    }

    private WorkspaceIndexManifest toManifest(WorkspaceIndexSnapshot snapshot, List<String> shardNames) {
        WorkspaceIndexManifest manifest = new WorkspaceIndexManifest();
        manifest.setAlias(snapshot.getAlias());
        manifest.setVersion(3);
        manifest.setBuiltAt(snapshot.getBuiltAt());
        manifest.setDocumentCount(snapshot.getDocuments().size());
        manifest.setTableCount(snapshot.tableCount());
        manifest.setColumnCount(snapshot.columnCount());
        manifest.setTermCount(snapshot.termCount());
        manifest.setSourceRevision(snapshot.getSourceRevision());
        manifest.setMaxShardBytes(maxShardBytes);
        manifest.setShards(new ArrayList<>(shardNames));
        return manifest;
    }

    private Path resolveShardPath(String alias, String shard) throws IOException {
        Path indexDir = indexDir(alias).toAbsolutePath().normalize();
        Path path = indexDir.resolve(shard).normalize();
        if (!path.getParent().equals(indexDir) || !path.getFileName().toString().startsWith("documents-")) {
            throw new IOException("Invalid index shard path: " + shard);
        }
        return path;
    }

    private void cleanupObsoleteFiles(Path indexDir, Set<String> activeShards) throws IOException {
        try (var stream = Files.list(indexDir)) {
            List<Path> obsolete = stream
                    .filter(path -> path.getFileName().toString().startsWith("documents-"))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !activeShards.contains(path.getFileName().toString()))
                    .sorted(Comparator.naturalOrder())
                    .collect(Collectors.toList());
            for (Path path : obsolete) {
                Files.deleteIfExists(path);
            }
        }
    }

    private Path manifestPath(String alias) {
        return indexDir(alias).resolve("manifest.json");
    }

    private Path indexDir(String alias) {
        return workspacePath(alias).resolve("index");
    }

    private Path workspacePath(String alias) {
        return baseRoot != null ? baseRoot.resolve(alias) : GraphWorkspaceStore.getWorkspacePath(alias);
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
