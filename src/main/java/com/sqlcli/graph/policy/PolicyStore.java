package com.sqlcli.graph.policy;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sqlcli.runstate.RunStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 规则评估的存储。
 *
 * <p><b>评估结果在运行库里，规则与豁免在文件里</b>——这条分界不是随手划的：
 * 评估结果是运行记录（有起止时间、状态、actor，要按规则/表/时间查、要能清理），
 * 和 {@code sql_execution} / {@code task_run} 是同一类东西；而规则集与豁免是
 * <em>人编辑</em>的配置，可读可 diff 可进 Git，和 {@code aliases.yaml} 同理。
 *
 * <p>原来两者都在 {@code policy/runs/} 下按目录存：3 轮评估就 2MB、5 万行违规，
 * 而 {@code listEvaluations} 每次全目录扫——和当初执行历史 JSONL 一模一样的病。
 */
public class PolicyStore {
    private static final Logger log = LoggerFactory.getLogger(PolicyStore.class);

    /** 一次性导入的标记；存在就说明老的 {@code policy/runs/} 已经搬完。 */
    private static final String IMPORT_MARKER = ".runs-imported";

    private final GraphWorkspaceStore workspaceStore;
    private final RunStateStore runState;
    private final ObjectMapper json = configured(new ObjectMapper());
    private final ObjectMapper yaml = configured(new ObjectMapper(new YAMLFactory()));

    public PolicyStore(GraphWorkspaceStore workspaceStore) {
        this(workspaceStore, new RunStateStore());
    }

    public PolicyStore(GraphWorkspaceStore workspaceStore, RunStateStore runState) {
        this.workspaceStore = workspaceStore;
        this.runState = runState;
    }

    public void saveRun(String alias, RuleEvaluation evaluation, RuleSet ruleSet,
            List<PolicyViolation> violations) throws IOException {
        validateId(evaluation.getId(), "evaluation id");
        importLegacyRuns(alias);
        if (runState.policyRunExists(evaluation.getId())) {
            throw new IOException("evaluation already exists: " + evaluation.getId());
        }
        runState.savePolicyRun(alias, evaluation.getId(), name(evaluation.getStatus()),
                evaluation.getRuleSetId(), startedAtMillis(evaluation),
                json.writeValueAsString(evaluation), yaml.writeValueAsString(ruleSet),
                violationRows(violations));
    }

    public PolicyRun loadRun(String alias, String evaluationId) throws IOException {
        validateId(evaluationId, "evaluation id");
        importLegacyRuns(alias);
        RunStateStore.PolicyRunRow row = runState.loadPolicyRun(evaluationId);
        if (row == null) {
            throw new IOException("evaluation not found: " + evaluationId);
        }
        List<PolicyViolation> violations = new ArrayList<>();
        for (String payload : row.violations()) {
            violations.add(json.readValue(payload, PolicyViolation.class));
        }
        return new PolicyRun(json.readValue(row.evaluationJson(), RuleEvaluation.class),
                yaml.readValue(row.ruleSetYaml(), RuleSet.class), violations);
    }

    public List<RuleEvaluation> listEvaluations(String alias) throws IOException {
        importLegacyRuns(alias);
        List<RuleEvaluation> result = new ArrayList<>();
        for (String payload : runState.listPolicyEvaluations(alias)) {
            result.add(json.readValue(payload, RuleEvaluation.class));
        }
        return result;
    }

    private List<RunStateStore.PolicyViolationRow> violationRows(List<PolicyViolation> violations)
            throws IOException {
        List<RunStateStore.PolicyViolationRow> rows = new ArrayList<>();
        for (PolicyViolation violation : violations) {
            rows.add(new RunStateStore.PolicyViolationRow(violation.getId(), violation.getRuleId(),
                    violation.getTargetId(), name(violation.getSeverity()), name(violation.getStatus()),
                    json.writeValueAsString(violation)));
        }
        return rows;
    }

