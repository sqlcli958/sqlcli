package com.sqlcli.graph.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.sqlcli.approval.ApprovalGate;
import com.sqlcli.graph.ui.service.GraphChangePayload;
import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sqlcli.runstate.RunStateStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * agent 总结出来的规则：提交 → 审批 → 写进规则集并启用。
 *
 * <h2>为什么走图谱审批而不是直接写文件</h2>
 * 规则一旦启用就会在执行链路上拦 DDL（{@code PolicyStage}）。agent 从十几张表里归纳出
 * 「每张表都得有 created_at / updated_at」是它擅长的活，但**这条规则要不要成为全库约束**
 * 是人的决定。所以它跟 agent 写进图谱的描述一样：先存进 {@code approval_request.payload}，
 * 批准那一刻才落文件——拒绝就等于没发生过。
 *
 * <p>别名 {@code graphApproval: auto} 时当场写入并留底，跟图谱写入同一条判据——
 * 规则提议不许自己决定要不要审批（CLAUDE.md「一个别名上只能有一种行为」）。
 *
 * <p>payload 的 targetId 是 {@code policy:<alias>:<file>/<ruleId>}，before / after 是这条规则
 * 本身，评审页的 diff 面板照常按字段摊开。
 */
public class PolicyRuleProposals {

    /** 需要 SQL / 变更集输入的类别，归到 SQL 与迁移那份规则集；其余进结构规范。文件名与规则页固定分组一致。 */
    private static final Set<String> SQL_CATEGORIES = Set.of(
            "dangerous_dml_guard", "migration_safety_check", "dialect_sql_pattern");
    static final String STRUCTURE_FILE = "structure.yaml";
    static final String SQL_FILE = "sql-migration.yaml";

    private final GraphWorkspaceStore workspaceStore;
    private final PolicyRuleSetManager manager;
    private final RuleSetLoader loader = new RuleSetLoader();
    private final ApprovalGate approvalGate;
    private final RunStateStore runState;

    public PolicyRuleProposals(GraphWorkspaceStore workspaceStore) {
        this(workspaceStore, new RunStateStore(), new ApprovalGate());
    }

    public PolicyRuleProposals(GraphWorkspaceStore workspaceStore, RunStateStore runState, ApprovalGate approvalGate) {
        this.workspaceStore = workspaceStore;
        this.manager = new PolicyRuleSetManager(workspaceStore);
        this.runState = runState;
        this.approvalGate = approvalGate;
    }

    /** @param applied true = 别名是 auto，已经写进 {@code fileName} 并启用；false = 排在审批 #approvalId 等人 */
    public record Proposal(long approvalId, boolean applied, String fileName) {
    }

    /**
     * @param manual 别名的图谱审批是不是 manual（调用方用 {@link ApprovalGate#isEnabled} 算，
     *               测试可以直接给）
     */
    public Proposal propose(String alias, PolicyRule rule, GraphActor actor, String reason, boolean manual)
            throws Exception {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("--reason is required：人在队列里裁决的就是这句话");
        }
        if (com.sqlcli.session.SessionContext.batchId() != null) {
            throw new IllegalArgumentException("规则提议不进批次，去掉 --batch 后重试");
        }
        String fileName = fileFor(rule.getCategory());
        // 先按规则集整体校验一遍：坏正则、未知类别在提交时就报，不留到批准那一刻
        RuleSet skeleton = skeleton(fileName);
        skeleton.getRules().add(rule);
        loader.validate(skeleton);

