package com.sqlcli.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcli.graph.workspace.*;
import com.sqlcli.graph.policy.PolicyEnforcement;
import com.sqlcli.graph.policy.PolicyRule;
import com.sqlcli.graph.policy.PolicyService;
import com.sqlcli.graph.policy.RuleSet;
import com.sqlcli.graph.policy.RuleSetLoader;
import com.sqlcli.graph.policy.PolicyViolation;
import com.sqlcli.graph.policy.RuleEvaluation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaPolicyCommandTest {
    @TempDir Path temp;

    private String previousHome;

    /**
     * 规则评估现在落运行库（{@code sqlcli.db}），而运行库的位置由 {@code sqlcli.home} 决定。
     * 不隔离的话这些用例会往开发机真实的 {@code ~/.sql-cli} 里写评估记录。
     */
    @BeforeEach
    void isolateRunStateHome() {
        previousHome = System.getProperty("sqlcli.home");
        System.setProperty("sqlcli.home", temp.resolve("run-state-home").toString());
    }

    @AfterEach
    void restoreRunStateHome() {
        if (previousHome == null) {
            System.clearProperty("sqlcli.home");
        } else {
            System.setProperty("sqlcli.home", previousHome);
        }
    }


    @Test
    void policyCheckUsesRulesBoundToTheAliasWorkspace() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = GraphWorkspace.create("commands", "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create(
                "commands", "app", "orders", GraphActor.extractor);
        workspace.getTables().put(orders.getId(), orders);
        store.save(workspace);

        Path policyRoot = temp.resolve("commands/policy");
        Files.createDirectories(policyRoot.resolve("rules"));
        Files.writeString(policyRoot.resolve("rules/app.yaml"), """
                kind: PolicyRuleSet
                id: alias-policy
                title: Alias policy
                version: "1"
                rules:
                  - id: pk
                    title: PK
                    category: primary_key_required
                    severity: error
                    enforcement: required
                """);
        Files.writeString(policyRoot.resolve("rules/naming.yaml"), """
                kind: PolicyRuleSet
                id: alias-naming
                title: Alias naming
                version: "1"
                rules:
                  - id: naming
                    title: Naming
                    category: naming_convention
                    severity: warning
                    enforcement: advisory
                    statement:
                      tableNameRegex: "^[a-z_]+$"
                """);
        Files.writeString(policyRoot.resolve("bindings.yaml"), """
                ruleSets:
                  - rules/app.yaml
                  - rules/naming.yaml
                """);

        SchemaActionCommand policy = command(store, "policy",
                List.of("policy", "check", "--json"));
        policy.setJsonOutput(true);

        assertEquals(1, policy.executeCommand());
        List<RuleEvaluation> evaluations = new PolicyService(store).listEvaluations("commands");
        assertEquals(2, evaluations.size());
        assertTrue(evaluations.stream().anyMatch(item -> "alias-policy".equals(item.getRuleSetId())));
        assertTrue(evaluations.stream().anyMatch(item -> "alias-naming".equals(item.getRuleSetId())));
    }

    @Test
    void policyCommandsExecuteThroughCliHandler() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = GraphWorkspace.create("commands", "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create("commands", "app", "orders", GraphActor.extractor);
        ColumnWorkspaceNode userId = ColumnWorkspaceNode.create("user_id");
        orders.getColumns().add(userId);
        TableWorkspaceNode users = TableWorkspaceNode.create("commands", "app", "users", GraphActor.extractor);
        users.getColumns().add(ColumnWorkspaceNode.create("id"));
        workspace.getTables().put(orders.getId(), orders);
        workspace.getTables().put(users.getId(), users);
        store.save(workspace);

        Path rules = temp.resolve("rules.yaml");
        Files.writeString(rules, """
                kind: PolicyRuleSet
                id: cli
                title: CLI rules
                version: "1"
                rules:
                  - id: pk
                    title: PK
                    category: primary_key_required
                    severity: error
                    enforcement: required
                """);
        SchemaActionCommand policy = command(store, "policy",
                List.of("policy", "check", "--rules", rules.toString(), "--json"));
        policy.setJsonOutput(true);
        assertEquals(1, policy.executeCommand());
        PolicyService policyService = new PolicyService(store);
        String evaluationId = policyService.listEvaluations("commands").get(0).getId();
        assertEquals(0, command(store, "policy", List.of(
                "policy", "evaluation", "list", "--json")).executeCommand());
        assertEquals(0, command(store, "policy", List.of(
                "policy", "evaluation", "show", evaluationId, "--json")).executeCommand());
        assertEquals(0, command(store, "policy", List.of(
                "policy", "violation", "list", "--evaluation", evaluationId, "--json")).executeCommand());
        assertEquals(0, command(store, "policy", List.of(
                "policy", "waiver", "add", "--rule", "pk", "--target", orders.getId(),
                "--reason", "test", "--expires-at", "2099-01-01T00:00:00")).executeCommand());
        String waiverId = policyService.listWaivers("commands", false).get(0).getId();
        assertEquals(0, command(store, "policy", List.of(
                "policy", "waiver", "list", "--active", "--json")).executeCommand());
        assertEquals(0, command(store, "policy", List.of(
                "policy", "waiver", "revoke", waiverId, "--reason", "done")).executeCommand());
    }

    @Test
    void schemaValidateReturnsOneWhenValidationFindsErrors() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = GraphWorkspace.create("invalid-validation", "mysql");
        TableWorkspaceNode table = TableWorkspaceNode.create(
                "invalid-validation", "app", "orders", GraphActor.extractor);
        table.setConfidence(2.0);
        workspace.getTables().put(table.getId(), table);
        store.save(workspace);

        SchemaActionCommand validate = new SchemaActionCommand(store);
        validate.setAlias("invalid-validation");
        validate.setAction("validate");

        assertEquals(1, validate.executeCommand());
    }

    @Test
    void schemaListJsonUsesStableSuccessEnvelope() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = GraphWorkspace.create("commands", "mysql");
        workspace.getTables().put(
                "table:commands:app.orders",
                TableWorkspaceNode.create("commands", "app", "orders", GraphActor.extractor));
        store.save(workspace);
        SchemaActionCommand list = command(store, "list", List.of("list", "--json"));
        list.setJsonOutput(true);

        PrintStream originalOut = System.out;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            assertEquals(0, list.executeCommand());
        } finally {
            System.setOut(originalOut);
        }

        JsonNode output = new ObjectMapper().readTree(stdout.toString(StandardCharsets.UTF_8));
        assertTrue(output.get("ok").asBoolean());
        assertTrue(output.get("data").isArray());
    }

    @Test
    void schemaReadAndValidationJsonCommandsShareTheSuccessEnvelope() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = GraphWorkspace.create("commands", "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create(
                "commands", "app", "orders", GraphActor.extractor);
        orders.getColumns().add(ColumnWorkspaceNode.create("id"));
        workspace.getTables().put(orders.getId(), orders);
        store.save(workspace);

        assertSuccessEnvelope(store, "describe", 0, command -> command.setTableName("app.orders"));
        assertSuccessEnvelope(store, "query", 0, command -> command.setTableName("app.orders"));
        assertSuccessEnvelope(store, "path", 0, command -> {
            command.setTableName("app.orders");
            command.setTableName2("app.orders");
        });
        assertSuccessEnvelope(store, "search", 0, command -> command.setKeyword("orders"));
        assertSuccessEnvelope(store, "stats", 0, command -> { });
        assertSuccessEnvelope(store, "validate", 0, command -> { });
    }

    @Test
    void policyViolationJsonRemainsSuccessfulMachineResult() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = GraphWorkspace.create("commands", "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create(
                "commands", "app", "orders", GraphActor.extractor);
        orders.getColumns().add(ColumnWorkspaceNode.create("user_id"));
        TableWorkspaceNode users = TableWorkspaceNode.create(
                "commands", "app", "users", GraphActor.extractor);
        users.getColumns().add(ColumnWorkspaceNode.create("id"));
        workspace.getTables().put(orders.getId(), orders);
        workspace.getTables().put(users.getId(), users);
        store.save(workspace);

        Path rules = temp.resolve("envelope-rules.yaml");
        Files.writeString(rules, """
                kind: PolicyRuleSet
                id: envelope
                title: Envelope
                version: "1"
                rules:
                  - id: pk
                    title: PK
                    category: primary_key_required
                    severity: error
                    enforcement: required
                """);
        assertSuccessEnvelope(store, "policy", 1, command -> command.setCommandArgs(
                List.of("policy", "check", "--rules", rules.toString(), "--json")));
        assertSuccessEnvelope(store, "policy", 0, command -> command.setCommandArgs(
                List.of("policy", "evaluation", "list", "--json")));
    }

    @Test
    void designReviewAndMigrationLintEvaluateCandidatesWithoutChangingTheWorkspace() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = GraphWorkspace.create("commands", "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create(
                "commands", "app", "orders", GraphActor.extractor);
        ColumnWorkspaceNode id = ColumnWorkspaceNode.create("id");
        id.setPrimaryKey(true);
        orders.getColumns().add(id);
        orders.getPrimaryKey().add(GraphIds.columnId("commands", "app", "orders", "id"));
        workspace.getTables().put(orders.getId(), orders);
        store.save(workspace);
        long revision = workspace.getManifest().getRevision();

        Path rules = temp.resolve("review-rules.yaml");
        Files.writeString(rules, """
                kind: PolicyRuleSet
                id: review
                title: Review
                version: "1"
                rules:
                  - id: pk
                    title: PK
                    category: primary_key_required
                    severity: error
                    enforcement: required
                  - id: dml
                    title: DML
                    category: dangerous_dml_guard
                    severity: error
                    enforcement: required
                """);
        Path ddl = temp.resolve("design.sql");
        Files.writeString(ddl, "CREATE TABLE app.invoice (id BIGINT PRIMARY KEY)");
        SchemaActionCommand design = command(store, "design", List.of(
                "design", "review", "--ddl", ddl.toString(), "--rules", rules.toString(), "--json"));
        design.setJsonOutput(true);
        assertEquals(2, design.executeCommand(), "input-only rules require their own review input");

        Path designRules = temp.resolve("design-rules.yaml");
        Files.writeString(designRules, Files.readString(rules).replaceAll("(?s)\\n  - id: dml.*", ""));
        assertEquals(0, command(store, "design", List.of(
                "design", "review", "--ddl", ddl.toString(), "--rules", designRules.toString())).executeCommand());

        Path migration = temp.resolve("migration.sql");
        Files.writeString(migration, "UPDATE app.orders SET id = 2");
        SchemaActionCommand lint = command(store, "migration", List.of(
                "migration", "lint", "--file", migration.toString(), "--rules", rules.toString(), "--json"));
        lint.setJsonOutput(true);
        assertEquals(1, lint.executeCommand());

        List<RuleEvaluation> evaluations = new PolicyService(store).listEvaluations("commands");
        assertTrue(evaluations.stream().anyMatch(item -> "design".equals(item.getReviewType())));
        RuleEvaluation migrationEvaluation = evaluations.stream()
                .filter(item -> "migration".equals(item.getReviewType())).findFirst().orElseThrow();
        assertEquals(migration.toString(), migrationEvaluation.getInputRefs().get("migration"));
        assertNotNull(migrationEvaluation.getInputHashes().get("migration"));
        assertTrue(new PolicyService(store).showEvaluation("commands", migrationEvaluation.getId())
                .violations().stream().allMatch(item -> (migration + ":1").equals(item.getSourceRef())));
        assertNotNull(store.load("commands").getTableByQualifiedName("app.orders"));
        assertFalse(store.load("commands").getTables().values().stream()
                .anyMatch(table -> "invoice".equals(table.getName())));
        assertEquals(revision, store.load("commands").getManifest().getRevision());
    }

    @Test
    void migrationLintRejectsMalformedCompanionSqlWithExitTwo() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = GraphWorkspace.create("commands", "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create(
                "commands", "app", "orders", GraphActor.extractor);
        orders.getColumns().add(ColumnWorkspaceNode.create("legacy"));
        workspace.getTables().put(orders.getId(), orders);
        store.save(workspace);
        Path rules = temp.resolve("migration-rules.yaml");
        Files.writeString(rules, """
                kind: PolicyRuleSet
                id: migration
                title: Migration
                version: "1"
                rules:
                  - id: safe
                    title: Safe migration
                    category: migration_safety_check
                    severity: error
                    enforcement: required
                """);
        Path migration = temp.resolve("migration.sql");
        Files.writeString(migration, "ALTER TABLE app.orders DROP COLUMN legacy");
        Path rollback = temp.resolve("rollback.sql");
        Files.writeString(rollback, "SELECT (");

        SchemaActionCommand lint = command(store, "migration", List.of(
                "migration", "lint", "--file", migration.toString(), "--rules", rules.toString(),
                "--rollback", rollback.toString(), "--json"));
        lint.setJsonOutput(true);

        assertEquals(2, lint.executeCommand());
    }

    @Test
    void importIndexAndDiagramJsonCommandsShareTheSuccessEnvelope() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = GraphWorkspace.create("commands", "mysql");
        workspace.getTables().put(
                "table:commands:app.orders",
                TableWorkspaceNode.create("commands", "app", "orders", GraphActor.extractor));
        store.save(workspace);

        assertSuccessEnvelope(store, "import", 0, command -> command.setSubAction("status"));
        assertSuccessEnvelope(store, "index", 0, command -> command.setSubAction("status"));
        assertSuccessEnvelope(store, "diagram", 0,
                command -> command.setOutputPath(temp.resolve("diagram").toString()));
    }

    @Test
    void missingWorkspaceJsonUsesStableFailureEnvelopeAndNoStderr() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        SchemaActionCommand list = command(store, "list", List.of("list", "--json"));
        list.setJsonOutput(true);

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            System.setErr(err);
            assertEquals(1, list.executeCommand());
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        JsonNode output = new ObjectMapper().readTree(stdout.toString(StandardCharsets.UTF_8));
        assertEquals(false, output.get("ok").asBoolean());
        assertEquals("WORKSPACE_NOT_FOUND", output.get("code").asText());
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
    }

    @Test
    void schemaJsonUsageErrorReturnsTwoAndOneMachineReadableError() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        store.save(GraphWorkspace.create("commands", "mysql"));
        SchemaActionCommand describe = command(
                store, "describe", List.of("describe", "--json"));
        describe.setJsonOutput(true);

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            System.setErr(err);
            assertEquals(2, describe.executeCommand());
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        JsonNode output = new ObjectMapper().readTree(stdout.toString(StandardCharsets.UTF_8));
        assertEquals(false, output.get("ok").asBoolean());
        assertEquals("USAGE_ERROR", output.get("code").asText());
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
    }

    @Test
    void everySchemaMissingInputReturnsUsageContract() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        store.save(GraphWorkspace.create("commands", "mysql"));

        assertFailureEnvelope(store, "query", 2, "USAGE_ERROR", command -> { });
        assertFailureEnvelope(store, "path", 2, "USAGE_ERROR", command -> { });
        assertFailureEnvelope(store, "search", 2, "USAGE_ERROR", command -> { });
        assertFailureEnvelope(store, "import", 2, "USAGE_ERROR", command -> { });
        assertFailureEnvelope(store, "edit", 2, "USAGE_ERROR", command -> { });
        assertFailureEnvelope(store, "add-term", 2, "USAGE_ERROR", command -> { });
        assertFailureEnvelope(store, "add-relation", 2, "USAGE_ERROR", command -> { });
        assertFailureEnvelope(store, "index", 2, "USAGE_ERROR",
                command -> command.setSubAction("unknown"));
    }

    @Test
    void policyCheckPassesWhenTheWorkspaceHasNoBoundRuleSets() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        store.save(GraphWorkspace.create("commands", "mysql"));
        Path policyRoot = temp.resolve("commands/policy");
        Files.createDirectories(policyRoot);
        // 工作区初始化写入的默认内容
        Files.writeString(policyRoot.resolve("bindings.yaml"), "ruleSets: []\n");

        assertTrue(new com.sqlcli.graph.policy.RuleSetLoader().boundRulePaths(temp.resolve("commands")).isEmpty());
        assertSuccessEnvelope(store, "policy", 0, command -> command.setCommandArgs(
                List.of("policy", "check", "--json")));
        assertTrue(new PolicyService(store).listEvaluations("commands").isEmpty(),
                "no bound rules means no evaluation is recorded");

        Files.delete(policyRoot.resolve("bindings.yaml"));
        assertSuccessEnvelope(store, "policy", 0, command -> command.setCommandArgs(
                List.of("policy", "check", "--json")));
    }

    @Test
    void schemaRuntimeFailureUsesMachineReadableContract() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        store.save(GraphWorkspace.create("commands", "mysql"));

        assertFailureEnvelope(store, "describe", 1, "COMMAND_FAILED",
                command -> command.setTableName("app.missing"));
    }

    /**
     * P1 作用域收窄：命名类规则默认只对 design review / migration lint 的新 DDL 生效，
     * 存量图谱上的 {@code policy check} 不产出违规；显式 {@code --all} 才跑。
     *
     * <p>判据来自实测：某遗留库最后一次 policy 报 1487 条违规、1215 条是命名，豁免 0 条，
     * 之后无人再跑——那些表名永远不会改。
     */
    @Test
    void namingRulesSkipTheLegacyGraphUnlessAllIsRequested() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = GraphWorkspace.create("commands", "mysql");
        TableWorkspaceNode legacy = TableWorkspaceNode.create(
                "commands", "app", "ERP_Legacy_Order", GraphActor.extractor);
        workspace.getTables().put(legacy.getId(), legacy);
        store.save(workspace);

        Path rules = temp.resolve("naming-scope.yaml");
        Files.writeString(rules, """
                kind: PolicyRuleSet
                id: naming-scope
                title: Naming scope
                version: "1"
                rules:
                  - id: naming
                    title: Naming
                    category: naming_convention
                    severity: warning
                    enforcement: advisory
                    statement:
                      tableNameRegex: "^[a-z][a-z0-9_]*$"
                """);
        PolicyService policyService = new PolicyService(store);

        assertEquals(0, command(store, "policy", List.of(
                "policy", "check", "--rules", rules.toString())).executeCommand());
        RuleEvaluation narrowed = policyService.listEvaluations("commands").get(0);
        assertEquals(0, narrowed.getEvaluatedRules(), "命名规则默认不跑在存量图谱上");
        assertEquals(0, narrowed.getViolationCount());

        assertEquals(0, command(store, "policy", List.of(
                "policy", "check", "--rules", rules.toString(), "--all")).executeCommand());
        RuleEvaluation full = policyService.listEvaluations("commands").get(0);
        assertEquals(1, full.getEvaluatedRules(), "--all 显式打开");
        assertEquals(1, full.getViolationCount());

        // 规则集里写 scope: all 是长期打开的方式，不用每次记得敲 --all
        Path opened = temp.resolve("naming-opened.yaml");
        Files.writeString(opened, """
                kind: PolicyRuleSet
                id: naming-opened
                title: Naming opened
                version: "1"
                rules:
                  - id: naming
                    title: Naming
                    category: naming_convention
                    severity: warning
                    enforcement: advisory
                    scope: all
                    statement:
                      tableNameRegex: "^[a-z][a-z0-9_]*$"
                """);
        assertEquals(0, command(store, "policy", List.of(
                "policy", "check", "--rules", opened.toString())).executeCommand());
        assertEquals(1, policyService.listEvaluations("commands").get(0).getViolationCount());
    }

    /**
     * 收窄的另一半：design review 只报这次 DDL 碰到的表，不把整个候选工作区的历史违规
     * 一起倒出来——否则真正该看的那几条淹在存量噪音里，和不收窄没区别。
     */
    @Test
    void designReviewReportsOnlyTheTablesTheDdlTouches() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = GraphWorkspace.create("commands", "mysql");
        TableWorkspaceNode legacy = TableWorkspaceNode.create(
                "commands", "app", "legacy_no_pk", GraphActor.extractor);
        workspace.getTables().put(legacy.getId(), legacy);
        store.save(workspace);

        Path rules = temp.resolve("design-scope.yaml");
        Files.writeString(rules, """
                kind: PolicyRuleSet
                id: design-scope
                title: Design scope
                version: "1"
                rules:
                  - id: pk
                    title: PK
                    category: primary_key_required
                    severity: error
                    enforcement: required
                """);
        Path ddl = temp.resolve("design-scope.sql");
        Files.writeString(ddl, "CREATE TABLE app.invoice (id BIGINT)");

        assertEquals(1, command(store, "design", List.of(
                "design", "review", "--ddl", ddl.toString(), "--rules", rules.toString())).executeCommand());

        List<PolicyViolation> violations = new PolicyService(store)
                .showEvaluation("commands", new PolicyService(store).listEvaluations("commands").get(0).getId())
                .violations();
        assertEquals(List.of("table:commands:app.invoice"),
                violations.stream().map(PolicyViolation::getTargetId).toList(),
                "存量的 legacy_no_pk 同样没有主键，但这次 DDL 没碰它");
    }

    private void assertFailureEnvelope(GraphWorkspaceStore store, String action, int expectedExit,
                                       String code,
                                       Consumer<SchemaActionCommand> configure) throws Exception {
        SchemaActionCommand command = command(store, action, List.of(action, "--json"));
        command.setJsonOutput(true);
        configure.accept(command);

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            System.setErr(err);
            assertEquals(expectedExit, command.executeCommand(), action);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        JsonNode output = new ObjectMapper().readTree(stdout.toString(StandardCharsets.UTF_8));
        assertEquals(false, output.get("ok").asBoolean(), action);
        assertEquals(code, output.get("code").asText(), action);
        assertEquals("", stderr.toString(StandardCharsets.UTF_8), action);
    }

    private void assertSuccessEnvelope(GraphWorkspaceStore store, String action, int expectedExit,
                                       Consumer<SchemaActionCommand> configure) throws Exception {
        SchemaActionCommand command = command(store, action, List.of(action, "--json"));
        command.setJsonOutput(true);
        configure.accept(command);

        PrintStream originalOut = System.out;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            assertEquals(expectedExit, command.executeCommand(), action);
        } finally {
            System.setOut(originalOut);
        }
        JsonNode output = new ObjectMapper().readTree(stdout.toString(StandardCharsets.UTF_8));
        assertTrue(output.get("ok").asBoolean(), action);
        assertTrue(output.has("data"), action);
    }

    /**
     * agent 归纳出的约定从 CLI 提议成规则。别名 commands 不在 aliases.yaml 里，
     * 图谱审批按 auto 走：当场写进 structure.yaml 并绑定，之后 policy check 就能跑到它。
     */
    @Test
    void policyRuleAddWritesAndBindsTheRuleOnAnAutoAlias() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        store.save(GraphWorkspace.create("commands", "mysql"));

        SchemaActionCommand add = command(store, "policy", List.of("policy", "rule", "add",
                "--category", "required_business_columns",
                "--reason", "抽查 12 张业务表都带 create_time",
                "--column", "created_at datetime notnull comment=创建时间",
                "--column", "updated_at datetime notnull",
                "--enforcement", "required"));
        assertEquals(0, add.executeCommand());

        RuleSet ruleSet = new RuleSetLoader().load(temp.resolve("commands/policy/rules/structure.yaml"));
        PolicyRule rule = ruleSet.getRules().get(0);
        assertEquals("required_business_columns", rule.getCategory());
        assertEquals(PolicyEnforcement.required, rule.getEnforcement());
        assertEquals(List.of("base_table"), rule.getWhen().get("tableTypeAny"));
        assertEquals(2, ((List<?>) rule.getStatement().get("columns")).size());
        assertTrue(Files.readString(temp.resolve("commands/policy/bindings.yaml")).contains("rules/structure.yaml"));

        // 绑定之后 policy check 真的会跑它：空工作区没有表，0 违规，退出码 0
        assertEquals(0, command(store, "policy", List.of("policy", "check")).executeCommand());
        assertTrue(new PolicyService(store).listEvaluations("commands").stream()
                .anyMatch(item -> "structure-policy".equals(item.getRuleSetId())));

        // 没 --reason 直接是用法错误，什么都不写
        assertEquals(2, command(store, "policy", List.of("policy", "rule", "add",
                "--category", "required_business_columns", "--column", "x")).executeCommand());
    }

    private SchemaActionCommand command(GraphWorkspaceStore store, String action, List<String> args) {
        SchemaActionCommand command = new SchemaActionCommand(store);
        command.setAlias("commands");
        command.setAction(action);
        command.setCommandArgs(args);
        return command;
    }
}
