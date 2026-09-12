package com.sqlcli.graph.policy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.sqlcli.graph.workspace.TableType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class RuleSetLoader {
    private static final Set<String> CONDITIONS = Set.of(
            "tableTagsAny", "tableNameRegex", "columnNameRegex", "semanticTypeAny",
            "columnTagsAny", "dbTypeAny", "tableTypeAny",
            // 值域闸门，query_convention 用：软删除的值全库并不统一（真库上见过注释写
            // 「2 代表删除」而库里实际出现 1 的），值域不支持就不该给提示——
            // 给错一条注入 WHERE 的条件是静默筛掉全部数据
            "columnValueIn");
    private static final Set<String> CATEGORIES = Set.of(
            "naming_convention", "required_business_columns", "money_type_decimal",
            "primary_key_required", "sensitive_column_policy", "controlled_vocabulary",
            "business_unique_index", "relation_column_index", "join_column_type_match",
            "status_field_dictionary", "dangerous_dml_guard", "migration_safety_check",
            "dialect_sql_pattern", "fact_table_grain_required",
            // 约定类：产出的不是违规，是注入 describe / search 的查询提示，消费者是 Agent。
            // 见 QueryConventions——这是 policy 的第二个消费者
            "query_convention");
    /**
     * 默认只在新 DDL 上生效的类别。改名的规则在存量库上是永远修不掉的噪音——
     * 判据与代价见 {@link PolicyScope}。规则里显式写 {@code scope: all} 就能打开。
     */
    private static final Set<String> CHANGE_SCOPED_CATEGORIES = Set.of("naming_convention");

    private static final Set<String> REQUIRED_COLUMN_FIELDS = Set.of(
            "name", "type", "length", "precision", "scale",
            "nullable", "defaultValue", "comment", "onUpdate");
    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public RuleSet load(Path path) throws IOException {
        RuleSet ruleSet = mapper.readValue(path.toFile(), RuleSet.class);
        validate(ruleSet);
        return ruleSet;
    }

    public RuleSet loadBuiltin(String ref) throws IOException {
        if (ref == null || !ref.matches("[A-Za-z0-9._/-]+") || ref.contains("..")) {
            throw new IllegalArgumentException("invalid builtin rule reference: " + ref);
        }
        try (InputStream input = RuleSetLoader.class.getResourceAsStream("/rules/" + ref)) {
            if (input == null) {
                throw new IOException("builtin policy rules not found: " + ref);
            }
            RuleSet ruleSet = mapper.readValue(input, RuleSet.class);
            validate(ruleSet);
            return ruleSet;
        }
    }

    /**
     * 工作区绑定的规则文件。缺失的 policy 目录、缺失的 bindings.yaml 以及空的
     * {@code ruleSets} 都表示「未绑定任何规则集」，返回空列表而不是报错——
     * 工作区初始化时写入的默认内容就是 {@code ruleSets: []}。
     */
    public List<Path> boundRulePaths(Path workspaceRoot) throws IOException {
        Path policyRoot = workspaceRoot.toAbsolutePath().normalize().resolve("policy");
        if (!Files.isDirectory(policyRoot)) {
            return List.of();
        }
        if (Files.isSymbolicLink(policyRoot)) {
            throw new IOException("symbolic links are not allowed: " + policyRoot);
        }
        Path realPolicyRoot = policyRoot.toRealPath();
        Path bindings = policyRoot.resolve("bindings.yaml");
        if (!Files.isRegularFile(bindings)) {
            return List.of();
        }
        if (Files.isSymbolicLink(bindings)) {
            throw new IOException("symbolic links are not allowed: " + bindings);
        }
        JsonNode root = mapper.readTree(bindings.toFile());
        if (root == null) {
            return List.of();
        }
        JsonNode configured = root.path("ruleSets");
        if (configured.isMissingNode() || configured.isNull()) {
            return List.of();
        }
        if (!configured.isArray()) {
            throw new IllegalArgumentException("ruleSets must be a list in " + bindings);
        }
        if (configured.isEmpty()) {
            return List.of();
        }
        List<Path> result = new ArrayList<>();
        for (JsonNode item : configured) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw new IllegalArgumentException("invalid policy rule binding: " + item);
            }
            Path ref = Path.of(item.asText());
            if (ref.isAbsolute()) {
                throw new IOException("bound rule path must be relative: " + ref);
            }
            if (ref.getNameCount() < 2 || !"rules".equals(ref.getName(0).toString())) {
                throw new IOException("bound rule path must be under policy/rules: " + ref);
            }
            Path resolved = policyRoot.resolve(ref).normalize();
            if (!resolved.startsWith(policyRoot)) {
                throw new IOException("bound rule path escapes policy root: " + ref);
            }
            if (!Files.isRegularFile(resolved) || Files.isSymbolicLink(resolved)) {
                throw new IOException("bound policy rules not found: " + ref);
            }
            Path realResolved = resolved.toRealPath();
            if (!realResolved.startsWith(realPolicyRoot)) {
                throw new IOException("bound rule path escapes policy root: " + ref);
            }
            result.add(realResolved);
        }
        return List.copyOf(result);
    }

    public void validate(RuleSet ruleSet) {
        require(ruleSet.getKind(), "kind");
        require(ruleSet.getId(), "id");
        require(ruleSet.getTitle(), "title");
        require(ruleSet.getVersion(), "version");
        if (ruleSet.getRules() == null || ruleSet.getRules().isEmpty()) {
            throw new IllegalArgumentException("rules is required");
        }
        for (String key : Set.of("productVersionRegex", "versionRegex")) {
            if (ruleSet.getMatch().containsKey(key)) {
                compile(String.valueOf(ruleSet.getMatch().get(key)), "match." + key);
            }
        }
        Set<String> ids = new HashSet<>();
        for (PolicyRule rule : ruleSet.getRules()) {
            applyDefaults(ruleSet, rule);
            require(rule.getId(), "rules[].id");
            require(rule.getTitle(), "rules[].title");
            require(rule.getCategory(), "rules[].category");
            if (!CATEGORIES.contains(rule.getCategory())) {
                throw new IllegalArgumentException("unknown rule category: " + rule.getCategory());
            }
            if (!ids.add(rule.getId())) {
                throw new IllegalArgumentException("duplicate rule id: " + rule.getId());
            }
            if (rule.getSeverity() == null) {
                throw new IllegalArgumentException("severity is required for rule " + rule.getId());
            }
            if (rule.getEnforcement() == null) {
                throw new IllegalArgumentException("enforcement is required for rule " + rule.getId());
            }
            for (Map.Entry<String, Object> condition : rule.getWhen().entrySet()) {
                if (!CONDITIONS.contains(condition.getKey())) {
                    throw new IllegalArgumentException("unknown condition: " + condition.getKey());
                }
                if (condition.getKey().endsWith("Regex")) {
                    compile(String.valueOf(condition.getValue()), condition.getKey());
                }
                if ("tableTypeAny".equals(condition.getKey())) {
                    for (String value : strings(condition.getValue())) {
                        try {
                            TableType.valueOf(value);
                        } catch (IllegalArgumentException e) {
                            throw new IllegalArgumentException("unknown table type: " + value);
                        }
                    }
                }
            }
            for (String key : Set.of("tableNameRegex", "columnNameRegex", "indexNameRegex", "matchRegex")) {
                if (rule.getStatement().containsKey(key)) {
                    compile(String.valueOf(rule.getStatement().get(key)), key);
                }
            }
            if ("required_business_columns".equals(rule.getCategory())) {
                validateRequiredColumns(rule);
            }
        }
    }

    private void validateRequiredColumns(PolicyRule rule) {
        Object configured = rule.getStatement().get("columns");
        if (!(configured instanceof java.util.Collection<?> columns) || columns.isEmpty()) {
            throw new IllegalArgumentException("columns statement is required for " + rule.getId());
        }
        for (Object item : columns) {
            if (item instanceof String name) {
                require(name, "required column name");
                continue;
            }
            if (!(item instanceof Map<?, ?> column)) {
                throw new IllegalArgumentException("invalid required column statement: " + item);
            }
            // 老文件里的 aliases 直接丢掉：必备字段名必须完全一致，没有「叫这个也行」。
            // 不报错是因为规则页列文件时会整目录加载，一个老文件坏掉整页都打不开。
            column.remove("aliases");
            for (Object key : column.keySet()) {
                if (!REQUIRED_COLUMN_FIELDS.contains(String.valueOf(key))) {
                    throw new IllegalArgumentException("unknown required column field: " + key);
                }
            }
            if (column.get("name") == null || String.valueOf(column.get("name")).isBlank()) {
                throw new IllegalArgumentException("name is required for " + rule.getId());
            }
            for (String field : List.of("length", "precision", "scale")) {
                Object value = column.get(field);
                if (value != null && (!(value instanceof Number number) || number.intValue() < 0)) {
                    throw new IllegalArgumentException(field + " must be a non-negative number");
                }
            }
            if (column.containsKey("nullable") && !(column.get("nullable") instanceof Boolean)) {
                throw new IllegalArgumentException("nullable must be true or false");
            }
            for (String field : List.of("type", "comment", "onUpdate")) {
                Object value = column.get(field);
                if (value != null && !(value instanceof String)) {
                    throw new IllegalArgumentException(field + " must be a string");
                }
            }
            Object defaultValue = column.get("defaultValue");
            if (defaultValue instanceof Map<?, ?> || defaultValue instanceof java.util.Collection<?>) {
                throw new IllegalArgumentException("defaultValue must be a scalar");
            }
        }
    }

    private void applyDefaults(RuleSet ruleSet, PolicyRule rule) {
        Object severity = ruleSet.getDefaults().get("severity");
        Object enforcement = ruleSet.getDefaults().get("enforcement");
        if (rule.getScope() == null) {
            Object scope = ruleSet.getDefaults().get("scope");
            rule.setScope(scope != null ? PolicyScope.valueOf(String.valueOf(scope))
                    : CHANGE_SCOPED_CATEGORIES.contains(String.valueOf(rule.getCategory()))
                            ? PolicyScope.change : PolicyScope.all);
        }
        if (rule.getSeverity() == null && (severity != null || "dialect-ruleset".equals(ruleSet.getKind()))) {
            rule.setSeverity(PolicySeverity.valueOf(String.valueOf(severity == null ? "info" : severity)));
        }
        if (rule.getEnforcement() == null
                && (enforcement != null || "dialect-ruleset".equals(ruleSet.getKind()))) {
            rule.setEnforcement(PolicyEnforcement.valueOf(
                    String.valueOf(enforcement == null ? "advisory" : enforcement)));
        }
    }

    public String hash(RuleSet ruleSet) {
        return PolicyHashes.object(ruleSet);
    }

    private void compile(String expression, String field) {
        try {
            Pattern.compile(expression);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("invalid regex in " + field + ": " + e.getMessage());
        }
    }

    private void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private List<String> strings(Object value) {
        if (value == null) return List.of();
        if (value instanceof java.util.Collection<?> values) {
            return values.stream().map(String::valueOf).toList();
        }
        return List.of(String.valueOf(value));
    }
}
