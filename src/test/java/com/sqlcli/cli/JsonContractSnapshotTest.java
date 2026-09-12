package com.sqlcli.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcli.graph.workspace.ColumnWorkspaceNode;
import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphIds;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import com.sqlcli.graph.workspace.TermWorkspaceNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 机器契约快照测试（批次 7 / P0-9）。
 *
 * <p>锁定 Agent 依赖的 `--json` 输出契约：信封结构、载荷字段名、错误码、退出码。
 * 断言写成显式字段检查而非整串比对——值可变的字段（时间戳、id、score）只断言存在。
 * 后续重构（SqlTaskModule 等）如果改动这些字段，本测试必须一起改，改动即被看见。
 */
class JsonContractSnapshotTest {

    private static final String ALIAS = "json-contract";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir Path temp;

    private GraphWorkspaceStore store;

    /** 一次命令执行的观测结果：退出码 + 两条流。 */
    private record Run(int exitCode, String stdout, String stderr) {
        JsonNode json() throws Exception {
            return MAPPER.readTree(stdout);
        }
    }

    private String previousHome;

    @BeforeEach
    void setUp() {
        // describe 读执行历史、eval 写评估记录，两者都落运行库；不改 sqlcli.home
        // 这些用例会直接读写开发机真实的 ~/.sql-cli。
        previousHome = System.getProperty("sqlcli.home");
        System.setProperty("sqlcli.home", temp.resolve("run-state-home").toString());
        store = new GraphWorkspaceStore(temp);
    }

    @AfterEach
    void restoreHome() {
        if (previousHome == null) System.clearProperty("sqlcli.home");
        else System.setProperty("sqlcli.home", previousHome);
    }

    // ---------------------------------------------------------------- 只读命令

    @Test
    void schemaListJsonReturnsTableArrayWithStableNodeFields() throws Exception {
        seedWorkspace();
        Run run = run("list", command -> { });

        assertEquals(0, run.exitCode());
        JsonNode envelope = run.json();
        assertTrue(envelope.get("ok").asBoolean());
        assertTrue(envelope.get("data").isArray());
        assertEquals(2, envelope.get("data").size());

        JsonNode table = firstWhere(envelope.get("data"), "name", "orders");
        for (String field : List.of("id", "kind", "version", "status", "sourceAlias",
                "schema", "name", "qualifiedName", "tableType", "columns",
                "createdAt", "updatedAt", "createdBy", "updatedBy")) {
            assertTrue(table.has(field), "list[].{" + field + "} 是机器契约字段");
        }
        assertEquals("table", table.get("kind").asText());
        assertEquals(ALIAS, table.get("sourceAlias").asText());
        assertEquals("app.orders", table.get("qualifiedName").asText());
        assertTrue(table.get("columns").isArray());
        assertEquals("", run.stderr(), "--json 下 stdout 纯 JSON，stderr 不得有内容");
    }

    @Test
    void schemaStatsJsonReturnsCountersIncludingCandidates() throws Exception {
        seedWorkspace();
        Run run = run("stats", command -> { });

        assertEquals(0, run.exitCode());
        JsonNode data = run.json().get("data");
        assertTrue(run.json().get("ok").asBoolean());
        // terms / candidateRelations / candidateTerms 是 2026-08 批次新增：候选在 CLI 侧要能计数
        // ignoredRelations 是拒绝语义从物理删除改成 ignored 状态后新增：并列展示，不从 relations 里减掉
        for (String field : List.of("schemas", "tables", "columns", "relations", "validationIssues",
                "terms", "candidateRelations", "candidateTerms", "ignoredRelations")) {
            assertTrue(data.has(field), "stats.{" + field + "} 是机器契约字段");
            assertTrue(data.get(field).isNumber(), "stats." + field + " 必须是数字");
        }
        assertEquals(2, data.get("tables").asInt());
        assertEquals("", run.stderr());
    }

