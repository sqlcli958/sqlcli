package com.sqlcli.graph.policy;

import com.sqlcli.graph.workspace.*;
import com.sqlcli.testsupport.Symlinks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class PolicyServiceTest {
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
    void persistsEvaluationsViolationsAndWaiverLifecycleWithoutChangingWorkspaceRevision() throws Exception {
        GraphWorkspaceStore workspaceStore = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = workspace();
        workspaceStore.save(workspace);
        long revision = workspace.getManifest().getRevision();
        Path rules = temp.resolve("rules.yaml");
        Files.writeString(rules, rulesYaml());
        PolicyService service = new PolicyService(workspaceStore);

        PolicyCheckResult first = service.check("policy", rules, GraphActor.human);
        assertEquals(1, first.exitCode());
        assertEquals(PolicyEvaluationStatus.violations, first.evaluation().getStatus());
        assertTrue(first.violations().stream().map(PolicyViolation::getRuleId).distinct().count() >= 5);
        assertFalse(service.currentValidationIssues("policy").isEmpty());
        PolicyRun stored = service.showEvaluation("policy", first.evaluation().getId());
        assertEquals(first.violations().size(), stored.violations().size());
        String fingerprint = first.violations().get(0).getFingerprint();

        PolicyCheckResult second = service.check("policy", rules, GraphActor.human);
        assertNotEquals(first.evaluation().getId(), second.evaluation().getId());
        assertTrue(second.violations().stream().anyMatch(item -> item.getFingerprint().equals(fingerprint)));

        PolicyViolation waivedTarget = second.violations().stream()
                .filter(item -> item.getEnforcement() == PolicyEnforcement.required).findFirst().orElseThrow();
        RuleWaiver waiver = service.addWaiver("policy", waivedTarget.getRuleId(), waivedTarget.getTargetId(),
                "accepted until migration", LocalDateTime.now().plusDays(1), GraphActor.human);
        PolicyCheckResult third = service.check("policy", rules, GraphActor.human);
        PolicyViolation waived = third.violations().stream()
                .filter(item -> item.getFingerprint().equals(waivedTarget.getFingerprint())).findFirst().orElseThrow();
        assertEquals(PolicyViolationStatus.waived, waived.getStatus());
        assertEquals(waiver.getId(), waived.getWaiverId());
        assertEquals(waivedTarget.getSeverity(), waived.getSeverity());
        assertEquals(waivedTarget.getEnforcement(), waived.getEnforcement());

        service.revokeWaiver("policy", waiver.getId(), "migration cancelled", GraphActor.human);
        PolicyCheckResult fourth = service.check("policy", rules, GraphActor.human);
        assertEquals(PolicyViolationStatus.open, fourth.violations().stream()
                .filter(item -> item.getFingerprint().equals(waivedTarget.getFingerprint())).findFirst().orElseThrow()
                .getStatus());
        assertEquals(revision, workspaceStore.load("policy").getManifest().getRevision());
    }

    @Test
    void malformedRulesPersistErrorEvaluationAndInvalidWaiversAreRejected() throws Exception {
        GraphWorkspaceStore workspaceStore = new GraphWorkspaceStore(temp);
        workspaceStore.save(workspace());
        Path malformed = temp.resolve("bad.yaml");
        Files.writeString(malformed, "kind: [broken");
        PolicyService service = new PolicyService(workspaceStore);
        PolicyCheckResult result = service.check("policy", malformed, GraphActor.agent);
        assertEquals(2, result.exitCode());
        assertEquals(PolicyEvaluationStatus.error, result.evaluation().getStatus());
        assertEquals(PolicyEvaluationStatus.error,
                service.showEvaluation("policy", result.evaluation().getId()).evaluation().getStatus());
        assertThrows(IllegalArgumentException.class, () -> service.addWaiver(
                "policy", "rule", "target", "", LocalDateTime.now().plusDays(1), GraphActor.human));
        assertThrows(IllegalArgumentException.class, () -> service.addWaiver(
                "policy", "rule", "target", "reason", LocalDateTime.now().minusMinutes(1), GraphActor.human));
    }

    @Test
    void allFiveRulesCanPassAndUnknownConditionFailsBeforeEvaluation() throws Exception {
        GraphWorkspaceStore workspaceStore = new GraphWorkspaceStore(temp);
        GraphWorkspace compliant = GraphWorkspace.create("compliant", "mysql");
        TableWorkspaceNode table = TableWorkspaceNode.create("compliant", "app", "orders", GraphActor.extractor);
        table.getTags().add("business");
        for (String name : List.of("id", "created_at", "updated_at", "amount", "secret_phone")) {
            ColumnWorkspaceNode column = ColumnWorkspaceNode.create(name);
            column.getDataType().setNormalized("amount".equals(name) ? "decimal" : "varchar");
            if ("secret_phone".equals(name)) {
                column.setSemanticType(SemanticType.phone);
                column.getAttributes().put("sensitivePolicy", "mask");
            }
            table.getColumns().add(column);
        }
        table.findColumn("id").setPrimaryKey(true);
        table.getPrimaryKey().add(GraphIds.columnId("compliant", "app", "orders", "id"));
        TableIndexMetadata index = new TableIndexMetadata();
        index.setName("idx_orders_id");
        index.setColumns(List.of("id"));
        table.getIndexes().add(index);
        compliant.getTables().put(table.getId(), table);
        workspaceStore.save(compliant);
        Path rules = temp.resolve("pass-rules.yaml");
        Files.writeString(rules, rulesYaml());
        PolicyCheckResult passed = new PolicyService(workspaceStore).check(
                "compliant", rules, GraphActor.human);
        assertEquals(0, passed.exitCode());
        assertEquals(PolicyEvaluationStatus.passed, passed.evaluation().getStatus());
        assertTrue(passed.violations().isEmpty());

        Path unknown = temp.resolve("unknown.yaml");
        Files.writeString(unknown, rulesYaml().replace("tableTagsAny: [business]",
                "unsupportedCondition: true"));
        assertThrows(IllegalArgumentException.class, () -> new RuleSetLoader().load(unknown));

        Path unknownCategory = temp.resolve("unknown-category.yaml");
        Files.writeString(unknownCategory, rulesYaml().replace(
                "category: naming_convention", "category: unsupported_category"));
        assertThrows(IllegalArgumentException.class, () -> new RuleSetLoader().load(unknownCategory));
    }

    @Test
    void nonMatchingRuleSetIsPersistedAsSkipped() throws Exception {
        GraphWorkspaceStore workspaceStore = new GraphWorkspaceStore(temp);
        workspaceStore.save(workspace());
        Path rules = temp.resolve("skip-rules.yaml");
        Files.writeString(rules, rulesYaml().replace("dbType: mysql", "dbType: postgresql"));

        PolicyCheckResult result = new PolicyService(workspaceStore).check("policy", rules, GraphActor.human);

        assertEquals(0, result.exitCode());
        assertEquals(PolicyEvaluationStatus.skipped, result.evaluation().getStatus());
        assertEquals(0, result.evaluation().getEvaluatedRules());
    }

    @Test
    void controlledVocabularyReportsAliasesWithoutRenamingMetadata() throws Exception {
        GraphWorkspaceStore workspaceStore = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = GraphWorkspace.create("vocabulary", "mysql");
        TableWorkspaceNode table = TableWorkspaceNode.create(
                "vocabulary", "app", "customers", GraphActor.extractor);
        table.getColumns().add(ColumnWorkspaceNode.create("mobile"));
        workspace.getTables().put(table.getId(), table);
        workspaceStore.save(workspace);
        Path rules = temp.resolve("vocabulary.yaml");
        Files.writeString(rules, """
                kind: PolicyRuleSet
                id: vocabulary
                title: Controlled vocabulary
                version: "1"
                rules:
                  - id: standard-phone
                    title: Standard phone field
                    category: controlled_vocabulary
                    severity: warning
                    enforcement: advisory
                    statement:
                      concepts:
                        - standard: phone
                          aliases: [mobile, tel]
                """);

        PolicyCheckResult result = new PolicyService(workspaceStore).check(
                "vocabulary", rules, GraphActor.human);

        assertEquals(0, result.exitCode());
        PolicyViolation violation = assertSingleViolation(result, "standard-phone");
        assertEquals("name", violation.getField());
        assertTrue(violation.getMessage().contains("phone"));
        assertNotNull(workspaceStore.load("vocabulary").getTableByQualifiedName("app.customers")
                .findColumn("mobile"));
    }

    @Test
    void businessUniqueIndexMatchesTheCompleteSingleOrCompositeKey() throws Exception {
        GraphWorkspaceStore workspaceStore = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = GraphWorkspace.create("unique-key", "mysql");
        TableWorkspaceNode table = TableWorkspaceNode.create(
                "unique-key", "app", "orders", GraphActor.extractor);
        for (String name : List.of("tenant_id", "order_no")) {
            table.getColumns().add(ColumnWorkspaceNode.create(name));
        }
        TableIndexMetadata unique = new TableIndexMetadata();
        unique.setName("uk_orders_tenant_no");
        unique.setUnique(true);
        unique.setColumns(List.of("tenant_id", "order_no"));
        table.getIndexes().add(unique);
        workspace.getTables().put(table.getId(), table);
        workspaceStore.save(workspace);
        Path rules = temp.resolve("unique-key.yaml");
        Files.writeString(rules, """
                kind: PolicyRuleSet
                id: unique-key
                title: Business unique keys
                version: "1"
                rules:
                  - id: composite-business-key
                    title: Composite business key
                    category: business_unique_index
                    severity: error
                    enforcement: required
                    statement:
                      columns: [tenant_id, order_no]
                  - id: order-number-alone
                    title: Order number alone
                    category: business_unique_index
                    severity: warning
                    enforcement: advisory
                    statement:
                      columns: [order_no]
                """);

        PolicyCheckResult result = new PolicyService(workspaceStore).check(
                "unique-key", rules, GraphActor.human);

        PolicyViolation violation = assertSingleViolation(result, "order-number-alone");
        assertEquals("indexes", violation.getField());
    }

    @Test
    void businessUniqueIndexCandidatesCanMatchSemanticTypeOrTags() throws Exception {
        GraphWorkspaceStore workspaceStore = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = GraphWorkspace.create("unique-candidates", "mysql");
        TableWorkspaceNode table = TableWorkspaceNode.create(
                "unique-candidates", "app", "customers", GraphActor.extractor);
        ColumnWorkspaceNode externalId = ColumnWorkspaceNode.create("external_id");
        externalId.setSemanticType(SemanticType.code);
        ColumnWorkspaceNode legacyNo = ColumnWorkspaceNode.create("legacy_no");
        legacyNo.getAttributes().put("tags", List.of("business-key"));
        table.getColumns().addAll(List.of(externalId, legacyNo, ColumnWorkspaceNode.create("tenant_id")));
        TableIndexMetadata externalUnique = new TableIndexMetadata();
        externalUnique.setName("uk_customers_external");
        externalUnique.setUnique(true);
        externalUnique.setColumns(List.of("external_id"));
        TableIndexMetadata legacyComposite = new TableIndexMetadata();
        legacyComposite.setName("uk_customers_legacy_tenant");
        legacyComposite.setUnique(true);
        legacyComposite.setColumns(List.of("legacy_no", "tenant_id"));
        table.getIndexes().addAll(List.of(externalUnique, legacyComposite));
        workspace.getTables().put(table.getId(), table);
        workspaceStore.save(workspace);
        Path rules = temp.resolve("unique-candidates.yaml");
        Files.writeString(rules, """
                kind: PolicyRuleSet
                id: unique-candidates
                title: Business key candidates
                version: "1"
                rules:
                  - id: semantic-business-key
                    title: Semantic business key
                    category: business_unique_index
                    severity: error
                    enforcement: required
                    when:
                      semanticTypeAny: [business_id]
                  - id: tagged-business-key
                    title: Tagged business key
                    category: business_unique_index
                    severity: warning
                    enforcement: advisory
                    when:
                      columnTagsAny: [business-key]
                """);

        PolicyCheckResult result = new PolicyService(workspaceStore).check(
                "unique-candidates", rules, GraphActor.human);

        PolicyViolation violation = assertSingleViolation(result, "tagged-business-key");
        assertTrue(violation.getTargetId().endsWith(".legacy_no"));
    }

    @Test
    void relationColumnIndexUsesCompositeLeftPrefixAndUnverifiedJoinsAreAdvisory() throws Exception {
        GraphWorkspaceStore workspaceStore = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = GraphWorkspace.create("relation-index", "mysql");
        TableWorkspaceNode source = TableWorkspaceNode.create(
                "relation-index", "app", "orders", GraphActor.extractor);
        TableWorkspaceNode target = TableWorkspaceNode.create(
                "relation-index", "app", "customers", GraphActor.extractor);
        for (String name : List.of("tenant_id", "customer_no", "unindexed_id")) {
            source.getColumns().add(ColumnWorkspaceNode.create(name));
        }
        for (String name : List.of("tenant_id", "customer_no", "id")) {
            target.getColumns().add(ColumnWorkspaceNode.create(name));
        }
        TableIndexMetadata index = new TableIndexMetadata();
        index.setName("idx_orders_customer");
        index.setColumns(List.of("tenant_id", "customer_no", "created_at"));
        source.getIndexes().add(index);
        workspace.getTables().put(source.getId(), source);
        workspace.getTables().put(target.getId(), target);
        addRelation(workspace, RelationType.foreign_key, source, "tenant_id",
                target, "tenant_id", "foreign-key:orders-customer", true);
        addRelation(workspace, RelationType.foreign_key, source, "customer_no",
                target, "customer_no", "foreign-key:orders-customer", true);
        addRelation(workspace, RelationType.join_observed, source, "unindexed_id",
                target, "id", "mapper.xml:12", false);
        workspaceStore.save(workspace);
        Path rules = temp.resolve("relation-index.yaml");
        Files.writeString(rules, """
                kind: PolicyRuleSet
                id: relation-index
                title: Relation indexes
                version: "1"
                rules:
                  - id: relation-source-index
                    title: Relation source index
                    category: relation_column_index
                    severity: error
                    enforcement: required
                """);

        PolicyCheckResult result = new PolicyService(workspaceStore).check(
                "relation-index", rules, GraphActor.human);

        PolicyViolation violation = assertSingleViolation(result, "relation-source-index");
        assertEquals(PolicyEnforcement.advisory, violation.getEnforcement());
        assertEquals(GraphIds.columnId("relation-index", "app", "orders", "unindexed_id"),
                violation.getTargetId());
    }

    /**
     * 拒绝候选不再删边（P1 第 6 条）之后，被人明确拒绝的 join 关系如果还能触发
     * "关系源列要建索引" 这条规则，等于规则评估架空了那次拒绝——人说这条关系不算数，
     * 系统却照样拿它去要求整改。这里复用上一个测试的 fixture，唯一区别是把那条
     * 无索引的 join_observed 关系标成 ignored，断言违规从 1 条变成 0 条。
     */
    @Test
    void ignoredRelationIsExcludedFromRelationColumnIndexRule() throws Exception {
        GraphWorkspaceStore workspaceStore = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = GraphWorkspace.create("relation-index-ignored", "mysql");
        TableWorkspaceNode source = TableWorkspaceNode.create(
                "relation-index-ignored", "app", "orders", GraphActor.extractor);
        TableWorkspaceNode target = TableWorkspaceNode.create(
                "relation-index-ignored", "app", "customers", GraphActor.extractor);
        source.getColumns().add(ColumnWorkspaceNode.create("unindexed_id"));
        target.getColumns().add(ColumnWorkspaceNode.create("id"));
        workspace.getTables().put(source.getId(), source);
        workspace.getTables().put(target.getId(), target);
        addRelation(workspace, RelationType.join_observed, source, "unindexed_id",
                target, "id", "mapper.xml:12", false);
        // 拒绝：状态转 ignored，不删边——就是这次改动要验证的读法
        workspace.getRelations().get(0).setStatus(GraphStatus.ignored);
        workspaceStore.save(workspace);
        Path rules = temp.resolve("relation-index-ignored.yaml");
        Files.writeString(rules, """
                kind: PolicyRuleSet
                id: relation-index-ignored
                title: Relation indexes
                version: "1"
                rules:
                  - id: relation-source-index
                    title: Relation source index
                    category: relation_column_index
                    severity: error
                    enforcement: required
                """);

        PolicyCheckResult result = new PolicyService(workspaceStore).check(
                "relation-index-ignored", rules, GraphActor.human);

        assertTrue(result.violations().isEmpty(),
                "已忽略的关系不该再被当作 join 关系去要求索引: " + result.violations());
    }

    @Test
    void joinColumnTypeMatchDistinguishesCompatibleSuspiciousAndIncompatibleTypes() throws Exception {
        GraphWorkspaceStore workspaceStore = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = GraphWorkspace.create("join-types", "postgresql");
        TableWorkspaceNode left = TableWorkspaceNode.create(
                "join-types", "app", "orders", GraphActor.extractor);
        TableWorkspaceNode right = TableWorkspaceNode.create(
                "join-types", "app", "customers", GraphActor.extractor);
        addTypedColumn(left, "exact_id", "bigint", null);
        addTypedColumn(right, "exact_id", "bigint", null);
        addTypedColumn(left, "code", "varchar", 32);
        addTypedColumn(right, "code", "varchar", 64);
        addTypedColumn(left, "legacy_id", "integer", null);
        addTypedColumn(right, "legacy_id", "varchar", 20);
        addTypedColumn(left, "description", "varchar", 255);
        addTypedColumn(right, "description", "text", null);
        workspace.getTables().put(left.getId(), left);
        workspace.getTables().put(right.getId(), right);
        for (String column : List.of("exact_id", "code", "legacy_id", "description")) {
            addRelation(workspace, RelationType.join_observed, left, column,
                    right, column, "query.sql:" + column, true);
        }
        workspaceStore.save(workspace);
        Path rules = temp.resolve("join-types.yaml");
        Files.writeString(rules, """
                kind: PolicyRuleSet
                id: join-types
                title: Join type compatibility
                version: "1"
                rules:
                  - id: join-column-types
                    title: Join column types
                    category: join_column_type_match
                    severity: error
                    enforcement: required
                    statement:
                      compatibleTypeGroups:
                        - [varchar, text]
                """);

        PolicyCheckResult result = new PolicyService(workspaceStore).check(
                "join-types", rules, GraphActor.human);

        assertEquals(2, result.violations().size());
        assertTrue(result.violations().stream().anyMatch(item ->
                item.getMessage().startsWith("suspicious:")));
        assertTrue(result.violations().stream().anyMatch(item ->
                item.getMessage().startsWith("incompatible:")));
    }

    @Test
    void statusFieldDictionaryMatchesNameSemanticTypeOrTagsWithoutSamplingData() throws Exception {
        GraphWorkspaceStore workspaceStore = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = GraphWorkspace.create("status-fields", "mysql");
        TableWorkspaceNode table = TableWorkspaceNode.create(
                "status-fields", "app", "orders", GraphActor.extractor);
        ColumnWorkspaceNode byName = ColumnWorkspaceNode.create("order_status");
        ColumnValueHints hints = new ColumnValueHints();
        hints.setEnumValues(List.of("NEW", "PAID"));
        byName.setValueHints(hints);
        ColumnWorkspaceNode bySemanticType = ColumnWorkspaceNode.create("state_code");
        bySemanticType.setSemanticType(SemanticType.status);
        ColumnWorkspaceNode byTag = ColumnWorkspaceNode.create("phase");
        byTag.getAttributes().put("tags", List.of("status"));
        byTag.getAttributes().put("sampleValues", List.of("must-not-count"));
        table.getColumns().addAll(List.of(byName, bySemanticType, byTag));
        workspace.getTables().put(table.getId(), table);

        // state_code 的取值来源是一条指向字典表的关系，不是属性字符串：
        // 属性装不下 dict_type 这类过滤条件，也不受端点校验。
        TableWorkspaceNode dict = TableWorkspaceNode.create(
                "status-fields", "app", "sys_dict_item", GraphActor.extractor);
        dict.getColumns().add(ColumnWorkspaceNode.create("dict_key"));
        workspace.getTables().put(dict.getId(), dict);
        addRelation(workspace, RelationType.join_observed,
                table, "state_code", dict, "dict_key", "test:dictionary", false);

        workspaceStore.save(workspace);
        Path rules = temp.resolve("status-fields.yaml");
        Files.writeString(rules, """
                kind: PolicyRuleSet
                id: status-fields
                title: Status dictionaries
                version: "1"
                rules:
                  - id: status-by-name
                    title: Status by name
                    category: status_field_dictionary
                    severity: warning
                    enforcement: advisory
                    when:
                      columnNameRegex: "(?i)status"
                  - id: status-by-semantic-type
                    title: Status by semantic type
                    category: status_field_dictionary
                    severity: warning
                    enforcement: advisory
                    when:
                      semanticTypeAny: [status]
                  - id: status-by-tag
                    title: Status by tag
                    category: status_field_dictionary
                    severity: warning
                    enforcement: advisory
                    when:
                      columnTagsAny: [status]
                """);

        PolicyCheckResult result = new PolicyService(workspaceStore).check(
                "status-fields", rules, GraphActor.human);

        PolicyViolation violation = assertSingleViolation(result, "status-by-tag");
        assertEquals("dictionary", violation.getField());
        assertTrue(violation.getTargetId().endsWith(".phase"));
    }

    /**
     * 同一份 fixture，唯一区别是把 state_code -> dict_key 那条字典关系标成 ignored。
     * 人拒绝了"这是字典关系"这个判断之后，state_code 就不再满足
     * "有枚举/字典/说明三选一"，status-by-semantic-type 规则应该从不违规变成违规——
     * 这是 hasDictionaryRelation 必须过滤 ignored 边的直接证据，不是画布那种间接影响。
     */
    @Test
    void ignoredDictionaryRelationNoLongerSatisfiesStatusFieldDictionaryRule() throws Exception {
        GraphWorkspaceStore workspaceStore = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = GraphWorkspace.create("status-fields-ignored", "mysql");
        TableWorkspaceNode table = TableWorkspaceNode.create(
                "status-fields-ignored", "app", "orders", GraphActor.extractor);
        ColumnWorkspaceNode bySemanticType = ColumnWorkspaceNode.create("state_code");
        bySemanticType.setSemanticType(SemanticType.status);
        table.getColumns().add(bySemanticType);
        workspace.getTables().put(table.getId(), table);
        TableWorkspaceNode dict = TableWorkspaceNode.create(
                "status-fields-ignored", "app", "sys_dict_item", GraphActor.extractor);
        dict.getColumns().add(ColumnWorkspaceNode.create("dict_key"));
        workspace.getTables().put(dict.getId(), dict);
        addRelation(workspace, RelationType.join_observed,
                table, "state_code", dict, "dict_key", "test:dictionary", false);
        workspace.getRelations().get(0).setStatus(GraphStatus.ignored);
        workspaceStore.save(workspace);
        Path rules = temp.resolve("status-fields-ignored.yaml");
        Files.writeString(rules, """
                kind: PolicyRuleSet
                id: status-fields-ignored
                title: Status dictionaries
                version: "1"
                rules:
                  - id: status-by-semantic-type
                    title: Status by semantic type
                    category: status_field_dictionary
                    severity: warning
                    enforcement: advisory
                    when:
                      semanticTypeAny: [status]
                """);

        PolicyCheckResult result = new PolicyService(workspaceStore).check(
                "status-fields-ignored", rules, GraphActor.human);

        PolicyViolation violation = assertSingleViolation(result, "status-by-semantic-type");
        assertTrue(violation.getTargetId().endsWith(".state_code"));
    }

    @Test
    void dangerousDmlGuardReusesExecutionWhereClauseRule() throws Exception {
        GraphWorkspace workspace = GraphWorkspace.create("dml-policy", "mysql");
        TableWorkspaceNode table = TableWorkspaceNode.create(
                "dml-policy", "app", "orders", GraphActor.extractor);
        table.getColumns().add(ColumnWorkspaceNode.create("id"));
        table.getPrimaryKey().add(GraphIds.columnId("dml-policy", "app", "orders", "id"));
        workspace.getTables().put(table.getId(), table);
        Path rulesPath = temp.resolve("dml-policy.yaml");
        Files.writeString(rulesPath, """
                kind: PolicyRuleSet
                id: dml-policy
                title: Dangerous DML
                version: "1"
                rules:
                  - id: safe-dml
                    title: Safe DML
                    category: dangerous_dml_guard
                    severity: error
                    enforcement: blocking
                """);
        RuleSet rules = new RuleSetLoader().load(rulesPath);

        List<PolicyViolation> violations = new PolicyEvaluator().evaluate(
                workspace, rules, List.of(), "evaluation-dml",
                Map.of(
                        "sql", "UPDATE app.orders SET status = 'paid'",
                        "verificationSql", "SELECT status FROM app.orders WHERE id = 1"));

        assertEquals(1, violations.size());
        assertEquals("whereClause", violations.get(0).getField());
    }

    @Test
    void dangerousDmlGuardRequiresPrimaryKeyFactsAndVerificationSql() throws Exception {
        GraphWorkspace workspace = GraphWorkspace.create("dml-facts", "mysql");
        TableWorkspaceNode table = TableWorkspaceNode.create(
                "dml-facts", "app", "orders", GraphActor.extractor);
        table.getColumns().add(ColumnWorkspaceNode.create("id"));
        workspace.getTables().put(table.getId(), table);
        Path rulesPath = temp.resolve("dml-facts.yaml");
        Files.writeString(rulesPath, """
                kind: PolicyRuleSet
                id: dml-facts
                title: Dangerous DML facts
                version: "1"
                rules:
                  - id: recoverable-dml
                    title: Recoverable DML
                    category: dangerous_dml_guard
                    severity: error
                    enforcement: blocking
                """);

        List<PolicyViolation> violations = new PolicyEvaluator().evaluate(
                workspace, new RuleSetLoader().load(rulesPath), List.of(), "evaluation-dml-facts",
                Map.of("sql", "DELETE FROM app.orders WHERE id = 1"));

        assertEquals(2, violations.size());
        assertEquals(List.of("primaryKey", "verificationSql"),
                violations.stream().map(PolicyViolation::getField).sorted().toList());
    }

    @Test
    void migrationSafetyCheckReportsRiskPlansAndBackfillStrategyAtSource() throws Exception {
        GraphWorkspace workspace = GraphWorkspace.create("migration-policy", "mysql");
        Path rulesPath = temp.resolve("migration-policy.yaml");
        Files.writeString(rulesPath, """
                kind: PolicyRuleSet
                id: migration-policy
                title: Migration safety
                version: "1"
                rules:
                  - id: safe-migration
                    title: Safe migration
                    category: migration_safety_check
                    severity: error
                    enforcement: required
                """);
        List<Map<String, Object>> changes = List.of(
                Map.of(
                        "changeType", "DROP_COLUMN",
                        "targetId", "column:migration-policy:app.orders.legacy_code",
                        "sourceRef", "V2__orders.sql:2"),
                Map.of(
                        "changeType", "BACKFILL",
                        "targetId", "table:migration-policy:app.orders",
                        "sourceRef", "V2__orders.sql:3"));

        List<PolicyViolation> violations = new PolicyEvaluator().evaluate(
                workspace, new RuleSetLoader().load(rulesPath), List.of(), "evaluation-migration",
                Map.of("changes", changes));

        assertEquals(8, violations.size());
        assertTrue(violations.stream().anyMatch(item ->
                "DROP_COLUMN".equals(item.getChangeType())
                        && "V2__orders.sql:2".equals(item.getSourceRef())));
        assertEquals(List.of("batchStrategy", "checkpointStrategy", "idempotencyStrategy"),
                violations.stream().filter(item -> "BACKFILL".equals(item.getChangeType()))
                        .map(PolicyViolation::getField)
                        .filter(field -> field.endsWith("Strategy"))
                        .sorted().toList());
    }

    @Test
    void migrationSafetyAllowsSafeAddColumnWithoutCompanionFiles() throws Exception {
        GraphWorkspace workspace = GraphWorkspace.create("safe-migration", "mysql");
        Path rulesPath = temp.resolve("safe-migration.yaml");
        Files.writeString(rulesPath, """
                kind: PolicyRuleSet
                id: safe-migration
                title: Safe migration
                version: "1"
                rules:
                  - id: migration
                    title: Migration
                    category: migration_safety_check
                    severity: error
                    enforcement: required
                """);

        List<PolicyViolation> violations = new PolicyEvaluator().evaluate(
                workspace, new RuleSetLoader().load(rulesPath), List.of(), "evaluation-safe",
                Map.of("changes", List.of(Map.of(
                        "changeType", "ADD",
                        "objectType", "column",
                        "targetId", "column:safe-migration:app.orders.note",
                        "sourceRef", "V1.sql:1",
                        "destructive", false))));

        assertTrue(violations.isEmpty(), () -> violations.toString());
    }

    @Test
    void designReviewViolationsInheritSourceLinesAndSortByThem() throws Exception {
        GraphWorkspace workspace = GraphWorkspace.create("design-source", "mysql");
        TableWorkspaceNode second = TableWorkspaceNode.create(
                "design-source", "app", "second_table", GraphActor.agent);
        TableWorkspaceNode first = TableWorkspaceNode.create(
                "design-source", "app", "first_table", GraphActor.agent);
        workspace.getTables().put(second.getId(), second);
        workspace.getTables().put(first.getId(), first);
        Path rulesPath = temp.resolve("design-source.yaml");
        Files.writeString(rulesPath, """
                kind: PolicyRuleSet
                id: design-source
                title: Design source
                version: "1"
                rules:
                  - id: pk
                    title: Primary key
                    category: primary_key_required
                    severity: error
                    enforcement: required
                """);
        List<Map<String, Object>> changes = List.of(
                Map.of("changeType", "CREATE", "objectType", "table",
                        "targetId", second.getId(), "sourceRef", "design.sql:2"),
                Map.of("changeType", "CREATE", "objectType", "table",
                        "targetId", first.getId(), "sourceRef", "design.sql:1"));

        List<PolicyViolation> violations = new PolicyEvaluator().evaluate(
                workspace, new RuleSetLoader().load(rulesPath), List.of(), "evaluation-source",
                Map.of("reviewType", "design", "changes", changes));

        assertEquals(List.of("design.sql:1", "design.sql:2"),
                violations.stream().map(PolicyViolation::getSourceRef).toList());
        assertTrue(violations.stream().allMatch(item -> "CREATE".equals(item.getChangeType())));
    }

    @Test
    void reviewAuditFieldsPersistWithStableInputHashAndViolationFingerprint() throws Exception {
        GraphWorkspaceStore workspaceStore = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = GraphWorkspace.create("review-audit", "mysql");
        TableWorkspaceNode table = TableWorkspaceNode.create(
                "review-audit", "app", "orders", GraphActor.extractor);
        table.getColumns().add(ColumnWorkspaceNode.create("id"));
        table.getPrimaryKey().add(GraphIds.columnId("review-audit", "app", "orders", "id"));
        workspace.getTables().put(table.getId(), table);
        workspaceStore.save(workspace);
        long revision = workspace.getManifest().getRevision();
        Path rules = temp.resolve("review-audit.yaml");
        Files.writeString(rules, """
                kind: PolicyRuleSet
                id: review-audit
                title: Review audit
                version: "1"
                rules:
                  - id: safe-dml
                    title: Safe DML
                    category: dangerous_dml_guard
                    severity: error
                    enforcement: blocking
                """);
        Map<String, Object> input = new LinkedHashMap<>(Map.of(
                "reviewType", "sql-review",
                "inputType", "sql",
                "inputRef", "migration/V3.sql:7",
                "sql", "DELETE FROM app.orders",
                "verificationSql", "SELECT 1"));
        input.put("migrationSql", "DELETE FROM app.orders");
        input.put("migrationRef", "migration/V3.sql");
        input.put("rollbackSql", "INSERT INTO app.orders(id) VALUES (1)");
        input.put("rollbackRef", "migration/V3.rollback.sql");
        input.put("precheckSql", "SELECT 1");
        input.put("precheckRef", "migration/V3.pre.sql");
        input.put("postcheckSql", "SELECT 1");
        input.put("postcheckRef", "migration/V3.post.sql");
        PolicyService service = new PolicyService(workspaceStore);

        PolicyCheckResult first = service.check("review-audit", rules, GraphActor.agent, input);
        PolicyCheckResult second = service.check("review-audit", rules, GraphActor.agent, input);

        assertNotEquals(first.evaluation().getId(), second.evaluation().getId());
        assertEquals("sql-review", second.evaluation().getReviewType());
        assertEquals("sql", second.evaluation().getInputType());
        assertEquals("migration/V3.sql:7", second.evaluation().getInputRef());
        assertEquals(first.evaluation().getInputHash(), second.evaluation().getInputHash());
        assertEquals(Set.of("migration", "rollback", "precheck", "postcheck"),
                second.evaluation().getInputHashes().keySet());
        assertEquals("migration/V3.rollback.sql", second.evaluation().getInputRefs().get("rollback"));
        assertEquals(second.evaluation().getInputHashes(),
                service.showEvaluation("review-audit", second.evaluation().getId())
                        .evaluation().getInputHashes());
        assertEquals(first.violations().get(0).getFingerprint(),
                second.violations().get(0).getFingerprint());
        assertEquals(revision, workspaceStore.load("review-audit").getManifest().getRevision());
    }

    @Test
    void builtinDialectRulesLoadFromClasspath() throws Exception {
        RuleSet rules = new RuleSetLoader().loadBuiltin("dialect/mysql/8.0.yaml");

        assertEquals("mysql-8", rules.getId());
        assertEquals("dialect-ruleset", rules.getKind());
        assertThrows(IllegalArgumentException.class,
                () -> new RuleSetLoader().loadBuiltin("../settings.yaml"));
    }

    @Test
    void aliasPolicyBindingsCannotFollowRulesDirectoryOutsideWorkspace() throws Exception {
        Path workspace = temp.resolve("linked-rules");
        Path policy = workspace.resolve("policy");
        Path outside = temp.resolve("outside-rules");
        Files.createDirectories(policy);
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("app.yaml"), rulesYaml());
        Symlinks.createOrAbort(policy.resolve("rules"), outside);

        Files.writeString(policy.resolve("bindings.yaml"), "ruleSets:\n  - rules/app.yaml\n");

        assertThrows(java.io.IOException.class,
                () -> new RuleSetLoader().boundRulePaths(workspace));
    }

    @Test
    void requiredBusinessColumnsValidateConfiguredMetadataForBaseTables() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = GraphWorkspace.create("column-standard", "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create(
                "column-standard", "app", "orders", GraphActor.extractor);
        orders.setTableType(TableType.base_table);
        ColumnWorkspaceNode creator = ColumnWorkspaceNode.create("creator");
        creator.getDataType().setNormalized("varchar");
        creator.getDataType().setLength(32);
        creator.setDefaultValue("x");
        creator.setComment("wrong");
        ColumnWorkspaceNode updateTime = ColumnWorkspaceNode.create("update_time");
        updateTime.getDataType().setNormalized("timestamp");
        updateTime.setNullable(true);
        updateTime.setDefaultValue("0");
        updateTime.setComment("wrong");
        orders.getColumns().addAll(List.of(creator, updateTime));
        workspace.getTables().put(orders.getId(), orders);
        TableWorkspaceNode view = TableWorkspaceNode.create(
                "column-standard", "app", "order_view", GraphActor.extractor);
        view.setTableType(TableType.view);
        workspace.getTables().put(view.getId(), view);
        store.save(workspace);
        Path rules = temp.resolve("column-standard.yaml");
        Files.writeString(rules, """
                kind: PolicyRuleSet
                id: column-standard
                title: Column standard
                version: "1"
                dbType: mysql
                rules:
                  - id: common-columns
                    title: Common columns
                    category: required_business_columns
                    severity: error
                    enforcement: required
                    when:
                      tableTypeAny: [base_table]
                    statement:
                      columns:
                        - name: creator
                          type: varchar
                          length: 64
                          defaultValue: ""
                          comment: 创建者
                        - name: update_time
                          type: datetime
                          nullable: false
                          defaultValue: CURRENT_TIMESTAMP
                          onUpdate: CURRENT_TIMESTAMP
                          comment: 更新时间
                """);
        PolicyService service = new PolicyService(store);

        PolicyCheckResult failed = service.check(
                "column-standard", rules, GraphActor.human);
        assertEquals(1, failed.exitCode());
        assertEquals(Set.of("dataType", "length", "nullable", "defaultValue", "comment", "onUpdate"),
                failed.violations().stream().map(PolicyViolation::getField).collect(Collectors.toSet()));
        assertTrue(failed.violations().stream().noneMatch(item -> item.getTargetId().equals(view.getId())));

        creator.getDataType().setLength(64);
        creator.setDefaultValue("''");
        creator.setComment("创建者");
        updateTime.getDataType().setNormalized("datetime");
        updateTime.setNullable(false);
        updateTime.setDefaultValue("current_timestamp()");
        updateTime.setComment("更新时间");
        updateTime.getAttributes().put("onUpdate", "current_timestamp()");
        store.save(workspace);

        PolicyCheckResult passed = service.check(
                "column-standard", rules, GraphActor.human);
        assertEquals(0, passed.exitCode(), passed.violations().toString());
        assertTrue(passed.violations().isEmpty());

        Files.writeString(rules, Files.readString(rules)
                .replace("comment: 创建者", "commment: 创建者"));
        assertThrows(IllegalArgumentException.class, () -> new RuleSetLoader().load(rules));
    }

    @Test
    void builtinRuleEvaluationIsStoredInTheAliasPolicyWorkspace() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = GraphWorkspace.create("builtin-audit", "mysql");
        workspace.getDataSource().setProductName("MySQL");
        workspace.getDataSource().setProductVersion("8.0.36");
        store.save(workspace);

        PolicyCheckResult result = new PolicyService(store).checkBuiltin(
                "builtin-audit", "dialect/mysql/8.0.yaml", GraphActor.agent,
                Map.of("sql", "SELECT * FROM orders FETCH FIRST 10 ROWS ONLY",
                        "inputRef", "query.sql:1"));

        assertEquals("mysql-8", result.evaluation().getRuleSetId());
        assertEquals("query.sql:1", result.violations().get(0).getSourceRef());
        // 评估落在运行库里，不再是 policy/runs/<id>/ruleset.yaml——当时用的规则集原文
        // 仍然完整保留，只是换了载体，所以这里从 loadRun 读回来验。
        PolicyRun stored = new PolicyStore(store).loadRun("builtin-audit", result.evaluation().getId());
        assertEquals("mysql-8", stored.ruleSet().getId());
        assertFalse(Files.exists(temp.resolve("builtin-audit/policy/runs")
                .resolve(result.evaluation().getId())), "不该再往文件系统写评估目录");
    }

    @Test
    void mysqlDialectRulesUseUnifiedDefaultsTopicsAndMatchingAudit() throws Exception {
        String rulesRef = "dialect/mysql/8.0.yaml";
        RuleSet rules = new RuleSetLoader().loadBuiltin(rulesRef);
        assertEquals("dialect-ruleset", rules.getKind());
        assertEquals(Set.of("pagination", "identifier", "schema", "datetime",
                        "json", "upsert", "ddl"),
                rules.getRules().stream()
                        .map(rule -> String.valueOf(rule.getStatement().get("topic")))
                        .collect(Collectors.toSet()));
        assertTrue(rules.getRules().stream().allMatch(rule ->
                rule.getSeverity() == PolicySeverity.info
                        && rule.getEnforcement() == PolicyEnforcement.advisory));

        GraphWorkspace mysql = GraphWorkspace.create("mysql-dialect", "mysql");
        mysql.getDataSource().setProductName("MySQL");
        mysql.getDataSource().setProductVersion("8.0.36");
        List<PolicyViolation> violations = new PolicyEvaluator().evaluate(
                mysql, rules, List.of(), "evaluation-mysql-dialect",
                Map.of(
                        "sql", "SELECT * FROM orders FETCH FIRST 10 ROWS ONLY",
                        "inputRef", "query.sql:1"));
        PolicyViolation violation = assertSingleViolation(
                new PolicyCheckResult(null, violations, 0, 0), "mysql-pagination");
        assertEquals("query.sql:1", violation.getSourceRef());

        GraphWorkspaceStore workspaceStore = new GraphWorkspaceStore(temp);
        GraphWorkspace postgres = GraphWorkspace.create("postgres-dialect", "postgresql");
        postgres.getDataSource().setProductName("PostgreSQL");
        postgres.getDataSource().setProductVersion("14.12");
        workspaceStore.save(postgres);
        PolicyCheckResult skipped = new PolicyService(workspaceStore).checkBuiltin(
                "postgres-dialect", rulesRef, GraphActor.human,
                Map.of("sql", "SELECT 1", "inputType", "sql"));
        assertEquals(PolicyEvaluationStatus.skipped, skipped.evaluation().getStatus());
        assertTrue(skipped.violations().isEmpty());
    }

    @Test
    void postgresOracleAndClickHouseDialectAssetsUseTheSameEvaluator() throws Exception {
        List<List<String>> cases = List.of(
                List.of("postgresql/14.yaml", "postgresql", "PostgreSQL", "14.12",
                        "SELECT TOP 5 * FROM orders", "postgresql-pagination"),
                List.of("oracle/19c.yaml", "oracle", "Oracle Database", "19.22.0",
                        "SELECT * FROM orders LIMIT 5", "oracle-pagination"),
                List.of("clickhouse/24.x.yaml", "clickhouse", "ClickHouse", "24.8.3",
                        "SELECT * FROM orders FETCH FIRST 5 ROWS ONLY", "clickhouse-pagination"));
        for (List<String> item : cases) {
            RuleSet rules = new RuleSetLoader().loadBuiltin("dialect/" + item.get(0));
            GraphWorkspace workspace = GraphWorkspace.create("dialect-" + item.get(1), item.get(1));
            workspace.getDataSource().setProductName(item.get(2));
            workspace.getDataSource().setProductVersion(item.get(3));

            List<PolicyViolation> violations = new PolicyEvaluator().evaluate(
                    workspace, rules, List.of(), "evaluation-" + item.get(1),
                    Map.of("sql", item.get(4)));

            assertEquals(Set.of("pagination", "identifier", "schema", "datetime",
                            "json", "upsert", "ddl"),
                    rules.getRules().stream()
                            .map(rule -> String.valueOf(rule.getStatement().get("topic")))
                            .collect(Collectors.toSet()));
            assertTrue(violations.stream().anyMatch(violation ->
                    item.get(5).equals(violation.getRuleId())));
        }
    }

    private void addTypedColumn(TableWorkspaceNode table, String name, String type, Integer length) {
        ColumnWorkspaceNode column = ColumnWorkspaceNode.create(name);
        column.getDataType().setNormalized(type);
        column.getDataType().setLength(length);
        table.getColumns().add(column);
    }

    private void addRelation(GraphWorkspace workspace, RelationType type,
            TableWorkspaceNode fromTable, String fromColumn,
            TableWorkspaceNode toTable, String toColumn, String sourceRef, boolean verified) {
        RelationWorkspaceEdge relation = RelationWorkspaceEdge.create(
                workspace.getManifest().getAlias(), type,
                GraphIds.columnId(workspace.getManifest().getAlias(), fromTable.getSchema(),
                        fromTable.getName(), fromColumn),
                GraphIds.columnId(workspace.getManifest().getAlias(), toTable.getSchema(),
                        toTable.getName(), toColumn),
                GraphActor.extractor);
        // 推断类关系没有默认置信度，测试固件需显式给出
        if (relation.getConfidence() == null) {
            relation.setConfidence(verified ? 0.95 : 0.8);
        }
        relation.setVerified(verified);
        relation.getEvidence().add(RelationEvidence.of("test", sourceRef));
        workspace.getRelations().add(relation);
    }

    private PolicyViolation assertSingleViolation(PolicyCheckResult result, String ruleId) {
        assertEquals(1, result.violations().size());
        assertEquals(ruleId, result.violations().get(0).getRuleId());
        return result.violations().get(0);
    }

    private GraphWorkspace workspace() {
        GraphWorkspace workspace = GraphWorkspace.create("policy", "mysql");
        workspace.getDataSource().setProductVersion("8.4.0");
        TableWorkspaceNode table = TableWorkspaceNode.create("policy", "app", "Orders", GraphActor.extractor);
        table.getTags().add("business");
        ColumnWorkspaceNode amount = ColumnWorkspaceNode.create("Amount");
        amount.getDataType().setNormalized("double");
        amount.getDataType().setRaw("DOUBLE");
        ColumnWorkspaceNode secret = ColumnWorkspaceNode.create("secret_phone");
        secret.getDataType().setNormalized("varchar");
        table.getColumns().addAll(List.of(amount, secret));
        TableIndexMetadata index = new TableIndexMetadata();
        index.setName("BAD INDEX");
        index.setColumns(List.of("Amount", "secret_phone"));
        index.setUnique(true);
        table.getIndexes().add(index);
        workspace.getTables().put(table.getId(), table);
        return workspace;
    }

    private String rulesYaml() {
        return """
                kind: PolicyRuleSet
                id: baseline
                title: Baseline
                version: "1"
                dbType: mysql
                rules:
                  - id: naming
                    title: Naming
                    category: naming_convention
                    severity: warning
                    enforcement: advisory
                    # 命名类默认 scope: change（只跑新 DDL），基线用例要的是全量行为
                    scope: all
                    statement:
                      tableNameRegex: "[a-z_]+"
                      columnNameRegex: "[a-z_]+"
                      indexNameRegex: "[a-z_]+"
                  - id: audit-columns
                    title: Audit columns
                    category: required_business_columns
                    severity: error
                    enforcement: required
                    when:
                      tableTagsAny: [business]
                    statement:
                      columns: [created_at, updated_at]
                  - id: money-decimal
                    title: Money decimal
                    category: money_type_decimal
                    severity: error
                    enforcement: blocking
                    when:
                      columnNameRegex: "(?i)amount|money|price"
                    statement:
                      allowedTypes: [decimal, numeric]
                  - id: primary-key
                    title: Primary key
                    category: primary_key_required
                    severity: error
                    enforcement: required
                    when:
                      tableTagsAny: [business]
                  - id: sensitive
                    title: Sensitive metadata
                    category: sensitive_column_policy
                    severity: error
                    enforcement: required
                    when:
                      columnNameRegex: "(?i)phone|secret"
                    statement:
                      policyAttribute: sensitivePolicy
                """;
    }
}
