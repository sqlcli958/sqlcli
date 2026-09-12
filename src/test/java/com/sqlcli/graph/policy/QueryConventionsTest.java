package com.sqlcli.graph.policy;

import com.sqlcli.graph.workspace.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全库查询约定规则。
 *
 * <p>最要紧的一条断言是 {@link #skipsColumnsWhoseValueDomainDoesNotBackTheRule()}：
 * 软删除的值在不同表上并不一致，一条无条件注入 {@code del_flag = 0} 的规则
 * 只要遇到一张用 1 表示存在的表，就会**静默筛掉全部数据**。所以值域不支持时宁可不给提示。
 */
class QueryConventionsTest {

    private static final String ALIAS = "demo";

    @TempDir Path temp;

    @Test
    void matchesByColumnNameAndValueDomain() throws Exception {
        GraphWorkspace workspace = seed("0=存在", "2=删除");
        QueryConventions conventions = load();

        List<QueryConventions.Hint> hints = conventions.forTable(workspace, table(workspace));
        assertEquals(1, hints.size());
        assertEquals("del_flag = 0", hints.get(0).filter());
        assertEquals("soft_delete_filter", hints.get(0).ruleId());
    }

    @Test
    void skipsColumnsWhoseValueDomainDoesNotBackTheRule() throws Exception {
        // 这张表用 1 表示存在，规则要求值域里有 0——不匹配，一条提示都不给。
        // 给了就是把「查得到的数据」整批筛掉，而且不报错。
        GraphWorkspace workspace = seed("1=存在", "2=删除");
        assertTrue(load().forTable(workspace, table(workspace)).isEmpty());
    }

    @Test
    void skipsColumnsWithNoValueDomainAtAll() throws Exception {
        GraphWorkspace workspace = seed();   // 值域没人标注过
        assertTrue(load().forTable(workspace, table(workspace)).isEmpty(),
                "值域未知就不该猜——猜错是静默筛掉全部数据");
    }

    @Test
    void anActiveWaiverSilencesTheRuleOnThatTable() throws Exception {
        GraphWorkspace workspace = seed("0=存在", "2=删除");
        RuleWaiver waiver = new RuleWaiver();
        waiver.setRuleId("soft_delete_filter");
        waiver.setTargetId(table(workspace).getId());
        waiver.setStatus(RuleWaiverStatus.active);
        waiver.setExpiresAt(LocalDateTime.now().plusDays(30));

        QueryConventions conventions = QueryConventions.load(temp.resolve(ALIAS), List.of(waiver));
        assertTrue(conventions.forTable(workspace, table(workspace)).isEmpty());
    }

    @Test
    void noRuleSetBoundIsNotAnError() {
        // describe / search 挂着这条路径，为了一份可选提示让查询失败是本末倒置
        assertTrue(QueryConventions.load(temp.resolve("no-such-workspace"), List.of()).isEmpty());
    }

    @Test
    void policyCheckDoesNotChokeOnConventionRules() throws Exception {
        // 约定规则不是断言，评估器的 switch 里必须显式跳过它——
        // 漏了这一行，任何绑了约定规则的工作区跑 policy check 会直接抛「unknown rule category」
        GraphWorkspace workspace = seed("0=存在", "2=删除");
        RuleSet ruleSet = new RuleSetLoader().load(
                temp.resolve(ALIAS).resolve("policy/rules/conventions.yaml"));
        List<PolicyViolation> violations = new PolicyEvaluator()
                .evaluate(workspace, ruleSet, List.of(), "eval-1");
        assertTrue(violations.isEmpty(), "约定规则不该产出违规");
    }

    // ── fixtures ──

    private QueryConventions load() {
        return QueryConventions.load(temp.resolve(ALIAS), List.of());
    }

    private static TableWorkspaceNode table(GraphWorkspace workspace) {
        return workspace.getTableByQualifiedName("app.report");
    }

    private GraphWorkspace seed(String... enumValues) throws Exception {
        Path policy = temp.resolve(ALIAS).resolve("policy");
        Files.createDirectories(policy.resolve("rules"));
        Files.writeString(policy.resolve("bindings.yaml"), "ruleSets:\n  - rules/conventions.yaml\n");
        Files.writeString(policy.resolve("rules").resolve("conventions.yaml"), """
                kind: PolicyRuleSet
                id: conventions
                title: 查询约定
                version: "1"
                rules:
                  - id: soft_delete_filter
                    title: 软删除过滤
                    category: query_convention
                    severity: info
                    enforcement: advisory
                    when:
                      columnNameRegex: "^(del_flag|is_deleted)$"
                      columnValueIn: ["0"]
                    statement:
                      queryFilter: "{column} = 0"
                """);

        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");
        TableWorkspaceNode table = TableWorkspaceNode.create(ALIAS, "app", "report", GraphActor.extractor);
        ColumnWorkspaceNode column = ColumnWorkspaceNode.create("del_flag");
        if (enumValues.length > 0) {
            ColumnValueHints hints = new ColumnValueHints();
            hints.setEnumValues(new java.util.ArrayList<>(List.of(enumValues)));
            column.setValueHints(hints);
        }
        table.getColumns().add(column);
        workspace.getTables().put(table.getId(), table);
        return workspace;
    }
}