    @Test
    void schemaSearchJsonReturnsScoredHitsWithFixedShape() throws Exception {
        seedWorkspace();
        Run run = run("search", command -> command.setKeyword("orders"));

        assertEquals(0, run.exitCode());
        JsonNode data = run.json().get("data");
        assertTrue(run.json().get("ok").asBoolean());
        assertTrue(data.isArray());
        assertTrue(data.size() > 0, "orders 至少命中一条");

        JsonNode hit = data.get(0);
        // matchedField / matchedText / comment / businessName / semanticType / candidate 是 2026-08 批次新增：
        // Agent 拿到候选后不必逐个再 describe，且能看出命中在哪个字段、是不是候选对象。
        for (String field : List.of("id", "type", "schema", "tableName", "columnName", "matchValue", "score",
                "matchedField", "matchedText", "comment", "businessName", "semanticType", "candidate")) {
            assertTrue(hit.has(field), "search[].{" + field + "} 是机器契约字段（无值时为 null，不得省略）");
        }
        assertEquals(hit.get("matchValue"), hit.get("matchedText"), "matchedText 与旧字段 matchValue 同值");
        assertTrue(List.of("table", "column").contains(hit.get("type").asText()),
                "search[].type 只有 table / column 两种取值");
        assertTrue(hit.get("score").isNumber());
        assertEquals("", run.stderr());
    }

    @Test
    void schemaDescribeJsonReturnsTableColumnsRelationsTriple() throws Exception {
        seedWorkspace();
        Run run = run("describe", command -> command.setTableName("app.orders"));

        assertEquals(0, run.exitCode());
        JsonNode data = run.json().get("data");
        assertTrue(run.json().get("ok").asBoolean());
        // sm4Columns / recentSql 是 2026-08 批次新增：Agent 从 describe 一步拿到
        // 加密列名单（LIKE/范围不可用）和这张表最近的成功查询范例。
        // conventions / scenarios 是场景模型新增：前者是全库约定过滤（漏了就是静默多查已删数据），
        // 后者是「这张表属于哪些业务场景」的反向索引。两者都必须在 --json 里，
        // 只出现在文本输出等于 Agent 拿不到。
        // completeness 是 D1 新增：agent 开工前判断「这张表能不能直接用」。图谱按需生长，
        // 没有描述不等于这张表简单——curated=false 才说明没人干过这块，该先读代码。
        // 同样必须在 --json 里，只出现在文本输出等于 Agent 拿不到。
        assertEquals(8, data.size(), "describe 载荷恰好八个键");
        assertTrue(data.has("table"));
        assertTrue(data.get("columns").isArray());
        assertTrue(data.get("relations").isArray());
        assertTrue(data.get("sm4Columns").isArray());
        assertTrue(data.get("recentSql").isArray());
        assertTrue(data.get("conventions").isArray());
        assertTrue(data.get("scenarios").isArray());
        JsonNode completeness = data.get("completeness");
        for (String field : List.of("columns", "withDescription", "withEnumValues",
                "withBusinessName", "relations", "unknownCardinality", "curated",
                "updatedAt", "searchHits", "summary")) {
            assertTrue(completeness.has(field),
                    "completeness.{" + field + "} 是机器契约字段（无值时为 null，不得省略）");
        }
        assertTrue(completeness.get("curated").isBoolean(),
                "curated 是这条提示的全部用处：区分「表确实简单」和「没人干过这块」");

        assertEquals("app.orders", data.get("table").get("qualifiedName").asText());
        JsonNode column = data.get("columns").get(0);
        for (String field : List.of("name", "dataType", "nullable", "primaryKey")) {
            assertTrue(column.has(field), "describe.columns[].{" + field + "} 是机器契约字段");
        }
        // 内联列没有自己的 id 字段，Agent 需按 GraphIds.columnId 规则自行拼装
        assertFalse(column.has("id"), "列节点不带 id —— 改动这一点会打断 add-relation 的端点引用约定");
        assertEquals("", run.stderr());
    }

    @Test
    void schemaValidateJsonReturnsIssueArrayAndZeroWhenClean() throws Exception {
        seedWorkspace();
        Run run = run("validate", command -> { });

        assertEquals(0, run.exitCode(), "无 error 级问题时退出码 0");
        assertTrue(run.json().get("ok").asBoolean());
        assertTrue(run.json().get("data").isArray(), "validate 载荷是问题数组，空数组即通过");
        assertEquals("", run.stderr());
    }

    @Test
    void schemaValidateJsonReturnsOneWithErrorSeverityIssues() throws Exception {
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");
        TableWorkspaceNode broken = TableWorkspaceNode.create(ALIAS, "app", "orders", GraphActor.extractor);
        broken.setConfidence(2.0);
        workspace.getTables().put(broken.getId(), broken);
        store.save(workspace);

        Run run = run("validate", command -> { });

        assertEquals(1, run.exitCode(), "存在 error 级问题时退出码 1，但信封仍是 ok=true 的成功结果");
        JsonNode data = run.json().get("data");
        assertTrue(run.json().get("ok").asBoolean());
        assertTrue(data.isArray() && data.size() > 0);
        JsonNode issue = data.get(0);
        for (String field : List.of("id", "kind", "sourceAlias", "severity", "code", "message", "status")) {
            assertTrue(issue.has(field), "validate[].{" + field + "} 是机器契约字段");
        }
    }