        PolicyRule existing = existingRule(alias, fileName, rule.getId());
        String targetId = targetId(alias, fileName, rule.getId());
        JsonNode before = existing == null ? null : GraphChangePayload.MAPPER.valueToTree(existing);
        JsonNode after = GraphChangePayload.MAPPER.valueToTree(rule);
        GraphChangePayload payload = new GraphChangePayload(targetId,
                existing == null ? "create" : "update", actor == null ? null : actor.name(),
                workspaceStore.load(alias).getManifest().getRevision(), before, after,
                GraphChangePayload.ACTION_POLICY);
        String summary = "规则 " + rule.getId() + "：" + rule.getTitle();
        if (manual) {
            if (runState.hasPendingApproval(alias, ApprovalGate.Kind.GRAPH.code(), targetId)) {
                // 同一条规则重复提交不该在队列里排两张卡
                throw new IllegalStateException("这条规则已在待审批队列里：" + targetId);
            }
            long id = approvalGate.request(alias, ApprovalGate.Kind.GRAPH, summary, reason,
                    null, targetId, payload.toJson());
            return new Proposal(id, false, fileName);
        }
        apply(alias, payload);
        long id = approvalGate.recordAutoApproved(alias, ApprovalGate.Kind.GRAPH, summary, reason,
                targetId, payload.toJson());
        return new Proposal(id, true, fileName);
    }

    /** 批准时落地：写进规则文件（同 id 覆盖、否则追加），版本 +1，并绑定到工作区。 */
    public void apply(String alias, GraphChangePayload payload) throws Exception {
        if (payload.after() == null) {
            throw new IllegalStateException("规则审批没有内容可写入");
        }
        String[] location = parseTargetId(alias, payload.targetId());
        String fileName = location[0];
        PolicyRule rule = GraphChangePayload.MAPPER.treeToValue(payload.after(), PolicyRule.class);
        PolicyRuleSetManager.RuleSetFile file = manager.list(alias).stream()
                .filter(item -> item.fileName().equals(fileName)).findFirst().orElse(null);
        if (file == null) {
            RuleSet ruleSet = skeleton(fileName);
            ruleSet.getRules().add(rule);
            manager.create(alias, fileName, ruleSet);
        } else {
            RuleSet ruleSet = file.ruleSet();
            ruleSet.getRules().removeIf(item -> item.getId().equals(rule.getId()));
            ruleSet.getRules().add(rule);
            ruleSet.setVersion(String.valueOf(parseVersion(ruleSet.getVersion()) + 1));
            manager.update(alias, fileName, ruleSet);
        }
        manager.setBound(alias, fileName, true);
    }

    // ---- required_business_columns 的 CLI 表达 ----

    /**
     * 一条 {@code --column} 的写法：
     * {@code name [type] [notnull|nullable] [default=X] [comment=X] [onupdate=X] [length=N]}。
     * 例：{@code "created_at datetime notnull comment=创建时间"}。
     *
     * <p>字段名只有一个，没有别名——规则要的是新表**统一**叫这个名字。
     * 不用 JSON：agent 在 Windows 的 bash 里给 JSON 转义引号，十次错三次。
     */
    public static Map<String, Object> parseColumnSpec(String spec) {
        if (spec == null || spec.isBlank()) throw new IllegalArgumentException("--column 不能为空");
        String[] tokens = spec.trim().split("\\s+");
        if (tokens[0].contains("|")) {
            throw new IllegalArgumentException("--column 的字段名只能有一个，没有别名：" + tokens[0]
                    + "。规则要求新表统一用这个名字");
        }
        Map<String, Object> column = new LinkedHashMap<>();
        column.put("name", tokens[0]);
        for (int i = 1; i < tokens.length; i++) {
            String token = tokens[i];
            int eq = token.indexOf('=');
            if (eq > 0) {
                String key = token.substring(0, eq).toLowerCase(Locale.ROOT);
                String value = token.substring(eq + 1);
                switch (key) {
                    case "default" -> column.put("defaultValue", value);
                    case "comment" -> column.put("comment", value);
                    case "onupdate" -> column.put("onUpdate", value);
                    case "length", "precision", "scale" -> column.put(key, Integer.parseInt(value));
                    default -> throw new IllegalArgumentException("--column 不认识的属性: " + key
                            + "（可用 default= comment= onupdate= length= precision= scale=）");
                }
            } else if (token.equalsIgnoreCase("notnull")) {
                column.put("nullable", false);
            } else if (token.equalsIgnoreCase("nullable")) {
                column.put("nullable", true);
            } else if (!column.containsKey("type")) {
                column.put("type", token.toLowerCase(Locale.ROOT));
            } else {
                throw new IllegalArgumentException("--column 里多余的词: " + token);
            }
        }
        return column;
    }

    // ---- helpers ----

    static String fileFor(String category) {
        return SQL_CATEGORIES.contains(category) ? SQL_FILE : STRUCTURE_FILE;
    }

    static String targetId(String alias, String fileName, String ruleId) {
        return "policy:" + alias + ":" + fileName + "/" + ruleId;
    }

    private static String[] parseTargetId(String alias, String targetId) {
        String prefix = "policy:" + alias + ":";
        if (targetId == null || !targetId.startsWith(prefix) || !targetId.contains("/")) {
            throw new IllegalStateException("不是规则审批的 targetId: " + targetId);
        }
        String rest = targetId.substring(prefix.length());
        int slash = rest.lastIndexOf('/');
        return new String[] {rest.substring(0, slash), rest.substring(slash + 1)};
    }

    /** 与规则页的固定分组同一套 id / 标题，人手建的和 agent 提的落在同一个文件里。 */
    private static RuleSet skeleton(String fileName) {
        RuleSet ruleSet = new RuleSet();
        ruleSet.setKind("PolicyRuleSet");
        ruleSet.setVersion("1");
        if (SQL_FILE.equals(fileName)) {
            ruleSet.setId("sql-migration-policy");
            ruleSet.setTitle("SQL 与迁移");
        } else {
            ruleSet.setId("structure-policy");
            ruleSet.setTitle("结构规范");
        }
        return ruleSet;
    }

    private PolicyRule existingRule(String alias, String fileName, String ruleId) throws Exception {
        for (PolicyRuleSetManager.RuleSetFile file : manager.list(alias)) {
            if (!file.fileName().equals(fileName)) continue;
            for (PolicyRule rule : file.ruleSet().getRules()) {
                if (rule.getId().equals(ruleId)) return rule;
            }
        }
        return null;
    }

    private static int parseVersion(String version) {
        try {
            return Integer.parseInt(version.trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
