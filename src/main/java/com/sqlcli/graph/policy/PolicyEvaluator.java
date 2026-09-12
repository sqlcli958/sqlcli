package com.sqlcli.graph.policy;

import com.sqlcli.graph.workspace.ColumnWorkspaceNode;
import com.sqlcli.graph.workspace.ColumnDataType;
import com.sqlcli.graph.workspace.GraphIds;
import com.sqlcli.graph.workspace.GraphStatus;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.RelationType;
import com.sqlcli.graph.workspace.RelationWorkspaceEdge;
import com.sqlcli.graph.workspace.TableIndexMetadata;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import com.sqlcli.parser.ParsedSql;
import com.sqlcli.parser.SqlParseException;
import com.sqlcli.parser.SqlParser;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public class PolicyEvaluator {

    public List<PolicyViolation> evaluate(GraphWorkspace workspace, RuleSet ruleSet, List<RuleWaiver> waivers,
            String evaluationId) {
        return evaluate(workspace, ruleSet, waivers, evaluationId, Map.of());
    }

    public List<PolicyViolation> evaluate(GraphWorkspace workspace, RuleSet ruleSet, List<RuleWaiver> waivers,
            String evaluationId, Map<String, ?> input) {
        List<PolicyViolation> result = new ArrayList<>();
        if (!matchesRuleSet(workspace, ruleSet)) {
            return result;
        }
        Set<String> changedTables = changedTables(input);
        for (PolicyRule rule : ruleSet.getRules()) {
            if (!runs(rule, changedTables, input)) continue;
            int firstOfRule = result.size();
            switch (rule.getCategory()) {
                // 约定类规则不产出违规：它不是断言「这必须成立」，而是一条注入 describe /
                // search 的查询提示，消费者是 Agent（见 QueryConventions）。
                // 少了这一行，任何绑了约定规则的工作区跑 policy check 会撞上下面的 default 抛异常。
                case QueryConventions.CATEGORY -> { }
                case "naming_convention" -> naming(workspace, ruleSet, rule, evaluationId, result);
                case "required_business_columns" -> requiredColumns(workspace, ruleSet, rule, evaluationId, result);
                case "money_type_decimal" -> moneyType(workspace, ruleSet, rule, evaluationId, result);
                case "primary_key_required" -> primaryKey(workspace, ruleSet, rule, evaluationId, result);
                case "sensitive_column_policy" -> sensitive(workspace, ruleSet, rule, evaluationId, result);
                case "controlled_vocabulary" -> controlledVocabulary(
                        workspace, ruleSet, rule, evaluationId, result);
                case "business_unique_index" -> businessUniqueIndex(
                        workspace, ruleSet, rule, evaluationId, result);
                case "relation_column_index" -> relationColumnIndex(
                        workspace, ruleSet, rule, evaluationId, result);
                case "join_column_type_match" -> joinColumnTypeMatch(
                        workspace, ruleSet, rule, evaluationId, result);
                case "status_field_dictionary" -> statusFieldDictionary(
                        workspace, ruleSet, rule, evaluationId, result);
                case "dangerous_dml_guard" -> dangerousDml(
                        workspace, ruleSet, rule, evaluationId, input, result);
                case "migration_safety_check" -> migrationSafety(
                        workspace, ruleSet, rule, evaluationId, input, result);
                case "dialect_sql_pattern" -> dialectSqlPattern(
                        workspace, ruleSet, rule, evaluationId, input, result);
                case "fact_table_grain_required" -> factTableGrainRequired(
                        workspace, ruleSet, rule, evaluationId, result);
                default -> throw new IllegalArgumentException("unknown rule category: " + rule.getCategory());
            }
            if (changedTables != null) {
                result.subList(firstOfRule, result.size())
                        .removeIf(violation -> !inChangeScope(changedTables, violation.getTargetId()));
            }
        }
        applyReviewContext(result, input);
        LocalDateTime now = LocalDateTime.now();
        for (PolicyViolation violation : result) {
            waivers.stream()
                    .filter(waiver -> waiver.isActiveAt(now))
                    .filter(waiver -> Objects.equals(waiver.getRuleSetId(), ruleSet.getId()))
                    .filter(waiver -> Objects.equals(waiver.getRuleId(), violation.getRuleId()))
                    .filter(waiver -> Objects.equals(waiver.getTargetId(), violation.getTargetId()))
                    .findFirst()
                    .ifPresent(waiver -> {
                        violation.setStatus(PolicyViolationStatus.waived);
                        violation.setWaiverId(waiver.getId());
                    });
        }
        Comparator<PolicyViolation> order = nonBlank(input.get("reviewType"))
                ? Comparator.comparing(PolicyViolation::getSourceRef,
                        Comparator.nullsLast(String::compareTo))
                    .thenComparing(PolicyViolation::getRuleId)
                    .thenComparing(PolicyViolation::getTargetId)
                    .thenComparing(PolicyViolation::getFingerprint)
                : Comparator.comparing(PolicyViolation::getRuleId)
                    .thenComparing(PolicyViolation::getTargetId)
                    .thenComparing(PolicyViolation::getFingerprint);
        result.sort(order);
        return result;
    }

    /**
     * 这次真正会跑的规则数。收窄之后它和规则集条数不再相等，而 {@code evaluatedRules} 是
     * 机器读的契约字段——报「跑了 6 条」却只跑了 5 条，读的人会把「没有违规」当成「查过了」。
     */
    public int evaluatedRules(RuleSet ruleSet, Map<String, ?> input) {
        Set<String> changedTables = changedTables(input);
        return (int) ruleSet.getRules().stream().filter(rule -> runs(rule, changedTables, input)).count();
    }

    /**
     * 全量检查（没有变更集）里跳过 {@link PolicyScope#change} 的规则；
     * {@code input.scope=all}（CLI 的 {@code --all}）是一次性的显式打开。
     * design review / migration lint 有变更集，所有规则照跑。
     */
    private boolean runs(PolicyRule rule, Set<String> changedTables, Map<String, ?> input) {
        return changedTables != null || rule.getScope() != PolicyScope.change
                || "all".equals(input.get("scope"));
    }

    /**
     * design review / migration lint 送进来的变更对象各自所属的表。返回 {@code null} 表示
     * 这是一次全量检查，不按对象收窄。
     *
     * <p>没有这一层，design review 会把整个候选工作区的历史违规连同这次新写的 DDL 一起报出来——
     * 存量库上就是几百上千条，真正该看的那几条淹在里面。
     */
    private Set<String> changedTables(Map<String, ?> input) {
        if (!(input.get("changes") instanceof Collection<?> changes)) return null;
        Set<String> result = new java.util.LinkedHashSet<>();
        for (Object item : changes) {
            if (!(item instanceof Map<?, ?> change) || change.get("targetId") == null) continue;
            String table = tableKey(String.valueOf(change.get("targetId")));
            if (table != null) result.add(table);
        }
        return result;
    }

    private boolean inChangeScope(Set<String> changedTables, String targetId) {
        String table = tableKey(targetId);
        return table == null || changedTables.contains(table);
    }

    /**
     * 从对象 id 取出它所属的表（{@code alias:schema.table}）。关系 id、SQL 的来源引用、
     * 图谱里查不到的表名都返回 {@code null}——这类违规的判据来自送进来的 SQL 或变更集本身，
     * 不受「这次改了哪些表」收窄，否则 dangerous_dml_guard 会被自己的收窄机制吃掉。
     */
    private String tableKey(String id) {
        if (id == null) return null;
        String[] parts = id.split(":");
        if (parts.length < 3) return null;
        String qualified = parts[2];
        if ("column".equals(parts[0])) {
            int dot = qualified.lastIndexOf('.');
            if (dot < 0) return null;
            qualified = qualified.substring(0, dot);
        } else if (!"table".equals(parts[0])) {
            return null;
        }
        return parts[1] + ":" + qualified;
    }

    private void applyReviewContext(List<PolicyViolation> violations, Map<String, ?> input) {
        if (!(input.get("changes") instanceof Collection<?> changes)) return;
        for (PolicyViolation violation : violations) {
            if (violation.getSourceRef() != null) continue;
            Map<?, ?> context = null;
            for (Object item : changes) {
                if (!(item instanceof Map<?, ?> change)) continue;
                String targetId = change.get("targetId") == null ? null : String.valueOf(change.get("targetId"));
                if (Objects.equals(targetId, violation.getTargetId())
                        || isTableChild(targetId, violation.getTargetId())) {
                    context = change;
                    break;
                }
            }
            if (context == null) continue;
            if (context.get("sourceRef") != null) {
                violation.setSourceRef(String.valueOf(context.get("sourceRef")));
            }
            if (context.get("changeType") != null) {
                violation.setChangeType(String.valueOf(context.get("changeType")));
            }
        }
    }

    private boolean isTableChild(String changeTargetId, String violationTargetId) {
        if (changeTargetId == null || violationTargetId == null || !changeTargetId.startsWith("table:")) {
            return false;
        }
        return violationTargetId.startsWith("column:"
                + changeTargetId.substring("table:".length()) + ".");
    }

    public boolean matchesRuleSet(GraphWorkspace workspace, RuleSet ruleSet) {
        String dbType = workspace.getDataSource().getDbType();
        if (ruleSet.getDbType() != null && !ruleSet.getDbType().isBlank()
                && !ruleSet.getDbType().equalsIgnoreCase(dbType)) {
            return false;
        }
        Object dbTypes = ruleSet.getMatch().get("dbTypeAny");
        if (dbTypes != null && !strings(dbTypes).stream().anyMatch(value -> value.equalsIgnoreCase(dbType))) {
            return false;
        }
        Object versionRegex = ruleSet.getMatch().containsKey("productVersionRegex")
                ? ruleSet.getMatch().get("productVersionRegex") : ruleSet.getMatch().get("versionRegex");
        if (versionRegex != null && !Pattern.compile(String.valueOf(versionRegex))
                .matcher(workspace.getDataSource().getProductVersion()).find()) {
            return false;
        }
        Object names = ruleSet.getMatch().get("productNameIncludes");
        String productName = workspace.getDataSource().getProductName();
        return names == null || strings(names).stream().anyMatch(value ->
                (productName == null ? "" : productName).toLowerCase(Locale.ROOT)
                        .contains(value.toLowerCase(Locale.ROOT)));
    }

    private void naming(GraphWorkspace workspace, RuleSet ruleSet, PolicyRule rule, String evaluationId,
            List<PolicyViolation> out) {
        Pattern tablePattern = pattern(rule.getStatement(), "tableNameRegex");
        Pattern columnPattern = pattern(rule.getStatement(), "columnNameRegex");
        Pattern indexPattern = pattern(rule.getStatement(), "indexNameRegex");
        for (TableWorkspaceNode table : workspace.getTables().values()) {
            if (!matchesTable(workspace, rule, table)) continue;
            if (tablePattern != null && !tablePattern.matcher(table.getName()).matches()) {
                add(workspace, ruleSet, rule, evaluationId, table.getId(), "name",
                        "table name does not match " + tablePattern.pattern(), out);
            }
            for (ColumnWorkspaceNode column : table.getColumns()) {
                if (columnPattern != null && matchesColumn(workspace, rule, table, column)
                        && !columnPattern.matcher(column.getName()).matches()) {
                    add(workspace, ruleSet, rule, evaluationId, columnId(workspace, table, column), "name",
                            "column name does not match " + columnPattern.pattern(), out);
                }
            }
            for (TableIndexMetadata index : table.getIndexes()) {
                if (indexPattern != null && !indexPattern.matcher(index.getName()).matches()) {
                    add(workspace, ruleSet, rule, evaluationId, table.getId(), "indexes." + index.getName(),
                            "index name does not match " + indexPattern.pattern(), out);
                }
            }
        }
    }

    private void requiredColumns(GraphWorkspace workspace, RuleSet ruleSet, PolicyRule rule, String evaluationId,
            List<PolicyViolation> out) {
        Object configured = rule.getStatement().get("columns");
        if (!(configured instanceof Collection<?> required)) {
            throw new IllegalArgumentException("columns statement is required for " + rule.getId());
        }
        for (TableWorkspaceNode table : workspace.getTables().values()) {
            if (!matchesTable(workspace, rule, table)) continue;
            for (Object item : required) {
                List<String> accepted = acceptedColumnNames(item);
                ColumnWorkspaceNode column = table.getColumns().stream().filter(candidate -> accepted.stream()
                        .anyMatch(name -> name.equalsIgnoreCase(candidate.getName()))).findFirst().orElse(null);
                if (column == null) {
                    add(workspace, ruleSet, rule, evaluationId, table.getId(), accepted.get(0),
                            "required business column is missing: " + String.join("/", accepted), out);
                } else if (item instanceof Map<?, ?> expected) {
                    requiredColumnMetadata(workspace, ruleSet, rule, evaluationId, table, column, expected, out);
                }
            }
        }
    }

    private void requiredColumnMetadata(GraphWorkspace workspace, RuleSet ruleSet, PolicyRule rule,
            String evaluationId, TableWorkspaceNode table, ColumnWorkspaceNode column,
            Map<?, ?> expected, List<PolicyViolation> out) {
        ColumnDataType actualType = column.getDataType();
        if (expected.containsKey("type") && !sameText(expected.get("type"),
                actualType == null ? null : actualType.getNormalized())) {
            mismatch(workspace, ruleSet, rule, evaluationId, table, column,
                    "dataType", expected.get("type"), actualType == null ? null : actualType.getNormalized(), out);
        }
        for (String field : List.of("length", "precision", "scale")) {
            if (!expected.containsKey(field)) continue;
            Object actual = switch (field) {
                case "length" -> actualType == null ? null : actualType.getLength();
                case "precision" -> actualType == null ? null : actualType.getPrecision();
                default -> actualType == null ? null : actualType.getScale();
            };
            if (!Objects.equals(integer(expected.get(field)), actual)) {
                mismatch(workspace, ruleSet, rule, evaluationId, table, column,
                        field, expected.get(field), actual, out);
            }
        }
        if (expected.containsKey("nullable")
                && !Objects.equals(expected.get("nullable"), column.isNullable())) {
            mismatch(workspace, ruleSet, rule, evaluationId, table, column,
                    "nullable", expected.get("nullable"), column.isNullable(), out);
        }
        if (expected.containsKey("defaultValue")
                && !Objects.equals(normalizeSqlValue(expected.get("defaultValue")),
                        normalizeSqlValue(column.getDefaultValue()))) {
            mismatch(workspace, ruleSet, rule, evaluationId, table, column,
                    "defaultValue", expected.get("defaultValue"), column.getDefaultValue(), out);
        }
        if (expected.containsKey("comment") && !sameExactText(expected.get("comment"), column.getComment())) {
            mismatch(workspace, ruleSet, rule, evaluationId, table, column,
                    "comment", expected.get("comment"), column.getComment(), out);
        }
        Object actualOnUpdate = column.getAttributes().get("onUpdate");
        if (expected.containsKey("onUpdate")
                && !Objects.equals(normalizeSqlValue(expected.get("onUpdate")),
                        normalizeSqlValue(actualOnUpdate))) {
            mismatch(workspace, ruleSet, rule, evaluationId, table, column,
                    "onUpdate", expected.get("onUpdate"), actualOnUpdate, out);
        }
    }

    private void mismatch(GraphWorkspace workspace, RuleSet ruleSet, PolicyRule rule, String evaluationId,
            TableWorkspaceNode table, ColumnWorkspaceNode column, String field,
            Object expected, Object actual, List<PolicyViolation> out) {
        add(workspace, ruleSet, rule, evaluationId, columnId(workspace, table, column), field,
                column.getName() + " " + field + " must be " + display(expected)
                        + " but was " + display(actual), out);
    }

    private boolean sameText(Object expected, String actual) {
        return expected != null && actual != null
                && String.valueOf(expected).trim().equalsIgnoreCase(actual.trim());
    }

    private boolean sameExactText(Object expected, String actual) {
        return Objects.equals(expected == null ? null : String.valueOf(expected).trim(),
                actual == null ? null : actual.trim());
    }

    private Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : Integer.valueOf(String.valueOf(value));
    }

    private String normalizeSqlValue(Object value) {
        if (value == null) return null;
        String normalized = String.valueOf(value).trim();
        if (normalized.length() >= 2 && normalized.startsWith("'") && normalized.endsWith("'")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        normalized = normalized.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return "current_timestamp()".equals(normalized) ? "current_timestamp" : normalized;
    }

    private String display(Object value) {
        return value == null ? "<null>" : "'" + value + "'";
    }

    private void moneyType(GraphWorkspace workspace, RuleSet ruleSet, PolicyRule rule, String evaluationId,
            List<PolicyViolation> out) {
        Set<String> allowed = lowerSet(rule.getStatement().getOrDefault("allowedTypes",
                List.of("decimal", "numeric", "number")));
        for (TableWorkspaceNode table : workspace.getTables().values()) {
            if (!matchesTable(workspace, rule, table)) continue;
            for (ColumnWorkspaceNode column : table.getColumns()) {
                if (!matchesColumn(workspace, rule, table, column)) continue;
                String normalized = column.getDataType() == null ? null : column.getDataType().getNormalized();
                boolean valid = normalized != null && allowed.stream().anyMatch(normalized.toLowerCase(Locale.ROOT)::startsWith);
                if (!valid) {
                    add(workspace, ruleSet, rule, evaluationId, columnId(workspace, table, column), "dataType",
                            "money column must use one of: " + String.join(",", allowed), out);
                }
            }
        }
    }

    private void primaryKey(GraphWorkspace workspace, RuleSet ruleSet, PolicyRule rule, String evaluationId,
            List<PolicyViolation> out) {
        for (TableWorkspaceNode table : workspace.getTables().values()) {
            if (matchesTable(workspace, rule, table) && table.getPrimaryKey().isEmpty()) {
                add(workspace, ruleSet, rule, evaluationId, table.getId(), "primaryKey",
                        "primary key is required", out);
            }
        }
    }

    /**
     * 事实表（打了 {@code fact} 标签，见 {@code when.tableTagsAny}）必须声明 {@code grain}——
     * 「一行代表什么」是扇形陷阱检查（expand-metric 展开时判断 join 有没有放大行数）的输入，
     * 没有粒度声明就没法判断，所以设计时先在这里挡住。
     */
    private void factTableGrainRequired(GraphWorkspace workspace, RuleSet ruleSet, PolicyRule rule,
            String evaluationId, List<PolicyViolation> out) {
        for (TableWorkspaceNode table : workspace.getTables().values()) {
            if (matchesTable(workspace, rule, table) && !nonBlank(table.getGrain())) {
                add(workspace, ruleSet, rule, evaluationId, table.getId(), "grain",
                        "fact table requires a declared grain", out);
            }
        }
    }

    private void sensitive(GraphWorkspace workspace, RuleSet ruleSet, PolicyRule rule, String evaluationId,
            List<PolicyViolation> out) {
        String policyAttribute = String.valueOf(rule.getStatement().getOrDefault("policyAttribute", "sensitivePolicy"));
        for (TableWorkspaceNode table : workspace.getTables().values()) {
            if (!matchesTable(workspace, rule, table)) continue;
            for (ColumnWorkspaceNode column : table.getColumns()) {
                if (!matchesColumn(workspace, rule, table, column)) continue;
                boolean classified = column.getSemanticType() != null;
                boolean declared = column.getAttributes().get(policyAttribute) != null;
                if (!classified || !declared) {
                    add(workspace, ruleSet, rule, evaluationId, columnId(workspace, table, column), policyAttribute,
                            "sensitive column requires classification and policy declaration", out);
                }
            }
        }
    }

    private void controlledVocabulary(GraphWorkspace workspace, RuleSet ruleSet, PolicyRule rule,
            String evaluationId, List<PolicyViolation> out) {
        Object configured = rule.getStatement().get("concepts");
        if (!(configured instanceof Collection<?> concepts)) {
            throw new IllegalArgumentException("concepts statement is required for " + rule.getId());
        }
        for (Object item : concepts) {
            if (!(item instanceof Map<?, ?> concept) || concept.get("standard") == null) {
                throw new IllegalArgumentException("invalid controlled vocabulary statement: " + item);
            }
            String standard = String.valueOf(concept.get("standard"));
            Set<String> aliases = lowerSet(concept.get("aliases"));
            for (TableWorkspaceNode table : workspace.getTables().values()) {
                if (!matchesTable(workspace, rule, table)) continue;
                for (ColumnWorkspaceNode column : table.getColumns()) {
                    if (matchesColumn(workspace, rule, table, column)
                            && aliases.contains(column.getName().toLowerCase(Locale.ROOT))
                            && !standard.equalsIgnoreCase(column.getName())) {
                        add(workspace, ruleSet, rule, evaluationId,
                                columnId(workspace, table, column), "name",
                                "field name must use controlled vocabulary: " + standard, out);
                    }
                }
            }
        }
    }

    private void businessUniqueIndex(GraphWorkspace workspace, RuleSet ruleSet, PolicyRule rule,
            String evaluationId, List<PolicyViolation> out) {
        List<String> columns = strings(rule.getStatement().get("columns"));
        for (TableWorkspaceNode table : workspace.getTables().values()) {
            if (!matchesTable(workspace, rule, table)) continue;
            if (!columns.isEmpty()) {
                boolean found = hasExactUniqueIndex(table, columns);
                if (!found) {
                    add(workspace, ruleSet, rule, evaluationId, table.getId(), "indexes",
                            "business key requires a unique index: " + String.join(",", columns), out);
                }
                continue;
            }
            if (rule.getWhen().keySet().stream().noneMatch(
                    Set.of("columnNameRegex", "semanticTypeAny", "columnTagsAny")::contains)) {
                throw new IllegalArgumentException(
                        "columns or a column candidate condition is required for " + rule.getId());
            }
            for (ColumnWorkspaceNode column : table.getColumns()) {
                if (!matchesColumn(workspace, rule, table, column)) continue;
                if (!column.isUnique() && !hasExactUniqueIndex(table, List.of(column.getName()))) {
                    add(workspace, ruleSet, rule, evaluationId, columnId(workspace, table, column), "indexes",
                            "business key requires a single-column unique index: " + column.getName(), out);
                }
            }
        }
    }

    private boolean hasExactUniqueIndex(TableWorkspaceNode table, List<String> columns) {
        return table.getIndexes().stream()
                .filter(TableIndexMetadata::isUnique)
                .anyMatch(index -> sameColumns(index.getColumns(), columns));
    }

    private boolean sameColumns(List<String> actual, List<String> expected) {
        if (actual.size() != expected.size()) return false;
        for (int i = 0; i < actual.size(); i++) {
            if (!actual.get(i).equalsIgnoreCase(expected.get(i))) return false;
        }
        return true;
    }

    private void relationColumnIndex(GraphWorkspace workspace, RuleSet ruleSet, PolicyRule rule,
            String evaluationId, List<PolicyViolation> out) {
        Map<String, List<RelationWorkspaceEdge>> groups = new LinkedHashMap<>();
        for (RelationWorkspaceEdge relation : workspace.getRelations()) {
            if (!isJoinRelation(relation)) continue;
            TableWorkspaceNode fromTable = tableForColumn(workspace, relation.getFrom());
            TableWorkspaceNode toTable = tableForColumn(workspace, relation.getTo());
            if (fromTable == null || toTable == null || !matchesTable(workspace, rule, fromTable)) continue;
            String sourceRef = relation.getEvidence().isEmpty() ? relation.getId()
                    : relation.getEvidence().get(0).getSourceRef();
            String key = relation.getType() + "|" + sourceRef + "|" + fromTable.getId() + "|" + toTable.getId();
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(relation);
        }
        for (List<RelationWorkspaceEdge> group : groups.values()) {
            TableWorkspaceNode table = tableForColumn(workspace, group.get(0).getFrom());
            List<String> columns = group.stream()
                    .map(relation -> columnName(workspace, relation.getFrom()))
                    .filter(Objects::nonNull).toList();
            if (table == null || columns.isEmpty() || hasLeftPrefixIndex(table, columns)) continue;
            boolean unverifiedJoin = group.stream().anyMatch(relation ->
                    relation.getType() == RelationType.join_observed
                            && !Boolean.TRUE.equals(relation.getVerified()));
            add(workspace, ruleSet, rule, evaluationId, group.get(0).getFrom(), "indexes",
                    "relation source requires a left-prefix index: " + String.join(",", columns),
                    unverifiedJoin ? PolicyEnforcement.advisory : rule.getEnforcement(), out);
        }
    }

    private boolean hasLeftPrefixIndex(TableWorkspaceNode table, List<String> columns) {
        if (columns.size() == 1) {
            ColumnWorkspaceNode column = table.findColumn(columns.get(0));
            if (column != null && (column.isIndexed() || column.isPrimaryKey())) return true;
        }
        return table.getIndexes().stream().anyMatch(index -> {
            if (index.getColumns().size() < columns.size()) return false;
            return sameColumns(index.getColumns().subList(0, columns.size()), columns);
        });
    }

    private TableWorkspaceNode tableForColumn(GraphWorkspace workspace, String id) {
        for (TableWorkspaceNode table : workspace.getTables().values()) {
            for (ColumnWorkspaceNode column : table.getColumns()) {
                if (columnId(workspace, table, column).equals(id)) return table;
            }
        }
        return null;
    }

    private String columnName(GraphWorkspace workspace, String id) {
        TableWorkspaceNode table = tableForColumn(workspace, id);
        if (table == null) return null;
        return table.getColumns().stream()
                .filter(column -> columnId(workspace, table, column).equals(id))
                .map(ColumnWorkspaceNode::getName).findFirst().orElse(null);
    }

    private void joinColumnTypeMatch(GraphWorkspace workspace, RuleSet ruleSet, PolicyRule rule,
            String evaluationId, List<PolicyViolation> out) {
        for (RelationWorkspaceEdge relation : workspace.getRelations()) {
            if (!isJoinRelation(relation)) continue;
            ColumnWorkspaceNode left = workspace.findColumnById(relation.getFrom());
            ColumnWorkspaceNode right = workspace.findColumnById(relation.getTo());
            if (left == null || right == null || left.getDataType() == null || right.getDataType() == null) continue;
            String leftType = left.getDataType().getNormalized();
            String rightType = right.getDataType().getNormalized();
            String classification = joinTypeMismatch(left.getDataType(), right.getDataType(),
                    compatibleTypeGroups(rule));
            if (classification == null) continue;
            boolean unverifiedJoin = relation.getType() == RelationType.join_observed
                    && !Boolean.TRUE.equals(relation.getVerified());
            add(workspace, ruleSet, rule, evaluationId, relation.getId(), "dataType",
                    classification + ": join column types are " + leftType + " and " + rightType,
                    unverifiedJoin ? PolicyEnforcement.advisory : rule.getEnforcement(), out);
        }
    }

    /**
     * join 两端字段类型对不对得上：对得上返回 {@code null}，对不上返回
     * {@code "incompatible"}（类型不同且不在同一兼容组）或 {@code "suspicious"}
     * （类型相同但长度/精度/小数位不同）。
     *
     * <p>公开出来是给 {@code schema eval} 的 {@code rel.endpoint-mismatch} 探针复用的
     * （见 {@link com.sqlcli.graph.eval.GraphEvaluator}）。两处各写一遍的结果是同一条关系
     * 在规则页报错、在评估页放行——判据分叉之后没人说得清哪份对。
     */
    public static String joinTypeMismatch(ColumnDataType left, ColumnDataType right,
            Collection<Set<String>> compatibleGroups) {
        if (left == null || right == null) return null;
        String leftType = left.getNormalized();
        String rightType = right.getNormalized();
        if (leftType != null && leftType.equalsIgnoreCase(rightType)) {
            boolean sameParameters = Objects.equals(left.getLength(), right.getLength())
                    && Objects.equals(left.getPrecision(), right.getPrecision())
                    && Objects.equals(left.getScale(), right.getScale());
            return sameParameters ? null : "suspicious";
        }
        return inSameGroup(compatibleGroups, leftType, rightType) ? null : "incompatible";
    }

    private static boolean inSameGroup(Collection<Set<String>> groups, String left, String right) {
        if (groups == null || left == null || right == null) return false;
        for (Set<String> group : groups) {
            if (group.contains(left.toLowerCase(Locale.ROOT))
                    && group.contains(right.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private Collection<Set<String>> compatibleTypeGroups(PolicyRule rule) {
        Object configured = rule.getStatement().get("compatibleTypeGroups");
        if (!(configured instanceof Collection<?> groups)) return List.of();
        List<Set<String>> result = new ArrayList<>();
        for (Object item : groups) result.add(lowerSet(item));
        return result;
    }

    /**
     * 已忽略的关系不算 join 关系：规则评估是"拿关系去推导索引/类型要求"，人已经明确拒绝过的
     * 判断不能反过来对表提出整改要求。策略见
     * {@link com.sqlcli.graph.ui.service.WorkspaceMutationService#isIgnored(GraphWorkspace, String, RelationType, String, String)}
     * 的方法注释。
     */
    public static boolean isJoinRelation(RelationWorkspaceEdge relation) {
        if (relation.getStatus() == GraphStatus.ignored) return false;
        return relation.getType() == RelationType.foreign_key
                || relation.getType() == RelationType.join_observed
                || relation.getType() == RelationType.join_observed;
    }

    /**
     * 状态类字段必须说清楚取值是什么。三条路任选其一：
     * 内联值域（{@code valueHints.enumValues}）、指向字典表的关系边、或一段文字说明。
     *
     * <p>字典表这条路走的是<b>关系</b>而不是属性：两端都是图谱里的对象，
     * 存成字符串等于把一条边降级成不受校验的文本，也装不下 {@code dict_type} 这类过滤条件——
     * 而没有过滤条件，顺着它去查字典表会捞出全表所有业务的字典项。
     * 字典表怎么命名各家不同，所以匹配模式由规则给（{@code dictionaryTablePattern}），
     * 模型里不写死。
     */
    private void statusFieldDictionary(GraphWorkspace workspace, RuleSet ruleSet, PolicyRule rule,
            String evaluationId, List<PolicyViolation> out) {
        Pattern dictionaryTable = likeToRegex(String.valueOf(
                rule.getStatement().getOrDefault("dictionaryTablePattern", "%dict%")));
        String descriptionAttribute = String.valueOf(
                rule.getStatement().getOrDefault("descriptionAttribute", "dictionaryDescription"));
        for (TableWorkspaceNode table : workspace.getTables().values()) {
            if (!matchesTable(workspace, rule, table)) continue;
            for (ColumnWorkspaceNode column : table.getColumns()) {
                if (!matchesColumn(workspace, rule, table, column)) continue;
                String id = columnId(workspace, table, column);
                boolean hasEnum = column.getValueHints() != null
                        && column.getValueHints().getEnumValues() != null
                        && !column.getValueHints().getEnumValues().isEmpty();
                boolean hasDictionary = hasDictionaryRelation(workspace, id, dictionaryTable);
                boolean hasDescription = nonBlank(column.getAttributes().get(descriptionAttribute));
                if (!hasEnum && !hasDictionary && !hasDescription) {
                    add(workspace, ruleSet, rule, evaluationId, id, "dictionary",
                            "status field requires an enum, a relation to a dictionary table, or a description", out);
                }
            }
        }
    }

    /**
     * 该字段有没有一条出边指向名字像字典表的表。
     *
     * <p>跳过已忽略的边：人拒绝掉的"这是字典关系"判断，不能反过来证明字段已经说清楚了取值——
     * 那样规则就被架空了，字段还是那个没人说清楚含义的字段。
     */
    private boolean hasDictionaryRelation(GraphWorkspace workspace, String columnId, Pattern dictionaryTable) {
        for (RelationWorkspaceEdge relation : workspace.getRelations()) {
            if (relation.getStatus() == GraphStatus.ignored) continue;
            if (!columnId.equals(relation.getFrom()) || relation.getTo() == null) continue;
            // 列 id 形如 column:alias:schema.table.column，倒数第二段就是表名
            String[] parts = relation.getTo().split("\\.");
            if (parts.length >= 2 && dictionaryTable.matcher(parts[parts.length - 2]).matches()) {
                return true;
            }
        }
        return false;
    }

    /** 把 SQL LIKE 风格的 {@code %dict%} 转成正则；规则里写 LIKE 比让人写正则友好。 */
    private static Pattern likeToRegex(String like) {
        StringBuilder regex = new StringBuilder();
        for (char c : like.toCharArray()) {
            switch (c) {
                case '%' -> regex.append(".*");
                case '_' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }

    private boolean nonBlank(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    private void dangerousDml(GraphWorkspace workspace, RuleSet ruleSet, PolicyRule rule,
            String evaluationId, Map<String, ?> input, List<PolicyViolation> out) {
        List<String> sqls = input.containsKey("sqlStatements")
                ? strings(input.get("sqlStatements"))
                : input.get("sql") == null ? List.of() : List.of(String.valueOf(input.get("sql")));
        if (sqls.isEmpty()) {
            if (input.containsKey("sqlStatements")) return;
            throw new IllegalArgumentException("sql input is required for " + rule.getId());
        }
        List<String> sourceRefs = strings(input.get("sqlSourceRefs"));
        for (int sqlIndex = 0; sqlIndex < sqls.size(); sqlIndex++) {
            String sql = sqls.get(sqlIndex);
            int firstViolation = out.size();
            SqlParser parser = new SqlParser();
            ParsedSql parsed;
            try {
                parsed = parser.parse(sql);
            } catch (SqlParseException e) {
                throw new IllegalArgumentException("invalid dangerous DML input: " + e.getMessage());
            }
            TableWorkspaceNode table = workspace.getTableByQualifiedName(parsed.getTableName());
            String targetId = table == null ? parsed.getTableName() : table.getId();
            try {
                parser.requireWhereClause(sql);
            } catch (SqlParseException e) {
                add(workspace, ruleSet, rule, evaluationId, targetId, "whereClause",
                        "UPDATE/DELETE requires a WHERE clause", out);
            }
            if (table == null || table.getPrimaryKey().isEmpty()) {
                add(workspace, ruleSet, rule, evaluationId, targetId, "primaryKey",
                        "UPDATE/DELETE recovery requires primary key facts", out);
            }
            if (!nonBlank(input.get("verificationSql"))) {
                add(workspace, ruleSet, rule, evaluationId, targetId, "verificationSql",
                        "UPDATE/DELETE review requires verification SQL", out);
            }
            if (sqlIndex < sourceRefs.size()) {
                for (int i = firstViolation; i < out.size(); i++) {
                    out.get(i).setSourceRef(sourceRefs.get(sqlIndex));
                    out.get(i).setChangeType(parsed.getSqlType());
                }
            }
        }
    }

    private void migrationSafety(GraphWorkspace workspace, RuleSet ruleSet, PolicyRule rule,
            String evaluationId, Map<String, ?> input, List<PolicyViolation> out) {
        Object configured = input.get("changes");
        if (!(configured instanceof Collection<?> changes) || changes.isEmpty()) {
            throw new IllegalArgumentException("changes input is required for " + rule.getId());
        }
        Map<?, ?> firstRisk = null;
        Map<?, ?> firstDestructive = null;
        for (Object item : changes) {
            if (!(item instanceof Map<?, ?> change) || change.get("changeType") == null) {
                throw new IllegalArgumentException("invalid migration change: " + item);
            }
            String declaredType = String.valueOf(change.get("changeType")).toUpperCase(Locale.ROOT);
            if ((Boolean.TRUE.equals(change.get("destructive")) || declaredType.startsWith("DROP"))
                    && firstDestructive == null) {
                firstDestructive = change;
            }
            for (MigrationRisk risk : migrationRisks(change)) {
                if (firstRisk == null) firstRisk = change;
                migrationViolation(workspace, ruleSet, rule, evaluationId, change,
                        risk.field(), risk.message(), risk.changeType(), out);
                if (!"BACKFILL".equals(risk.changeType())) continue;
                for (String field : List.of("batchStrategy", "checkpointStrategy", "idempotencyStrategy")) {
                    if (!nonBlank(change.get(field)) && !nonBlank(input.get(field))) {
                        migrationViolation(workspace, ruleSet, rule, evaluationId, change, field,
                                "backfill requires " + field, "BACKFILL", out);
                    }
                }
            }
        }
        if (firstDestructive != null && !nonBlank(input.get("rollbackSql"))) {
            migrationViolation(workspace, ruleSet, rule, evaluationId, firstDestructive,
                    "rollbackSql", "destructive migration requires rollbackSql",
                    String.valueOf(firstDestructive.get("changeType")), out);
        }
        if (firstRisk != null && (rule.getEnforcement() == PolicyEnforcement.required
                || rule.getEnforcement() == PolicyEnforcement.blocking)) {
            for (String field : List.of("precheckSql", "postcheckSql")) {
                if (!nonBlank(input.get(field))) {
                    migrationViolation(workspace, ruleSet, rule, evaluationId, firstRisk, field,
                            "migration requires " + field,
                            String.valueOf(firstRisk.get("changeType")), out);
                }
            }
        }
    }

    private List<MigrationRisk> migrationRisks(Map<?, ?> change) {
        String changeType = String.valueOf(change.get("changeType")).toUpperCase(Locale.ROOT);
        String objectType = String.valueOf(change.get("objectType")).toUpperCase(Locale.ROOT);
        List<MigrationRisk> risks = new ArrayList<>();
        if (changeType.startsWith("DROP")) {
            String riskType = "DROP".equals(changeType) ? "DROP_" + objectType : changeType;
            risks.add(new MigrationRisk(riskType, "changeType",
                    "migration drops " + objectType.toLowerCase(Locale.ROOT)));
        } else if (changeType.startsWith("RENAME")) {
            String riskType = "RENAME".equals(changeType) ? "RENAME_" + objectType : changeType;
            risks.add(new MigrationRisk(riskType, "changeType",
                    "migration renames " + objectType.toLowerCase(Locale.ROOT)));
        } else if (("ADD".equals(changeType)
                && ("INDEX".equals(objectType) || "CONSTRAINT".equals(objectType)))
                || "ADD_INDEX".equals(changeType) || "ADD_CONSTRAINT".equals(changeType)) {
            String riskType = "ADD".equals(changeType) ? "ADD_" + objectType : changeType;
            risks.add(new MigrationRisk(riskType, "changeType",
                    "migration adds " + objectType.toLowerCase(Locale.ROOT)));
        } else if ("UPDATE".equals(changeType) || "BACKFILL".equals(changeType)) {
            risks.add(new MigrationRisk("BACKFILL", "changeType", "migration contains data backfill"));
        }
        if (change.get("before") instanceof ColumnWorkspaceNode before
                && change.get("after") instanceof ColumnWorkspaceNode after) {
            if (!Objects.equals(before.getDataType(), after.getDataType())) {
                risks.add(new MigrationRisk("ALTER_TYPE", "dataType",
                        "migration changes column type"));
            }
            if (before.isNullable() && !after.isNullable()) {
                risks.add(new MigrationRisk("SET_NOT_NULL", "nullable",
                        "migration changes nullable column to NOT NULL"));
            }
        } else if ("ADD".equals(changeType) && change.get("after") instanceof ColumnWorkspaceNode after
                && !after.isNullable() && !nonBlank(after.getDefaultValue())) {
            risks.add(new MigrationRisk("SET_NOT_NULL", "defaultValue",
                    "NOT NULL column requires a default value or backfill plan"));
        }
        return risks;
    }

    private void migrationViolation(GraphWorkspace workspace, RuleSet ruleSet, PolicyRule rule,
            String evaluationId, Map<?, ?> change, String field, String message,
            List<PolicyViolation> out) {
        migrationViolation(workspace, ruleSet, rule, evaluationId, change, field, message,
                String.valueOf(change.get("changeType")), out);
    }

    private void migrationViolation(GraphWorkspace workspace, RuleSet ruleSet, PolicyRule rule,
            String evaluationId, Map<?, ?> change, String field, String message,
            String changeType, List<PolicyViolation> out) {
        String targetId = change.get("targetId") == null
                ? workspace.getManifest().getAlias() : String.valueOf(change.get("targetId"));
        add(workspace, ruleSet, rule, evaluationId, targetId, field, message, out);
        PolicyViolation violation = out.get(out.size() - 1);
        violation.setSourceRef(change.get("sourceRef") == null ? null : String.valueOf(change.get("sourceRef")));
        violation.setChangeType(changeType);
    }

    private record MigrationRisk(String changeType, String field, String message) {
    }

    private void dialectSqlPattern(GraphWorkspace workspace, RuleSet ruleSet, PolicyRule rule,
            String evaluationId, Map<String, ?> input, List<PolicyViolation> out) {
        String sql = input.get("sql") == null ? null : String.valueOf(input.get("sql"));
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("sql input is required for " + rule.getId());
        }
        Pattern pattern = pattern(rule.getStatement(), "matchRegex");
        if (pattern == null || !pattern.matcher(sql).find()) return;
        String sourceRef = input.get("inputRef") == null ? null : String.valueOf(input.get("inputRef"));
        String targetId = sourceRef == null ? workspace.getManifest().getAlias() : sourceRef;
        String topic = String.valueOf(rule.getStatement().getOrDefault("topic", "dialect"));
        String message = String.valueOf(rule.getStatement().getOrDefault(
                "message", "SQL uses syntax from a different dialect"));
        add(workspace, ruleSet, rule, evaluationId, targetId, topic, message, out);
        out.get(out.size() - 1).setSourceRef(sourceRef);
    }

    private boolean matchesTable(GraphWorkspace workspace, PolicyRule rule, TableWorkspaceNode table) {
        Map<String, Object> when = rule.getWhen();
        if (when.containsKey("dbTypeAny") && strings(when.get("dbTypeAny")).stream()
                .noneMatch(value -> value.equalsIgnoreCase(workspace.getDataSource().getDbType()))) return false;
        if (when.containsKey("tableTagsAny") && strings(when.get("tableTagsAny")).stream()
                .noneMatch(tag -> table.getTags().stream().anyMatch(tag::equalsIgnoreCase))) return false;
        if (when.containsKey("tableTypeAny") && strings(when.get("tableTypeAny")).stream()
                .noneMatch(type -> type.equals(table.getTableType().name()))) return false;
        return !when.containsKey("tableNameRegex") || Pattern.compile(String.valueOf(when.get("tableNameRegex")))
                .matcher(table.getName()).find();
    }

    private boolean matchesColumn(GraphWorkspace workspace, PolicyRule rule, TableWorkspaceNode table,
            ColumnWorkspaceNode column) {
        if (!matchesTable(workspace, rule, table)) return false;
        Map<String, Object> when = rule.getWhen();
        if (when.containsKey("columnNameRegex") && !Pattern.compile(String.valueOf(when.get("columnNameRegex")))
                .matcher(column.getName()).find()) return false;
        if (when.containsKey("columnTagsAny") && strings(when.get("columnTagsAny")).stream()
                .noneMatch(tag -> strings(column.getAttributes().get("tags")).stream()
                        .anyMatch(value -> value.equalsIgnoreCase(tag)))) return false;
        return !when.containsKey("semanticTypeAny") || strings(when.get("semanticTypeAny")).stream()
                .anyMatch(value -> column.getSemanticType() != null
                        && value.equalsIgnoreCase(column.getSemanticType().name()));
    }

    private void add(GraphWorkspace workspace, RuleSet ruleSet, PolicyRule rule, String evaluationId,
            String targetId, String field, String message, List<PolicyViolation> out) {
        add(workspace, ruleSet, rule, evaluationId, targetId, field, message, rule.getEnforcement(), out);
    }

    private void add(GraphWorkspace workspace, RuleSet ruleSet, PolicyRule rule, String evaluationId,
            String targetId, String field, String message, PolicyEnforcement enforcement,
            List<PolicyViolation> out) {
        PolicyViolation violation = new PolicyViolation();
        violation.setId("violation-" + UUID.randomUUID());
        violation.setEvaluationId(evaluationId);
        violation.setFingerprint(PolicyHashes.fingerprint(workspace.getManifest().getAlias(), ruleSet.getId(),
                rule.getId(), targetId, field));
        violation.setRuleId(rule.getId());
        violation.setTargetId(targetId);
        violation.setField(field);
        violation.setSeverity(rule.getSeverity());
        violation.setEnforcement(enforcement);
        violation.setMessage(message);
        violation.setRemediation(rule.getRemediation());
        violation.setObservedAt(LocalDateTime.now());
        out.add(violation);
    }

    private String columnId(GraphWorkspace workspace, TableWorkspaceNode table, ColumnWorkspaceNode column) {
        return GraphIds.columnId(workspace.getManifest().getAlias(), table.getSchema(), table.getName(), column.getName());
    }

    private Pattern pattern(Map<String, Object> map, String key) {
        return map.containsKey(key) ? Pattern.compile(String.valueOf(map.get(key))) : null;
    }

    /**
     * 必备字段只认 {@code name}，**没有别名**：规则说的是「新表必须叫 created_at」，
     * 不是「叫 created_at 或 create_time 都行」——后者等于把不统一写进规则里（2026-09-09 拍板）。
     */
    private List<String> acceptedColumnNames(Object item) {
        if (item instanceof String name) return List.of(name);
        if (item instanceof Map<?, ?> map && map.get("name") != null) {
            return List.of(String.valueOf(map.get("name")));
        }
        throw new IllegalArgumentException("invalid required column statement: " + item);
    }

    private List<String> strings(Object value) {
        if (value == null) return List.of();
        if (value instanceof Collection<?> values) return values.stream().map(String::valueOf).toList();
        return List.of(String.valueOf(value));
    }

    private Set<String> lowerSet(Object value) {
        return strings(value).stream().map(item -> item.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}