    @Test
    void schemaEvalJsonReturnsMetricsAndFindingsWithRemediation() throws Exception {
        seedWorkspace();
        TermWorkspaceNode orphan = TermWorkspaceNode.create(ALIAS, "工单", GraphActor.agent);
        GraphWorkspace workspace = store.load(ALIAS);
        workspace.getTerms().put(orphan.getId(), orphan);
        store.save(workspace);

        Run run = run("eval", command -> { });

        assertEquals(1, run.exitCode(), "硬错误 > 0 时退出码 1，信封仍是 ok=true");
        JsonNode data = run.json().get("data");
        assertTrue(run.json().get("ok").asBoolean());
        // evaluationId 让 agent 能顺着 GET /api/eval/evaluations/{id}/findings 翻回这次结果；
        // metrics 只报告不判定；findings[].remediation 是「报告即工作队列」的全部意义所在。
        for (String field : List.of("evaluationId", "alias", "revision", "status",
                "errorCount", "warningCount", "metrics", "findings")) {
            assertTrue(data.has(field), "eval.{" + field + "} 是机器契约字段");
        }
        assertEquals("fail", data.get("status").asText());
        assertEquals(1, data.get("errorCount").asInt());
        // columnComment 与 columnDescription 分开报：库注释是导入的镜像，
        // 合进描述覆盖率就成了虚荣指标（真图谱上 85.9% vs 0.4%）
        for (String metric : List.of("tableDescription", "columnDescription", "columnComment",
                "columnValueDomain", "termMapping")) {
            assertTrue(data.get("metrics").get(metric).isNumber(), "metrics." + metric + " 必须是数字");
        }
        JsonNode finding = data.get("findings").get(0);
        for (String field : List.of("probe", "severity", "targetId", "message", "remediation")) {
            assertTrue(finding.has(field), "eval.findings[].{" + field + "} 是机器契约字段");
        }
        assertEquals("term.orphan", finding.get("probe").asText());
        assertTrue(finding.get("remediation").asText().startsWith("sql-cli "),
                "产不出可执行命令的探针不上线");
        assertEquals("", run.stderr(), "--json 下 stdout 纯 JSON，stderr 不得有内容");
    }

    @Test
    void policyCheckJsonWithoutBindingsReturnsEmptyResultsAndZero() throws Exception {
        seedWorkspace();
        Path policyRoot = temp.resolve(ALIAS).resolve("policy");
        Files.createDirectories(policyRoot);
        Files.writeString(policyRoot.resolve("bindings.yaml"), "ruleSets: []\n");

        Run run = run("policy", command -> command.setCommandArgs(
                List.of("policy", "check", "--json")));

        assertEquals(0, run.exitCode(), "无绑定规则集视为检查通过");
        JsonNode data = run.json().get("data");
        assertTrue(run.json().get("ok").asBoolean());
        assertTrue(data.get("results").isArray());
        assertEquals(0, data.get("results").size());
        assertEquals(0, data.get("bound").asInt(), "bound 是绑定规则集数量");
        assertEquals("", run.stderr());
    }

