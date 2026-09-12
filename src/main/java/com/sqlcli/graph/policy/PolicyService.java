package com.sqlcli.graph.policy;

import com.sqlcli.graph.ui.service.WorkspaceLockManager;
import com.sqlcli.graph.ui.service.WriteLockGuard;
import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PolicyService {
    private final GraphWorkspaceStore workspaceStore;
    private final PolicyStore policyStore;
    private final RuleSetLoader loader;
    private final PolicyEvaluator evaluator;
    private final WorkspaceLockManager lockManager;

    public PolicyService(GraphWorkspaceStore workspaceStore) {
        this(workspaceStore, new PolicyStore(workspaceStore), new RuleSetLoader(),
                new PolicyEvaluator(), new WorkspaceLockManager());
    }

    public PolicyService(GraphWorkspaceStore workspaceStore, PolicyStore policyStore, RuleSetLoader loader,
            PolicyEvaluator evaluator, WorkspaceLockManager lockManager) {
        this.workspaceStore = workspaceStore;
        this.policyStore = policyStore;
        this.loader = loader;
        this.evaluator = evaluator;
        this.lockManager = lockManager;
    }

    public PolicyCheckResult check(String alias, Path rulesPath, GraphActor actor) {
        return check(alias, rulesPath, actor, Map.of());
    }

    public PolicyCheckResult checkBuiltin(String alias, String ref, GraphActor actor, Map<String, ?> input) {
        return check(alias, null, ref, actor, input);
    }

    public List<PolicyCheckResult> checkBound(String alias, GraphActor actor, Map<String, ?> input)
            throws Exception {
        List<PolicyCheckResult> results = new ArrayList<>();
        for (Path path : loader.boundRulePaths(workspaceStore.workspacePath(alias))) {
            results.add(check(alias, path, actor, input));
        }
        return List.copyOf(results);
    }

    public PolicyCheckResult check(String alias, Path rulesPath, GraphActor actor, Map<String, ?> input) {
        return check(alias, rulesPath, null, actor, input);
    }

    /**
     * 按工作区绑定的每个规则集评估一个候选工作区。执行链路的 DDL 门用它：
     * 没绑规则就是空列表，调用方据此什么都不做。
     */
    public List<PolicyCheckResult> checkBoundCandidate(String alias, GraphActor actor,
            GraphWorkspace candidate, Map<String, ?> input) throws Exception {
        List<PolicyCheckResult> results = new ArrayList<>();
        for (Path path : loader.boundRulePaths(workspaceStore.workspacePath(alias))) {
            results.add(checkCandidate(alias, path, actor, candidate, input));
        }
        return List.copyOf(results);
    }

    /** Evaluate an in-memory review candidate without saving it as the current workspace. */
    public PolicyCheckResult checkCandidate(String alias, Path rulesPath, GraphActor actor,
            GraphWorkspace candidate, Map<String, ?> input) {
        return check(alias, rulesPath, null, actor, input, candidate);
    }

    private PolicyCheckResult check(String alias, Path rulesPath, String builtinRef,
            GraphActor actor, Map<String, ?> input) {
        return check(alias, rulesPath, builtinRef, actor, input, null);
    }

    private PolicyCheckResult check(String alias, Path rulesPath, String builtinRef,
            GraphActor actor, Map<String, ?> input, GraphWorkspace candidate) {
        RuleEvaluation evaluation = new RuleEvaluation();
        evaluation.setId("evaluation-" + UUID.randomUUID());
        evaluation.setSourceAlias(alias);
        evaluation.setActor(actor == GraphActor.agent ? GraphActor.agent : GraphActor.human);
        evaluation.setReviewType(inputString(input, "reviewType"));
        evaluation.setInputType(inputString(input, "inputType"));
        evaluation.setInputRef(inputString(input, "inputRef"));
        evaluation.setInputHash(input.isEmpty() ? null : PolicyHashes.object(input));
        for (String name : List.of("ddl", "migration", "rollback", "precheck", "postcheck")) {
            Object sql = input.get(name + "Sql");
            Object ref = input.get(name + "Ref");
            if (sql != null) evaluation.getInputHashes().put(name, PolicyHashes.object(sql));
            if (ref != null) evaluation.getInputRefs().put(name, String.valueOf(ref));
        }
        evaluation.setStartedAt(LocalDateTime.now());
        RuleSet ruleSet = null;
        try (WriteLockGuard ignored = lockManager.acquireWriteLock(
                alias, workspaceStore.workspacePath(alias), 30_000)) {
            GraphWorkspace workspace = workspaceStore.load(alias);
            if (candidate != null
                    && candidate.getManifest().getRevision() != workspace.getManifest().getRevision()) {
                throw new IllegalStateException("workspace changed while preparing review; retry the command");
            }
            GraphWorkspace evaluatedWorkspace = candidate == null ? workspace : candidate;
            evaluation.setWorkspaceRevision(workspace.getManifest().getRevision());
            evaluation.setDbType(evaluatedWorkspace.getDataSource().getDbType());
            evaluation.setProductVersion(evaluatedWorkspace.getDataSource().getProductVersion());
            ruleSet = builtinRef == null ? loader.load(rulesPath) : loader.loadBuiltin(builtinRef);
            evaluation.setRuleSetId(ruleSet.getId());
            evaluation.setRuleSetVersion(ruleSet.getVersion());
            evaluation.setRuleSetHash(loader.hash(ruleSet));
            boolean matches = evaluator.matchesRuleSet(evaluatedWorkspace, ruleSet);
            evaluation.setEvaluatedRules(matches ? evaluator.evaluatedRules(ruleSet, input) : 0);
            List<RuleWaiver> waivers = policyStore.listWaivers(alias);
            List<PolicyViolation> violations = matches
                    ? evaluator.evaluate(evaluatedWorkspace, ruleSet, waivers, evaluation.getId(), input) : List.of();
            evaluation.setViolationCount(violations.size());
            evaluation.setWaivedCount((int) violations.stream()
                    .filter(item -> item.getStatus() == PolicyViolationStatus.waived).count());
            long currentRevision = workspaceStore.load(alias).getManifest().getRevision();
            boolean stale = currentRevision != evaluation.getWorkspaceRevision();
            boolean open = violations.stream().anyMatch(item -> item.getStatus() == PolicyViolationStatus.open);
            evaluation.setStatus(stale ? PolicyEvaluationStatus.stale : !matches ? PolicyEvaluationStatus.skipped
                    : open ? PolicyEvaluationStatus.violations : PolicyEvaluationStatus.passed);
            evaluation.setFinishedAt(LocalDateTime.now());
            policyStore.saveRun(alias, evaluation, ruleSet, violations);
            return new PolicyCheckResult(evaluation, violations, stale ? 2 : exitCode(violations),
                    matches ? ruleSet.getRules().size() - evaluation.getEvaluatedRules() : 0);
        } catch (Exception e) {
            evaluation.setStatus(PolicyEvaluationStatus.error);
            evaluation.setFinishedAt(LocalDateTime.now());
            evaluation.setErrorMessage(sanitize(e.getMessage()));
            if (ruleSet == null) {
                ruleSet = errorRuleSet(builtinRef == null ? rulesPath.toString() : builtinRef);
                evaluation.setRuleSetId(ruleSet.getId());
                evaluation.setRuleSetVersion(ruleSet.getVersion());
                evaluation.setRuleSetHash(loader.hash(ruleSet));
            }
            try {
                try (WriteLockGuard ignored = lockManager.acquireWriteLock(
                        alias, workspaceStore.workspacePath(alias), 30_000)) {
                    policyStore.saveRun(alias, evaluation, ruleSet, List.of());
                }
            } catch (Exception persistenceError) {
                evaluation.setErrorMessage(evaluation.getErrorMessage() + "; persistence: "
                        + sanitize(persistenceError.getMessage()));
            }
            return new PolicyCheckResult(evaluation, List.of(), 2, 0);
        }
    }

    public RuleWaiver addWaiver(String alias, String ruleId, String targetId, String reason,
            LocalDateTime expiresAt, GraphActor actor) throws Exception {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason is required");
        LocalDateTime now = LocalDateTime.now();
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("expiresAt must be in the future");
        }
        try (WriteLockGuard ignored = lockManager.acquireWriteLock(
                alias, workspaceStore.workspacePath(alias), 30_000)) {
            String ruleSetId = latestRuleSetFor(alias, ruleId);
            RuleWaiver waiver = new RuleWaiver();
            waiver.setId("waiver-" + UUID.randomUUID());
            waiver.setSourceAlias(alias);
            waiver.setRuleSetId(ruleSetId);
            waiver.setRuleId(ruleId);
            waiver.setTargetId(targetId);
            waiver.setReason(reason);
            waiver.setCreatedBy(actor == GraphActor.agent ? GraphActor.agent : GraphActor.human);
            waiver.setCreatedAt(now);
            waiver.setExpiresAt(expiresAt);
            waiver.setUpdatedAt(now);
            policyStore.createWaiver(alias, waiver);
            return waiver;
        }
    }

    public RuleWaiver revokeWaiver(String alias, String id, String reason, GraphActor actor) throws Exception {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason is required");
        try (WriteLockGuard ignored = lockManager.acquireWriteLock(
                alias, workspaceStore.workspacePath(alias), 30_000)) {
            RuleWaiver waiver = policyStore.loadWaiver(alias, id);
            if (waiver.getStatus() == RuleWaiverStatus.revoked) return waiver;
            LocalDateTime now = LocalDateTime.now();
            waiver.setStatus(RuleWaiverStatus.revoked);
            waiver.setRevokedBy(actor == GraphActor.agent ? GraphActor.agent : GraphActor.human);
            waiver.setRevokedAt(now);
            waiver.setRevokeReason(reason);
            waiver.setUpdatedAt(now);
            policyStore.updateWaiver(alias, waiver);
            return waiver;
        }
    }

    public List<RuleEvaluation> listEvaluations(String alias) throws Exception {
        return policyStore.listEvaluations(alias);
    }

    public PolicyRun showEvaluation(String alias, String id) throws Exception {
        return policyStore.loadRun(alias, id);
    }

    public List<PolicyViolation> listViolations(String alias, String evaluationId) throws Exception {
        if (evaluationId != null) return policyStore.loadRun(alias, evaluationId).violations();
        List<PolicyViolation> result = new ArrayList<>();
        for (RuleEvaluation evaluation : policyStore.listEvaluations(alias)) {
            result.addAll(policyStore.loadRun(alias, evaluation.getId()).violations());
        }
        result.sort(java.util.Comparator.comparing(PolicyViolation::getRuleId)
                .thenComparing(PolicyViolation::getTargetId)
                .thenComparing(PolicyViolation::getFingerprint)
                .thenComparing(PolicyViolation::getObservedAt));
        return result;
    }

    public List<RuleWaiver> listWaivers(String alias, boolean activeOnly) throws Exception {
        LocalDateTime now = LocalDateTime.now();
        return policyStore.listWaivers(alias).stream()
                .filter(waiver -> !activeOnly || waiver.isActiveAt(now)).toList();
    }

    /** Current required/blocking policy findings, projected without duplicating policy audit storage. */
    public List<com.sqlcli.graph.workspace.ValidationIssueRecord> currentValidationIssues(String alias)
            throws java.io.IOException {
        GraphWorkspace workspace = workspaceStore.load(alias);
        RuleEvaluation evaluation = policyStore.listEvaluations(alias).stream()
                .filter(item -> item.getWorkspaceRevision() == workspace.getManifest().getRevision())
                .filter(item -> item.getStatus() == PolicyEvaluationStatus.passed
                        || item.getStatus() == PolicyEvaluationStatus.violations)
                .findFirst().orElse(null);
        if (evaluation == null) return List.of();
        return policyStore.loadRun(alias, evaluation.getId()).violations().stream()
                .filter(item -> item.getStatus() == PolicyViolationStatus.open)
                .filter(item -> item.getEnforcement() == PolicyEnforcement.required
                        || item.getEnforcement() == PolicyEnforcement.blocking)
                .map(item -> policyIssue(alias, evaluation, item)).toList();
    }

    private com.sqlcli.graph.workspace.ValidationIssueRecord policyIssue(String alias, RuleEvaluation evaluation,
            PolicyViolation violation) {
        com.sqlcli.graph.workspace.ValidationIssueRecord issue =
                com.sqlcli.graph.workspace.ValidationIssueRecord.create(alias,
                        com.sqlcli.graph.workspace.ValidationSeverity.error,
                        "policy_" + violation.getRuleId(), violation.getMessage(),
                        violation.getTargetId(), violation.getField());
        issue.setProducer("policy");
        issue.setRunId(evaluation.getId());
        issue.setCreatedAt(violation.getObservedAt());
        issue.setUpdatedAt(violation.getObservedAt());
        return issue;
    }

    private int exitCode(List<PolicyViolation> violations) {
        return violations.stream().anyMatch(item -> item.getStatus() == PolicyViolationStatus.open
                && (item.getEnforcement() == PolicyEnforcement.required
                || item.getEnforcement() == PolicyEnforcement.blocking)) ? 1 : 0;
    }

    private String latestRuleSetFor(String alias, String ruleId) throws Exception {
        for (RuleEvaluation evaluation : policyStore.listEvaluations(alias)) {
            PolicyRun run = policyStore.loadRun(alias, evaluation.getId());
            if (run.ruleSet().getRules().stream().anyMatch(rule -> rule.getId().equals(ruleId))) {
                return run.ruleSet().getId();
            }
        }
        throw new IllegalArgumentException("rule not found in policy evaluation history: " + ruleId);
    }

    private RuleSet errorRuleSet(String source) {
        RuleSet ruleSet = new RuleSet();
        ruleSet.setKind("PolicyRuleSet");
        ruleSet.setId("invalid-ruleset");
        ruleSet.setTitle("Invalid ruleset: " + source);
        ruleSet.setVersion("unknown");
        return ruleSet;
    }

    private String sanitize(String message) {
        if (message == null || message.isBlank()) return "policy evaluation failed";
        return message.replaceAll("(?i)(password|secret|token)=[^\\s,;]+", "$1=***");
    }

    private String inputString(Map<String, ?> input, String key) {
        Object value = input.get(key);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }
}
