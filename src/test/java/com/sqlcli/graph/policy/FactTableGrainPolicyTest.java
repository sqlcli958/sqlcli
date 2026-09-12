package com.sqlcli.graph.policy;

import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F3：fact_table_grain_required —— 打了 fact 标签的表必须声明 grain，否则报 violation。
 * 覆盖两个分支：grain 为空报错；grain 已填不报错。
 */
class FactTableGrainPolicyTest {

    @Test
    void factTableWithoutGrainReportsViolation() throws Exception {
        List<PolicyViolation> violations = evaluate(table -> { /* grain 留空 */ });

        assertEquals(1, violations.size());
        PolicyViolation violation = violations.get(0);
        assertEquals("fact_table_grain_required", violation.getRuleId());
        assertEquals("grain", violation.getField());
    }

    @Test
    void factTableWithGrainDeclaredPasses() throws Exception {
        List<PolicyViolation> violations = evaluate(
                table -> table.setGrain("一行一个订单商品"));

        assertTrue(violations.isEmpty(), () -> violations.toString());
    }

    private List<PolicyViolation> evaluate(java.util.function.Consumer<TableWorkspaceNode> customize)
            throws Exception {
        GraphWorkspace workspace = GraphWorkspace.create("grain-policy", "mysql");
        TableWorkspaceNode table = TableWorkspaceNode.create(
                "grain-policy", "app", "order_item", GraphActor.extractor);
        table.getTags().add("fact");
        customize.accept(table);
        workspace.getTables().put(table.getId(), table);

        Path rulesPath = Files.createTempFile("fact-grain", ".yaml");
        Files.writeString(rulesPath, """
                kind: PolicyRuleSet
                id: grain-policy
                title: Fact table grain
                version: "1"
                rules:
                  - id: fact_table_grain_required
                    title: Fact table grain required
                    category: fact_table_grain_required
                    severity: warning
                    enforcement: advisory
                    when:
                      tableTagsAny: [fact]
                """);
        RuleSet rules = new RuleSetLoader().load(rulesPath);

        return new PolicyEvaluator().evaluate(workspace, rules, List.of(), "evaluation-grain");
    }
}
