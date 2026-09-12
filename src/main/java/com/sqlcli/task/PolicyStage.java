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
 * DDL 门：CREATE / ALTER / DROP 执行之前，先按别名绑定的结构规则查一遍。
 *
 * <p>这是结构规则唯一不靠 agent 自觉的消费点。{@code schema design review} 一直在，
 * 但真实 DDL 走过它的次数是 0——没人会在写表之前先想起来跑一条评审命令。
 * 门开在执行链路上，agent 写表那一刻就被查，required / blocking 的违规直接拒绝并把
 * 修法一起还回去；advisory 的附在结果里（{@link SqlTaskContext#notices}）。
 *
 * <p>三种情况静默放行：别名没有图谱、没绑规则集、解析不了这条 DDL（视图、存储过程）。
 * 前两种是「没开」，第三种会留一条提示——不说的话「没违规」和「根本没查」长得一样。
 */
final class PolicyStage implements SqlTaskStage {
    private static final Logger log = LoggerFactory.getLogger(PolicyStage.class);
    private static final Set<String> DDL = Set.of("CREATE", "ALTER", "DROP");

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
        if (!DDL.contains(ctx.sqlType)) return;
        String alias = ctx.config.getAliasName();
        List<PolicyCheckResult> results;
        try {
            if (alias == null || !workspaceStore.exists(alias)) return;
            GraphWorkspace workspace = workspaceStore.load(alias);
            CandidateDdlProjector.ProjectionResult projection = projector.projectText(
                    workspace, ctx.sql, ctx.request.origin().name(), defaultSchema(ctx.config));
            if (!projection.diagnostics().isEmpty()) {
                ctx.notices.add("规则检查未执行：" + projection.diagnostics().get(0).message());
                return;
            }
            results = new PolicyService(workspaceStore).checkBoundCandidate(
                    alias, GraphActor.agent, projection.workspace(), input(ctx, projection));
        } catch (Exception e) {
            // 规则检查自己坏了不该挡住 DDL——但要说出来，这是「没查」不是「没违规」
            log.debug("policy stage skipped for {}", alias, e);
            ctx.notices.add("规则检查未执行：" + e.getMessage());
            return;
        }
        List<String> blocking = new ArrayList<>();
        for (PolicyCheckResult result : results) {
            if (result.evaluation().getErrorMessage() != null) {
                ctx.notices.add("规则集 " + result.evaluation().getRuleSetId() + " 未能评估："
                        + result.evaluation().getErrorMessage());
                continue;
            }
            for (PolicyViolation violation : result.violations()) {
                if (violation.getStatus() != PolicyViolationStatus.open) continue;
                String line = describe(violation);
                if (violation.getEnforcement() == PolicyEnforcement.advisory) {
                    ctx.notices.add("规则提示 " + line);
                } else {
                    blocking.add(line);
                }
            }
        }
        if (!blocking.isEmpty()) {
            throw new SqlTaskRejected("DDL 违反结构规则，未执行：\n  " + String.join("\n  ", blocking)
                    + "\n规则在 Web UI 规则页维护；确需例外用 schema policy waiver add。");
        }
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
    private static Map<String, Object> input(SqlTaskContext ctx,
            CandidateDdlProjector.ProjectionResult projection) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("reviewType", "execute");
        input.put("inputType", "ddl");
        input.put("inputRef", ctx.request.origin().name());
        input.put("sql", ctx.sql);
        input.put("ddlSql", ctx.sql);
        input.put("sqlStatements", List.of());
        input.put("changes", projection.changes().stream().map(change -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("changeType", change.changeType());
            item.put("objectType", change.objectType());
            item.put("targetId", change.targetId());
            item.put("sourceRef", change.sourceRef());
            item.put("destructive", change.destructive());
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