    @Test
    void policyCheckJsonWithRulesReturnsEvaluationAndViolations() throws Exception {
        seedWorkspace();
        Path rules = temp.resolve("rules.yaml");
        Files.writeString(rules, """
                kind: PolicyRuleSet
                id: contract
                title: Contract rules
                version: "1"
                rules:
                  - id: pk
                    title: PK
                    category: primary_key_required
                    severity: error
                    enforcement: required
                """);

        Run run = run("policy", command -> command.setCommandArgs(
                List.of("policy", "check", "--rules", rules.toString(), "--json")));

        assertEquals(1, run.exitCode(), "error 级违规退出码 1");
        JsonNode data = run.json().get("data");
        assertTrue(run.json().get("ok").asBoolean());
        assertEquals(2, data.size(), "policy check 载荷恰好 evaluation + violations");

        JsonNode evaluation = data.get("evaluation");
        for (String field : List.of("id", "sourceAlias", "workspaceRevision", "reviewType", "ruleSetId",
                "ruleSetVersion", "status", "startedAt", "finishedAt", "actor",
                "evaluatedRules", "violationCount", "waivedCount")) {
            assertTrue(evaluation.has(field), "policy.evaluation.{" + field + "} 是机器契约字段");
        }
        assertEquals(ALIAS, evaluation.get("sourceAlias").asText());
        assertEquals("contract", evaluation.get("ruleSetId").asText());

        assertTrue(data.get("violations").isArray());
        assertTrue(data.get("violations").size() > 0, "两张无主键的表必然违规");
        JsonNode violation = data.get("violations").get(0);
        for (String field : List.of("id", "ruleId", "severity", "enforcement", "targetId", "message", "status")) {
            assertTrue(violation.has(field), "policy.violations[].{" + field + "} 是机器契约字段");
        }
        assertEquals("", run.stderr());
    }

    // ------------------------------------------------------------- 写入命令契约

    @Test
    void addRelationWithExplicitConfidenceSucceedsAndPersists() throws Exception {
        seedWorkspace();

        Run run = run("add-relation", command -> {
            command.setRelationType("join_observed");
            command.setFromRef("app.orders.user_id");
            command.setToRef("app.users.id");
            command.setConfidence(0.9);
        });

        assertEquals(0, run.exitCode());
        assertTrue(run.stdout().contains("relation:"), "成功时 stdout 回显关系 id：" + run.stdout());
        assertEquals("", run.stderr());

        GraphWorkspace saved = store.load(ALIAS);
        assertEquals(1, saved.getRelations().size());
        assertEquals(0.9, saved.getRelations().get(0).getConfidence(), 1e-9,
                "显式 confidence 必须原样落库，不得被兜底值覆盖");
    }

    @Test
    void addRelationWithoutExplicitConfidenceIsRejectedForInferredTypes() throws Exception {
        seedWorkspace();

        Run run = run("add-relation", command -> {
            command.setRelationType("join_observed");
            command.setFromRef("app.orders.user_id");
            command.setToRef("app.users.id");
        });

        assertEquals(1, run.exitCode(), "推断类关系缺少显式 confidence 必须拒绝，不得兜底 0.7");
        assertFalse(run.stderr().isBlank(), "拒绝原因走 stderr");
        assertTrue(store.load(ALIAS).getRelations().isEmpty(), "拒绝路径不得写入工作区");
    }

    @Test
    void addRelationRejectsConfidenceBelowTypeMinimum() throws Exception {
        seedWorkspace();

        Run run = run("add-relation", command -> {
            command.setRelationType("join_observed");
            command.setFromRef("app.orders.user_id");
            command.setToRef("app.users.id");
            command.setConfidence(0.2);
        });

        assertEquals(1, run.exitCode());
        assertTrue(run.stderr().contains("confidence"), "校验失败原因需可读：" + run.stderr());
        assertTrue(store.load(ALIAS).getRelations().isEmpty());
    }

    @Test
    void addRelationRejectsVerifiedBelowNinePointNine() throws Exception {
        seedWorkspace();

        Run run = run("add-relation", command -> {
            command.setRelationType("join_observed");
            command.setFromRef("app.orders.user_id");
            command.setToRef("app.users.id");
            command.setConfidence(0.6);
            command.setVerifiedFlag(true);
        });

        assertEquals(1, run.exitCode(), "verified 关系的 confidence 必须 >= 0.9");
        assertFalse(run.stderr().isBlank());
        assertTrue(store.load(ALIAS).getRelations().isEmpty());
    }

    @Test
    void addRelationRejectsEndpointKindMismatch() throws Exception {
        seedWorkspace();

        Run run = run("add-relation", command -> {
            command.setRelationType("join_observed");
            command.setFromRef("app.orders");
            command.setToRef("app.users");
            command.setConfidence(0.9);
        });

        assertEquals(1, run.exitCode(), "join_observed 两端必须是列");
        assertFalse(run.stderr().isBlank());
        assertTrue(store.load(ALIAS).getRelations().isEmpty());
    }

    @Test
    void addRelationRejectsDeclaredForeignKeyAsManualCreation() throws Exception {
        seedWorkspace();

        Run run = run("add-relation", command -> {
            command.setRelationType("foreign_key");
            command.setFromRef("app.orders.user_id");
            command.setToRef("app.users.id");
            command.setConfidence(1.0);
        });

        assertEquals(1, run.exitCode(), "declared FK 只能由导入产生");
        assertTrue(store.load(ALIAS).getRelations().isEmpty());
    }