    /** 排序键。评估没有开始时间是坏数据，给 0 让它沉到列表最后，而不是抛异常挡住整页。 */
    private static long startedAtMillis(RuleEvaluation evaluation) {
        return evaluation.getStartedAt() == null ? 0L
                : evaluation.getStartedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    /**
     * 把升级前留在 {@code policy/runs/} 下的评估目录搬进运行库，每个别名一次。
     *
     * <p>老目录<b>不删</b>——和当初 JSONL 历史的处置一致：搬完还留着，
     * 确认没问题再由人删。写标记文件只是免得每次列表都去扫一遍目录。
     *
     * <p>整个过程失败不抛：搬的是历史数据，搬不动不该让规则页打不开。
     */
    private void importLegacyRuns(String alias) {
        try {
            Path root = policyRoot(alias);
            Path marker = root.resolve(IMPORT_MARKER);
            if (Files.exists(marker)) return;
            Path runs = root.resolve("runs");
            if (Files.isDirectory(runs)) {
                int moved = 0;
                try (var stream = Files.list(runs)) {
                    for (Path run : stream.filter(Files::isDirectory)
                            .filter(path -> !path.getFileName().toString().startsWith(".staging-"))
                            .sorted().toList()) {
                        if (importOneRun(alias, run)) moved++;
                    }
                }
                if (moved > 0) {
                    log.info("规则评估迁移完成：别名 {} 的 {} 轮评估已搬进运行库（原目录保留）", alias, moved);
                }
            }
            Files.writeString(marker, "runs migrated to sqlcli.db" + System.lineSeparator());
        } catch (Exception e) {
            log.debug("failed to import legacy policy runs for {}", alias, e);
        }
    }

    /** 单轮评估搬家；已经在库里就跳过（重跑幂等）。 */
    private boolean importOneRun(String alias, Path run) {
        try {
            PolicyRun parsed = readRun(run);
            if (runState.policyRunExists(parsed.evaluation().getId())) return false;
            runState.savePolicyRun(alias, parsed.evaluation().getId(),
                    name(parsed.evaluation().getStatus()), parsed.evaluation().getRuleSetId(),
                    startedAtMillis(parsed.evaluation()),
                    json.writeValueAsString(parsed.evaluation()),
                    yaml.writeValueAsString(parsed.ruleSet()),
                    violationRows(parsed.violations()));
            return true;
        } catch (Exception e) {
            log.warn("规则评估 {} 迁移失败，已跳过：{}", run.getFileName(), e.toString());
            return false;
        }
    }

    public void createWaiver(String alias, RuleWaiver waiver) throws IOException {
        validateId(waiver.getId(), "waiver id");
        Path path = waiverPath(alias, waiver.getId());
        if (Files.exists(path)) {
            throw new IOException("waiver already exists: " + waiver.getId());
        }
        writeWaiver(path, waiver);
    }

    public void updateWaiver(String alias, RuleWaiver waiver) throws IOException {
        Path path = waiverPath(alias, waiver.getId());
        if (!Files.isRegularFile(path)) {
            throw new IOException("waiver not found: " + waiver.getId());
        }
        writeWaiver(path, waiver);
    }

    public RuleWaiver loadWaiver(String alias, String id) throws IOException {
        Path path = waiverPath(alias, id);
        if (!Files.isRegularFile(path)) {
            throw new IOException("waiver not found: " + id);
        }
        rejectSymlink(path);
        return yaml.readValue(path.toFile(), RuleWaiver.class);
    }

    public List<RuleWaiver> listWaivers(String alias) throws IOException {
        Path dir = policyRoot(alias).resolve("waivers");
        if (!Files.exists(dir)) {
            return List.of();
        }
        List<RuleWaiver> result = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path path : stream.filter(file -> file.getFileName().toString().endsWith(".yaml"))
                    .sorted().toList()) {
                rejectSymlink(path);
                result.add(yaml.readValue(path.toFile(), RuleWaiver.class));
            }
        }
        result.sort(Comparator.comparing(RuleWaiver::getCreatedAt).reversed());
        return result;
    }

    private PolicyRun readRun(Path run) throws IOException {
        rejectSymlink(run);
        Path evaluationPath = run.resolve("evaluation.json");
        Path rulesetPath = run.resolve("ruleset.yaml");
        Path violationsPath = run.resolve("violations.jsonl");
        if (!Files.isRegularFile(evaluationPath) || !Files.isRegularFile(rulesetPath)
                || !Files.isRegularFile(violationsPath)) {
            throw new IOException("incomplete policy evaluation: " + run.getFileName());
        }
        rejectSymlink(evaluationPath);
        rejectSymlink(rulesetPath);
        rejectSymlink(violationsPath);
        RuleEvaluation evaluation = json.readValue(evaluationPath.toFile(), RuleEvaluation.class);
        RuleSet ruleSet = yaml.readValue(rulesetPath.toFile(), RuleSet.class);
        List<PolicyViolation> violations = new ArrayList<>();
        try (MappingIterator<PolicyViolation> iterator = json.readerFor(PolicyViolation.class)
                .readValues(violationsPath.toFile())) {
            while (iterator.hasNextValue()) {
                violations.add(iterator.nextValue());
            }
        }
        return new PolicyRun(evaluation, ruleSet, violations);
    }

    private Path waiverPath(String alias, String id) throws IOException {
        validateId(id, "waiver id");
        Path dir = policyRoot(alias).resolve("waivers");
        Files.createDirectories(dir);
        rejectSymlink(dir);
        Path path = dir.resolve(id + ".yaml").normalize();
        ensureChild(dir, path);
        return path;
    }

    private void writeWaiver(Path path, RuleWaiver waiver) throws IOException {
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        yaml.writeValue(temp.toFile(), waiver);
        yaml.readValue(temp.toFile(), RuleWaiver.class);
        move(temp, path);
    }

    private Path policyRoot(String alias) throws IOException {
        Path root = workspaceStore.workspacePath(alias).toAbsolutePath().normalize();
        Path policy = root.resolve("policy").normalize();
        ensureChild(root, policy);
        Files.createDirectories(policy);
        rejectSymlink(policy);
        return policy;
    }

    private void ensureChild(Path root, Path path) throws IOException {
        if (!path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) {
            throw new IOException("path escapes policy root: " + path);
        }
    }

    private void validateId(String id, String label) throws IOException {
        if (id == null || !id.matches("[A-Za-z0-9._-]+")) {
            throw new IOException("invalid " + label + ": " + id);
        }
    }

    private void rejectSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("symbolic links are not allowed: " + path);
        }
    }

    private void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }


    private static ObjectMapper configured(ObjectMapper mapper) {
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        return mapper;
    }
}
