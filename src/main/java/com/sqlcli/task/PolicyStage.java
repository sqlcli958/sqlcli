package com.sqlcli.task;

import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.graph.policy.PolicyCheckResult;
import com.sqlcli.graph.policy.PolicyEnforcement;
import com.sqlcli.graph.policy.PolicyService;
import com.sqlcli.graph.policy.PolicyViolation;
import com.sqlcli.graph.policy.PolicyViolationStatus;
import com.sqlcli.graph.review.CandidateDdlProjector;
import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * DDL 执行门：复用设计审查的已启用规则，required / blocking 拒绝并返回修法，
 * advisory 作为提示返回且写入规则审计。
 *
 * <p>没有图谱或没绑规则集时放行。已启用规则时，解析或评估失败必须拒绝执行：
 * 「没查」不能当成「没违规」。
 */
final class PolicyStage implements SqlTaskStage {
    private static final Logger log = LoggerFactory.getLogger(PolicyStage.class);
    private static final Set<String> DDL = Set.of("CREATE", "ALTER", "DROP", "TRUNCATE");

    private final GraphWorkspaceStore workspaceStore;
    private final CandidateDdlProjector projector = new CandidateDdlProjector();

    PolicyStage(GraphWorkspaceStore workspaceStore) {
        this.workspaceStore = workspaceStore;
    }

    @Override
    public String name() {
        return "policy";
    }

    @Override
    public void before(SqlTaskContext ctx) throws SqlTaskRejected {
        if (!isDdl(ctx.sqlType)) return;
        ctx.notices.addAll(checkDdl(ctx.config, ctx.sql, ctx.request.origin().name()));
    }

    static boolean isDdl(String sqlType) {
        return DDL.contains(sqlType);
    }

    /** 单条和批次共用；批次在连接数据库之前投影整批 DDL，避免前半批已经隐式提交。 */
    List<String> checkDdl(DatabaseConfig config, String sql, String sourceRef) throws SqlTaskRejected {
        String alias = config.getAliasName();
        List<String> notices = new ArrayList<>();
        List<PolicyCheckResult> results;
        try {
            if (alias == null || !workspaceStore.exists(alias)) return notices;
            GraphWorkspace workspace = workspaceStore.load(alias);
            PolicyService service = new PolicyService(workspaceStore);
            if (service.boundDesignRules(alias, workspace).isEmpty()) return notices;
            CandidateDdlProjector.ProjectionResult projection = projector.projectText(
                    workspace, sql, sourceRef, defaultSchema(config));
            if (!projection.diagnostics().isEmpty()) {
                throw new IllegalArgumentException(projection.diagnostics().get(0).message());
            }
            results = service.checkBoundCandidate(
                    alias, GraphActor.agent, projection.workspace(), input(sql, sourceRef, projection));
        } catch (Exception e) {
            log.debug("policy stage failed for {}", alias, e);
            throw new SqlTaskRejected("DDL 规则检查未完成，未执行：" + e.getMessage());
        }
        List<String> blocking = new ArrayList<>();
        for (PolicyCheckResult result : results) {
            if (result.exitCode() == 2) {
                blocking.add("规则集 " + result.evaluation().getRuleSetId() + " 检查未完成："
                        + (result.evaluation().getErrorMessage() == null ? "工作区已变化，请重试"
                                : result.evaluation().getErrorMessage()));
                continue;
            }
            for (PolicyViolation violation : result.violations()) {
                if (violation.getStatus() != PolicyViolationStatus.open) continue;
                String line = describe(violation);
                if (violation.getEnforcement() == PolicyEnforcement.advisory) {
                    notices.add("规则提示 " + line);
                } else {
                    blocking.add(line);
                }
            }
        }
        if (!blocking.isEmpty()) {
            throw new SqlTaskRejected("DDL 违反结构规则，未执行：\n  " + String.join("\n  ", blocking)
                    + "\n规则在 Web UI 规则页维护；确需例外用 schema policy waiver add。");
        }
        return notices;
    }

    /** {@code [rule] target: message → remediation}，修法跟着违规走，只说「这里不对」的报告没人动。 */
    private static String describe(PolicyViolation violation) {
        StringBuilder sb = new StringBuilder("[").append(violation.getRuleId()).append("] ");
        String target = violation.getTargetId();
        if (target != null) {
            String[] parts = target.split(":");
            sb.append(parts.length >= 3 ? parts[2] : target).append(": ");
        }
        sb.append(violation.getMessage());
        if (violation.getRemediation() != null && !violation.getRemediation().isBlank()) {
            sb.append(" → ").append(violation.getRemediation());
        }
        return sb.toString();
    }

    /**
     * 评估输入：变更集让结构规则只查这次碰到的表；{@code sqlStatements} 给空让
     * dangerous_dml_guard 不把 DDL 当 DML 解析；{@code sql} 给方言类规则。
     */
    private static Map<String, Object> input(String sql, String sourceRef,
            CandidateDdlProjector.ProjectionResult projection) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("reviewType", "execute");
        input.put("inputType", "ddl");
        input.put("inputRef", sourceRef);
        input.put("sql", sql);
        input.put("ddlSql", sql);
        input.put("sqlStatements", List.of());
        input.put("changes", projection.changes().stream().map(change -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("changeType", change.changeType());
            item.put("objectType", change.objectType());
            item.put("targetId", change.targetId());
            item.put("sourceRef", change.sourceRef());
            item.put("destructive", change.destructive());
            if (change.before() != null) item.put("before", change.before());
            if (change.after() != null) item.put("after", change.after());
            return item;
        }).toList());
        return input;
    }

    /** 不带前缀的 CREATE TABLE 落在连接自己的库里：MySQL 是 database，Oracle 是用户，PG 是 public。 */
    static String defaultSchema(DatabaseConfig config) {
        String type = config.getType() == null ? "" : config.getType().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "oracle" -> config.getUsername() == null ? null : config.getUsername().toUpperCase(Locale.ROOT);
            case "postgresql", "postgres" -> "public";
            default -> config.getDatabase();
        };
    }
}