    // ------------------------------------------------------------- 失败信封契约

    @Test
    void missingRequiredArgumentReturnsUsageErrorEnvelopeAndExitTwo() throws Exception {
        seedWorkspace();

        for (String action : List.of("describe", "query", "path", "search", "edit", "add-term", "add-relation")) {
            Run run = run(action, command -> { });

            assertEquals(2, run.exitCode(), action + " 缺参数退出码必须是 2");
            JsonNode envelope = run.json();
            assertFalse(envelope.get("ok").asBoolean(), action);
            assertEquals("USAGE_ERROR", envelope.get("code").asText(), action);
            assertTrue(envelope.has("message"), action + " 失败信封必须带 message");
            assertFalse(envelope.get("message").asText().isBlank(), action);
            assertEquals("", run.stderr(), action + " --json 下不得写 stderr");
        }
    }

    @Test
    void missingWorkspaceReturnsWorkspaceNotFoundEnvelopeAndExitOne() throws Exception {
        // 不 seed：工作区不存在
        for (String action : List.of("list", "stats", "validate")) {
            Run run = run(action, command -> { });

            assertEquals(1, run.exitCode(), action + " 工作区缺失退出码必须是 1");
            JsonNode envelope = run.json();
            assertFalse(envelope.get("ok").asBoolean(), action);
            assertEquals("WORKSPACE_NOT_FOUND", envelope.get("code").asText(), action);
            assertFalse(envelope.get("message").asText().isBlank(), action);
            assertFalse(envelope.has("data"), action + " 失败信封不带 data");
            assertEquals("", run.stderr(), action);
        }
    }

    @Test
    void runtimeFailureReturnsCommandFailedEnvelopeAndExitOne() throws Exception {
        seedWorkspace();
        Run run = run("describe", command -> command.setTableName("app.not_there"));

        assertEquals(1, run.exitCode());
        JsonNode envelope = run.json();
        assertFalse(envelope.get("ok").asBoolean());
        assertEquals("COMMAND_FAILED", envelope.get("code").asText());
        assertFalse(envelope.get("message").asText().isBlank());
        assertEquals("", run.stderr());
    }

    @Test
    void successEnvelopeKeysAreExactlyOkAndData() throws Exception {
        seedWorkspace();
        JsonNode envelope = run("stats", command -> { }).json();

        assertEquals(2, envelope.size(), "成功信封只有 ok / data 两个键");
        assertEquals("ok", envelope.fieldNames().next(), "ok 必须是第一个键");
        assertTrue(envelope.has("data"));
    }

    // -------------------------------------------------------------------- 辅助

    private void seedWorkspace() throws Exception {
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");

        TableWorkspaceNode orders = TableWorkspaceNode.create(ALIAS, "app", "orders", GraphActor.extractor);
        orders.getColumns().add(ColumnWorkspaceNode.create("id"));
        orders.getColumns().add(ColumnWorkspaceNode.create("user_id"));
        workspace.getTables().put(orders.getId(), orders);

        TableWorkspaceNode users = TableWorkspaceNode.create(ALIAS, "app", "users", GraphActor.extractor);
        users.getColumns().add(ColumnWorkspaceNode.create("id"));
        workspace.getTables().put(users.getId(), users);

        store.save(workspace);
        // 端点 id 的拼装规则本身也是契约的一部分
        assertEquals("column:" + ALIAS + ":app.orders.user_id",
                GraphIds.columnId(ALIAS, "app", "orders", "user_id"));
    }

    private Run run(String action, Consumer<SchemaActionCommand> configure) {
        SchemaActionCommand command = new SchemaActionCommand(store);
        command.setAlias(ALIAS);
        command.setAction(action);
        command.setCommandArgs(List.of(action, "--json"));
        command.setJsonOutput(true);
        configure.accept(command);

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode;
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            System.setErr(err);
            exitCode = command.executeCommand();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new Run(exitCode,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private JsonNode firstWhere(JsonNode array, String field, String value) {
        for (JsonNode node : array) {
            if (node.hasNonNull(field) && value.equals(node.get(field).asText())) {
                return node;
            }
        }
        throw new AssertionError("no element with " + field + "=" + value + " in " + array);
    }
}
