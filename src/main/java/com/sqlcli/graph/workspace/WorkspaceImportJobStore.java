package com.sqlcli.graph.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

public class WorkspaceImportJobStore {
    private final ObjectMapper yamlMapper;
    private final ObjectMapper jsonMapper;
    private final Path baseRoot;

    public WorkspaceImportJobStore() {
        this(null);
    }

    public WorkspaceImportJobStore(Path baseRoot) {
        this.baseRoot = baseRoot;
        yamlMapper = new ObjectMapper(new YAMLFactory());
        yamlMapper.registerModule(new JavaTimeModule());
        yamlMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        yamlMapper.enable(SerializationFeature.INDENT_OUTPUT);

        jsonMapper = new ObjectMapper();
        jsonMapper.registerModule(new JavaTimeModule());
        jsonMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        jsonMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public boolean hasCurrentJob(String alias) {
        return Files.exists(currentJobPath(alias));
    }

    public ImportJob loadCurrentJob(String alias) throws IOException {
        return yamlMapper.readValue(currentJobPath(alias).toFile(), ImportJob.class);
    }

    public void saveCurrentJob(String alias, ImportJob job) throws IOException {
        ensureLayout(alias);
        yamlMapper.writeValue(currentJobPath(alias).toFile(), job);
    }

    public void saveTasks(String alias, String jobId, List<ImportTask> tasks) throws IOException {
        ensureLayout(alias);
        jsonMapper.writeValue(tasksPath(alias, jobId).toFile(), tasks);
    }

    public List<ImportTask> loadTasks(String alias, String jobId) throws IOException {
        Path path = tasksPath(alias, jobId);
        if (!Files.exists(path)) {
            // 兼容转义前写入的文件（POSIX 上 ':' 是合法文件名字符）
            Path legacy = tasksDir(alias).resolve(jobId + ".json");
            if (!Files.exists(legacy)) {
                return List.of();
            }
            path = legacy;
        }
        return List.of(jsonMapper.readValue(path.toFile(), ImportTask[].class));
    }

    public void archiveCurrentJob(String alias, ImportJob job) throws IOException {
        ensureLayout(alias);
        Path target = historyPath(alias).resolve(safeFileName(job.getJobId()) + ".yaml");
        yamlMapper.writeValue(target.toFile(), job);
    }

    public void reset(String alias) throws IOException {
        Path jobs = jobsRoot(alias);
        if (!Files.exists(jobs)) {
            return;
        }
        try (var stream = Files.walk(jobs)) {
            stream.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(jobs))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
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

    private void ensureLayout(String alias) throws IOException {
        Files.createDirectories(historyPath(alias));
        Files.createDirectories(tasksDir(alias));
    }

    private Path currentJobPath(String alias) {
        return jobsRoot(alias).resolve("import-current.yaml");
    }

    private Path historyPath(String alias) {
        return jobsRoot(alias).resolve("import-history");
    }

    private Path tasksDir(String alias) {
        return jobsRoot(alias).resolve("tasks");
    }

    private Path tasksPath(String alias, String jobId) {
        return tasksDir(alias).resolve(safeFileName(jobId) + ".json");
    }

    /**
     * jobId 形如 "import:&lt;alias&gt;:&lt;timestamp&gt;"，其中 ':' 在 Windows 上是非法文件名字符
     * （POSIX 下合法，所以这里必须转义才能跨平台落盘）。
     */
    private String safeFileName(String jobId) {
        return jobId == null ? "" : jobId.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private Path jobsRoot(String alias) {
        Path root = baseRoot != null ? baseRoot.resolve(alias) : GraphWorkspaceStore.getWorkspacePath(alias);
        return root.resolve("jobs");
    }
}
