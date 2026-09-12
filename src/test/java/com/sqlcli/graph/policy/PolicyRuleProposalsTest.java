package com.sqlcli.graph.policy;

import com.sqlcli.approval.ApprovalGate;
import com.sqlcli.graph.ui.service.GraphChangePayload;
import com.sqlcli.graph.ui.service.WorkspaceLockManager;
import com.sqlcli.graph.ui.service.WorkspaceMutationService;
import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sqlcli.graph.workspace.WorkspaceValidator;
import com.sqlcli.runstate.ApprovalRow;
import com.sqlcli.runstate.RunStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * agent 提议的规则：manual 别名上先排队、批准才落文件；auto 别名上当场写入并留底。
 * 两条路最后都必须**写进文件并绑定**——只写文件不绑定，policy check 和 DDL 门都看不见它。
 */
class PolicyRuleProposalsTest {

    private static final String ALIAS = "demo";

    @TempDir Path temp;
    private String previousHome;
    private GraphWorkspaceStore store;
    private RunStateStore runState;
    private PolicyRuleProposals proposals;

    @BeforeEach
    void setUp() throws Exception {
        previousHome = System.getProperty("sqlcli.home");
        System.setProperty("sqlcli.home", temp.resolve("home").toString());
        store = new GraphWorkspaceStore(temp.resolve("graphs"));
        store.save(GraphWorkspace.create(ALIAS, "mysql"));
        runState = new RunStateStore(temp.resolve("sqlcli.db"), temp.resolve("history"));
        proposals = new PolicyRuleProposals(store, runState, new ApprovalGate(runState, 200));
    }

    @AfterEach
    void tearDown() {
        if (previousHome == null) System.clearProperty("sqlcli.home");
        else System.setProperty("sqlcli.home", previousHome);
    }

    @Test
    void manualAliasQueuesTheRuleAndWritesItOnlyWhenApproved() throws Exception {
        PolicyRuleProposals.Proposal proposal = proposals.propose(ALIAS, rule(), GraphActor.agent,
                "抽查 12 张表都有 create_time", true);

        assertFalse(proposal.applied());
        assertFalse(Files.exists(rulesDir().resolve("structure.yaml")), "批准之前文件一个字节不动");
        ApprovalRow row = runState.findApproval(proposal.approvalId());
        assertEquals("pending", row.status());
        GraphChangePayload payload = GraphChangePayload.fromJson(row.payload());
        assertTrue(payload.isPolicy());
        assertTrue(payload.before() == null || payload.before().isNull(), "新规则没有 before");
        assertEquals("required_business_columns", payload.after().get("id").asText());

        // 重复提交同一条：不排第二张卡
        assertThrows(IllegalStateException.class,
                () -> proposals.propose(ALIAS, rule(), GraphActor.agent, "again", true));

        WorkspaceMutationService service = new WorkspaceMutationService(
                store, new WorkspaceValidator(), new WorkspaceLockManager());
        assertTrue(service.decideGraphApproval(ALIAS, proposal.approvalId(), payload, true, "ok").isSuccess());

        assertBoundWithRule();
    }

    @Test
    void rejectingLeavesNoTrace() throws Exception {
        PolicyRuleProposals.Proposal proposal = proposals.propose(ALIAS, rule(), GraphActor.agent, "why", true);
        GraphChangePayload payload = GraphChangePayload.fromJson(
                runState.findApproval(proposal.approvalId()).payload());
        WorkspaceMutationService service = new WorkspaceMutationService(
                store, new WorkspaceValidator(), new WorkspaceLockManager());
        assertTrue(service.decideGraphApproval(ALIAS, proposal.approvalId(), payload, false, "no").isSuccess());
        assertFalse(Files.exists(rulesDir().resolve("structure.yaml")));
    }

    @Test
    void autoAliasWritesImmediatelyAndKeepsAnApprovedRecord() throws Exception {
        PolicyRuleProposals.Proposal proposal = proposals.propose(ALIAS, rule(), GraphActor.agent, "why", false);

        assertTrue(proposal.applied());
        assertEquals("approved", runState.findApproval(proposal.approvalId()).status());
        assertBoundWithRule();

        // 再提一次同 id：覆盖而不是追加，版本 +1
        PolicyRule changed = rule();
        changed.setTitle("必备字段 v2");
        proposals.propose(ALIAS, changed, GraphActor.agent, "tighten", false);
        RuleSet ruleSet = new RuleSetLoader().load(rulesDir().resolve("structure.yaml"));
        assertEquals(1, ruleSet.getRules().size());
        assertEquals("必备字段 v2", ruleSet.getRules().get(0).getTitle());
        assertEquals("2", ruleSet.getVersion());
    }

    @Test
    void invalidRuleIsRejectedAtProposalTime() {
        PolicyRule bad = rule();
        bad.getWhen().put("tableNameRegex", "([unclosed");
        assertThrows(IllegalArgumentException.class,
                () -> proposals.propose(ALIAS, bad, GraphActor.agent, "why", true));
        assertFalse(runState.hasPendingApproval(ALIAS, "graph",
                PolicyRuleProposals.targetId(ALIAS, "structure.yaml", "required_business_columns")));
    }

    @Test
    void columnSpecGrammar() {
        Map<String, Object> column = PolicyRuleProposals.parseColumnSpec(
                "created_at datetime notnull default=CURRENT_TIMESTAMP comment=创建时间");
        assertEquals("created_at", column.get("name"));
        assertFalse(column.containsKey("aliases"), "必备字段没有别名这回事");
        assertEquals("datetime", column.get("type"));
        assertEquals(false, column.get("nullable"));
        assertEquals("CURRENT_TIMESTAMP", column.get("defaultValue"));
        assertEquals("创建时间", column.get("comment"));

        Map<String, Object> minimal = PolicyRuleProposals.parseColumnSpec("tenant_id");
        assertEquals(Map.of("name", "tenant_id"), minimal);

        assertThrows(IllegalArgumentException.class,
                () -> PolicyRuleProposals.parseColumnSpec("x datetime bigint"));
        assertThrows(IllegalArgumentException.class,
                () -> PolicyRuleProposals.parseColumnSpec("x foo=bar"));
        assertThrows(IllegalArgumentException.class,
                () -> PolicyRuleProposals.parseColumnSpec("created_at|create_time datetime"),
                "别名写法必须被拒，不能静默取第一个");
    }

    private void assertBoundWithRule() throws Exception {
        RuleSet ruleSet = new RuleSetLoader().load(rulesDir().resolve("structure.yaml"));
        assertEquals("structure-policy", ruleSet.getId());
        assertEquals("required_business_columns", ruleSet.getRules().get(0).getCategory());
        assertTrue(Files.readString(temp.resolve("graphs/demo/policy/bindings.yaml"))
                .contains("rules/structure.yaml"), "写了文件还得绑定，不然 DDL 门看不见它");
    }

    private Path rulesDir() {
        return temp.resolve("graphs/demo/policy/rules");
    }

    private static PolicyRule rule() {
        PolicyRule rule = new PolicyRule();
        rule.setId("required_business_columns");
        rule.setTitle("必备字段");
        rule.setCategory("required_business_columns");
        rule.setSeverity(PolicySeverity.warning);
        rule.setEnforcement(PolicyEnforcement.required);
        rule.getWhen().put("tableTypeAny", List.of("base_table"));
        rule.getStatement().put("columns", List.of(
                PolicyRuleProposals.parseColumnSpec("created_at datetime notnull")));
        return rule;
    }
}
