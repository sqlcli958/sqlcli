package com.sqlcli.graph.policy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Manages editable policy rule-set files under one workspace. */
public class PolicyRuleSetManager {
    private final GraphWorkspaceStore workspaceStore;
    private final RuleSetLoader loader;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public PolicyRuleSetManager(GraphWorkspaceStore workspaceStore) {
        this(workspaceStore, new RuleSetLoader());
    }

    PolicyRuleSetManager(GraphWorkspaceStore workspaceStore, RuleSetLoader loader) {
        this.workspaceStore = workspaceStore;
        this.loader = loader;
    }

    public List<RuleSetFile> list(String alias) throws IOException {
        Path policy = policyRoot(alias);
        List<String> bindings = bindings(policy);
        Path rules = policy.resolve("rules");
        if (!Files.isDirectory(rules)) {
            return List.of();
        }
        List<RuleSetFile> result = new ArrayList<>();
        try (var files = Files.list(rules)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".yaml"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList()) {
                String fileName = file.getFileName().toString();
                result.add(new RuleSetFile(fileName, loader.load(file), bindings.contains("rules/" + fileName)));
            }
        }
        return List.copyOf(result);
    }

    public RuleSetFile create(String alias, String fileName, RuleSet ruleSet) throws IOException {
        Path file = ruleFile(alias, fileName);
        if (Files.exists(file)) {
            throw new IllegalArgumentException("rule set already exists: " + fileName);
        }
        write(file, ruleSet);
        return new RuleSetFile(fileName, ruleSet, false);
    }

    public RuleSetFile update(String alias, String fileName, RuleSet ruleSet) throws IOException {
        Path file = ruleFile(alias, fileName);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("rule set not found: " + fileName);
        }
        write(file, ruleSet);
        return new RuleSetFile(fileName, ruleSet, bindings(policyRoot(alias)).contains("rules/" + fileName));
    }

    public void delete(String alias, String fileName) throws IOException {
        Path file = ruleFile(alias, fileName);
        if (!Files.deleteIfExists(file)) {
            throw new IllegalArgumentException("rule set not found: " + fileName);
        }
        setBound(alias, fileName, false);
    }

    public void setBound(String alias, String fileName, boolean enabled) throws IOException {
        ruleFile(alias, fileName);
        Path policy = policyRoot(alias);
        List<String> values = new ArrayList<>(bindings(policy));
        String ref = "rules/" + fileName;
        values.removeIf(ref::equals);
        if (enabled) {
            values.add(ref);
        }
        writeYaml(policy.resolve("bindings.yaml"), Map.of("ruleSets", values));
    }

    private Path policyRoot(String alias) throws IOException {
        Path root = workspaceStore.workspacePath(alias).toAbsolutePath().normalize();
        Path policy = root.resolve("policy").normalize();
        if (!policy.startsWith(root)) {
            throw new IOException("invalid policy path");
        }
        Files.createDirectories(policy.resolve("rules"));
        return policy;
    }

    private Path ruleFile(String alias, String fileName) throws IOException {
        if (fileName == null || !fileName.matches("[A-Za-z0-9][A-Za-z0-9._-]*\\.yaml")) {
            throw new IllegalArgumentException("invalid rule file name");
        }
        Path rules = policyRoot(alias).resolve("rules");
        Path file = rules.resolve(fileName).normalize();
        if (!file.startsWith(rules)) {
            throw new IllegalArgumentException("invalid rule file name");
        }
        return file;
    }

    private List<String> bindings(Path policy) throws IOException {
        Path file = policy.resolve("bindings.yaml");
        if (!Files.exists(file)) {
            return List.of();
        }
        Map<String, Object> root = yaml.readValue(file.toFile(), new TypeReference<LinkedHashMap<String, Object>>() { });
        Object configured = root == null ? null : root.get("ruleSets");
        if (!(configured instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().map(String::valueOf).toList();
    }

    private void write(Path file, RuleSet ruleSet) throws IOException {
        loader.validate(ruleSet);
        writeYaml(file, ruleSet);
    }

    private void writeYaml(Path file, Object value) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        yaml.writeValue(temporary.toFile(), value);
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public record RuleSetFile(String fileName, RuleSet ruleSet, boolean enabled) { }
}
