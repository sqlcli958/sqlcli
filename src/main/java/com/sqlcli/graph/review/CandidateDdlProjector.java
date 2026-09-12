package com.sqlcli.graph.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sqlcli.graph.workspace.ColumnWorkspaceNode;
import com.sqlcli.graph.workspace.CommentGrainParser;
import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphIds;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.TableIndexMetadata;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import com.sqlcli.parser.SqlParseException;
import com.sqlcli.parser.SqlStatementAnalyzer;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterExpression;
import net.sf.jsqlparser.statement.alter.AlterOperation;
import net.sf.jsqlparser.statement.create.index.CreateIndex;
import net.sf.jsqlparser.statement.create.table.ColDataType;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.table.Index;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.update.Update;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CandidateDdlProjector {
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final SqlStatementAnalyzer analyzer = new SqlStatementAnalyzer();

    public ProjectionResult projectText(GraphWorkspace current, String sql, String sourceName) {
        return project(current, sql, sourceName, false, null);
    }

    /**
     * 执行链路用：agent 在 CLI 里写的 {@code CREATE TABLE foo (...)} 通常不带 schema 前缀，
     * 走的是连接自己的库。{@code defaultSchema} 就是那个库；设计评审仍然要求显式写出来。
     */
    public ProjectionResult projectText(GraphWorkspace current, String sql, String sourceName,
            String defaultSchema) {
        return project(current, sql, sourceName, false, defaultSchema);
    }

    public ProjectionResult projectFile(GraphWorkspace current, Path path) {
        try {
            return project(current, Files.readString(path, StandardCharsets.UTF_8), path.toString());
        } catch (Exception e) {
            return new ProjectionResult(copy(current), List.of(),
                    List.of(new ProjectionDiagnostic(path + ":1", "error", safeMessage(e))));
        }
    }

    /** Migration review also records DML statements for safety rules without applying them. */
    public ProjectionResult projectMigrationFile(GraphWorkspace current, Path path) {
        try {
            return projectMigration(current, Files.readString(path, StandardCharsets.UTF_8), path.toString());
        } catch (Exception e) {
            return new ProjectionResult(copy(current), List.of(),
                    List.of(new ProjectionDiagnostic(path + ":1", "error", safeMessage(e))));
        }
    }

    private ProjectionResult project(GraphWorkspace current, String sql, String sourceName) {
        return project(current, sql, sourceName, false, null);
    }

    private ProjectionResult projectMigration(GraphWorkspace current, String sql, String sourceName) {
        return project(current, sql, sourceName, true, null);
    }

    private ProjectionResult project(GraphWorkspace current, String sql, String sourceName,
            boolean allowDml, String defaultSchema) {
        GraphWorkspace candidate = copy(current);
        List<ProjectionChange> changes = new ArrayList<>();
        List<ProjectionDiagnostic> diagnostics = new ArrayList<>();
        for (SqlStatementAnalyzer.StatementSlice slice : analyzer.splitStatementsWithLines(sql)) {
            String sourceRef = sourceName + ":" + slice.line();
            try {
                Statement statement = analyzer.parseStatement(slice.sql());
                if (statement instanceof CreateTable createTable) {
                    projectCreate(candidate, createTable, sourceRef, changes, defaultSchema);
                } else if (statement instanceof Alter alter) {
                    projectAlter(candidate, alter, sourceRef, changes);
                } else if (statement instanceof CreateIndex createIndex) {
                    projectCreateIndex(candidate, createIndex, sourceRef, changes);
                } else if (statement instanceof Drop drop
                        && "TABLE".equalsIgnoreCase(drop.getType())) {
                    projectDropTable(candidate, drop, sourceRef, changes);
                } else if (allowDml && statement instanceof Update update) {
                    projectDml(candidate, update.getTable().getFullyQualifiedName(), "UPDATE", sourceRef, changes);
                } else if (allowDml && statement instanceof Delete delete) {
                    projectDml(candidate, delete.getTable().getFullyQualifiedName(), "DELETE", sourceRef, changes);
                } else {
                    diagnostics.add(new ProjectionDiagnostic(sourceRef, "error",
                            "unsupported DDL statement: " + statement.getClass().getSimpleName()));
                }
            } catch (Exception e) {
                diagnostics.add(new ProjectionDiagnostic(sourceRef, "error", safeMessage(e)));
            }
        }
        return new ProjectionResult(candidate, List.copyOf(changes), List.copyOf(diagnostics));
    }

    private void projectDml(GraphWorkspace workspace, String tableName, String changeType,
            String sourceRef, List<ProjectionChange> changes) {
        TableWorkspaceNode table = workspace.getTableByQualifiedName(tableName);
        changes.add(new ProjectionChange(changeType, "data",
                table == null ? tableName : table.getId(), null, null, sourceRef, true));
    }

    private void projectDropTable(GraphWorkspace workspace, Drop drop,
            String sourceRef, List<ProjectionChange> changes) {
        String qualifiedName = drop.getName().getFullyQualifiedName();
        TableWorkspaceNode table = workspace.getTableByQualifiedName(qualifiedName);
        if (table == null) throw new IllegalArgumentException("table not found: " + qualifiedName);
        TableWorkspaceNode before = copy(table, TableWorkspaceNode.class);
        workspace.getTables().remove(table.getId());
        String columnPrefix = "column:" + workspace.getManifest().getAlias() + ":"
                + table.getSchema() + "." + table.getName() + ".";
        workspace.getRelations().removeIf(relation -> relation.getFrom().startsWith(columnPrefix)
                || relation.getTo().startsWith(columnPrefix));
        changes.add(new ProjectionChange("DROP", "table", table.getId(),
                before, null, sourceRef, true));
    }

    private void projectCreate(GraphWorkspace workspace, CreateTable create,
            String sourceRef, List<ProjectionChange> changes, String defaultSchema) {
        String alias = workspace.getManifest().getAlias();
        String schema = unquote(create.getTable().getSchemaName());
        String name = unquote(create.getTable().getName());
        if (schema == null || schema.isBlank()) schema = defaultSchema;
        if (schema == null || schema.isBlank()) {
            throw new IllegalArgumentException("CREATE TABLE requires an explicit schema");
        }
        if (workspace.getTableByQualifiedName(schema + "." + name) != null) {
            throw new IllegalArgumentException("table already exists: " + schema + "." + name);
        }
        TableWorkspaceNode table = TableWorkspaceNode.create(alias, schema, name, GraphActor.agent);
        table.setCreatedAt(null);
        table.setUpdatedAt(null);
        if (create.getColumnDefinitions() != null) {
            for (ColumnDefinition definition : create.getColumnDefinitions()) {
                ColumnWorkspaceNode column = column(definition);
                table.getColumns().add(column);
                String specs = String.join(" ", definition.getColumnSpecs() == null
                        ? List.of() : definition.getColumnSpecs()).toUpperCase(Locale.ROOT);
                if (specs.contains("PRIMARY KEY")) addPrimaryKey(alias, table, column);
                if (specs.contains("UNIQUE")) column.setUnique(true);
            }
        }
        if (create.getIndexes() != null) {
            for (Index index : create.getIndexes()) addIndex(alias, table, index);
        }
        applyTableCommentGrain(table, create.getTableOptionsStrings());
        workspace.getTables().put(table.getId(), table);
        changes.add(new ProjectionChange("CREATE", "table", table.getId(),
                null, table, sourceRef, false));
    }

    private void projectAlter(GraphWorkspace workspace, Alter alter,
            String sourceRef, List<ProjectionChange> changes) {
        TableWorkspaceNode table = workspace.getTableByQualifiedName(
                alter.getTable().getFullyQualifiedName());
        if (table == null) throw new IllegalArgumentException(
                "table not found: " + alter.getTable().getFullyQualifiedName());
        for (AlterExpression expression : alter.getAlterExpressions()) {
            int count = changes.size();
            AlterOperation operation = expression.getOperation();
            if ((operation == AlterOperation.ADD || operation == AlterOperation.MODIFY
                    || operation == AlterOperation.ALTER || operation == AlterOperation.CHANGE)
                    && expression.getColDataTypeList() != null) {
                for (AlterExpression.ColumnDataType definition : expression.getColDataTypeList()) {
                    if (operation == AlterOperation.ADD) {
                        addColumn(workspace, table, definition, sourceRef, changes);
                    } else {
                        modifyColumn(workspace, table, definition, sourceRef, changes,
                                operation == AlterOperation.CHANGE ? "RENAME" : operation.name());
                    }
                }
            }
            if (operation == AlterOperation.ADD && expression.getIndex() != null) {
                addProjectedIndex(workspace, table, expression.getIndex(), sourceRef, "index", changes);
            }
            if (operation == AlterOperation.ADD && expression.getUkColumns() != null) {
                addUniqueConstraint(workspace, table, expression, sourceRef, changes);
            }
            if (operation == AlterOperation.DROP) {
                dropObject(workspace, table, expression, sourceRef, changes);
            }
            if (operation == AlterOperation.RENAME && expression.getColumnOldName() != null) {
                renameColumn(workspace, table, expression.getColumnOldName(),
                        expression.getColumnName(), sourceRef, changes);
            }
            if (changes.size() == count) {
                throw new IllegalArgumentException("unsupported ALTER operation: " + expression);
            }
        }
    }

    private void addColumn(GraphWorkspace workspace, TableWorkspaceNode table,
            ColumnDefinition definition, String sourceRef, List<ProjectionChange> changes) {
        ColumnWorkspaceNode column = column(definition);
        if (table.findColumn(column.getName()) != null) {
            throw new IllegalArgumentException("column already exists: " + column.getName());
        }
        table.getColumns().add(column);
        changes.add(new ProjectionChange("ADD", "column",
                column.computeId(workspace.getManifest().getAlias(), table.getSchema(), table.getName()),
                null, column, sourceRef, false));
    }

    private void modifyColumn(GraphWorkspace workspace, TableWorkspaceNode table,
            ColumnDefinition definition, String sourceRef, List<ProjectionChange> changes, String changeType) {
        String oldName = definition instanceof AlterExpression.ColumnDataType
                ? ((AlterExpression.ColumnDataType) definition).getColumnName() : definition.getColumnName();
        ColumnWorkspaceNode existing = table.findColumn(unquote(oldName));
        if (existing == null) throw new IllegalArgumentException("column not found: " + oldName);
        ColumnWorkspaceNode before = copy(existing, ColumnWorkspaceNode.class);
        ColumnWorkspaceNode replacement = column(definition);
        existing.setDataType(replacement.getDataType());
        existing.setNullable(replacement.isNullable());
        existing.setDefaultValue(replacement.getDefaultValue());
        existing.setComment(replacement.getComment());
        changes.add(new ProjectionChange(changeType, "column",
                existing.computeId(workspace.getManifest().getAlias(), table.getSchema(), table.getName()),
                before, copy(existing, ColumnWorkspaceNode.class), sourceRef,
                destructiveColumnChange(before, existing)));
    }

    private void renameColumn(GraphWorkspace workspace, TableWorkspaceNode table,
            String oldName, String newName, String sourceRef, List<ProjectionChange> changes) {
        ColumnWorkspaceNode column = table.findColumn(unquote(oldName));
        if (column == null || newName == null) throw new IllegalArgumentException(
                "ambiguous column rename: " + oldName + " -> " + newName);
        ColumnWorkspaceNode before = copy(column, ColumnWorkspaceNode.class);
        String old = column.getName();
        column.setName(unquote(newName));
        for (TableIndexMetadata index : table.getIndexes()) {
            index.setColumns(index.getColumns().stream()
                    .map(name -> name.equalsIgnoreCase(old) ? column.getName() : name).toList());
        }
        String oldId = GraphIds.columnId(workspace.getManifest().getAlias(),
                table.getSchema(), table.getName(), old);
        String newId = GraphIds.columnId(workspace.getManifest().getAlias(),
                table.getSchema(), table.getName(), column.getName());
        table.setPrimaryKey(table.getPrimaryKey().stream()
                .map(id -> id.equals(oldId) ? newId : id).toList());
        changes.add(new ProjectionChange("RENAME", "column", newId,
                before, copy(column, ColumnWorkspaceNode.class), sourceRef, false));
    }

    private void dropObject(GraphWorkspace workspace, TableWorkspaceNode table,
            AlterExpression expression, String sourceRef, List<ProjectionChange> changes) {
        String specifier = expression.getOptionalSpecifier() == null ? ""
                : expression.getOptionalSpecifier().toUpperCase(Locale.ROOT);
        if (specifier.contains("COLUMN") || expression.hasColumn()) {
            String name = unquote(expression.getColumnName());
            ColumnWorkspaceNode column = table.findColumn(name);
            if (column == null) throw new IllegalArgumentException("column not found: " + name);
            ColumnWorkspaceNode before = copy(column, ColumnWorkspaceNode.class);
            table.getColumns().remove(column);
            changes.add(new ProjectionChange("DROP", "column",
                    GraphIds.columnId(workspace.getManifest().getAlias(),
                            table.getSchema(), table.getName(), name),
                    before, null, sourceRef, true));
            return;
        }
        String name = expression.getConstraintName();
        String objectType = "constraint";
        if (name == null && expression.getIndex() != null) {
            name = expression.getIndex().getName();
            objectType = "index";
        }
        if (name == null) name = expression.getColumnName();
        TableIndexMetadata index = findIndex(table, unquote(name));
        if (index == null) throw new IllegalArgumentException("index/constraint not found: " + name);
        table.getIndexes().remove(index);
        changes.add(new ProjectionChange("DROP", objectType,
                table.getId() + ":" + objectType + ":" + name,
                copy(index, TableIndexMetadata.class), null, sourceRef, true));
    }

    private void addUniqueConstraint(GraphWorkspace workspace, TableWorkspaceNode table,
            AlterExpression expression, String sourceRef, List<ProjectionChange> changes) {
        TableIndexMetadata index = new TableIndexMetadata();
        String name = expression.getUkName() == null
                ? expression.getConstraintName() : expression.getUkName();
        index.setName(unquote(name));
        index.setUnique(true);
        index.setColumns(expression.getUkColumns().stream().map(this::unquote).toList());
        if (index.getName() == null) throw new IllegalArgumentException("unique constraint name is required");
        table.getIndexes().add(index);
        changes.add(new ProjectionChange("ADD", "constraint",
                table.getId() + ":constraint:" + index.getName(), null,
                copy(index, TableIndexMetadata.class), sourceRef, false));
    }

    private void projectCreateIndex(GraphWorkspace workspace, CreateIndex create,
            String sourceRef, List<ProjectionChange> changes) {
        TableWorkspaceNode table = workspace.getTableByQualifiedName(
                create.getTable().getFullyQualifiedName());
        if (table == null) throw new IllegalArgumentException(
                "table not found: " + create.getTable().getFullyQualifiedName());
        addProjectedIndex(workspace, table, create.getIndex(), sourceRef, "index", changes);
    }

    private void addProjectedIndex(GraphWorkspace workspace, TableWorkspaceNode table, Index source,
            String sourceRef, String objectType, List<ProjectionChange> changes) {
        String name = unquote(source.getName());
        if (name == null || findIndex(table, name) != null) {
            throw new IllegalArgumentException("ambiguous index: " + name);
        }
        addIndex(workspace.getManifest().getAlias(), table, source);
        TableIndexMetadata index = findIndex(table, name);
        changes.add(new ProjectionChange("ADD", objectType,
                table.getId() + ":" + objectType + ":" + name,
                null, copy(index, TableIndexMetadata.class), sourceRef, false));
    }

    private TableIndexMetadata findIndex(TableWorkspaceNode table, String name) {
        if (name == null) return null;
        return table.getIndexes().stream()
                .filter(index -> name.equalsIgnoreCase(index.getName())).findFirst().orElse(null);
    }

    private ColumnWorkspaceNode column(ColumnDefinition definition) {
        ColumnWorkspaceNode column = ColumnWorkspaceNode.create(unquote(definition.getColumnName()));
        ColDataType sourceType = definition.getColDataType();
        if (sourceType != null) {
            column.getDataType().setRaw(sourceType.toString());
            column.getDataType().setNormalized(sourceType.getDataType().toLowerCase(Locale.ROOT));
            List<String> arguments = sourceType.getArgumentsStringList();
            if (arguments != null) {
                if (arguments.size() == 1) {
                    column.getDataType().setLength(integer(arguments.get(0)));
                } else if (arguments.size() >= 2) {
                    column.getDataType().setPrecision(integer(arguments.get(0)));
                    column.getDataType().setScale(integer(arguments.get(1)));
                }
            }
        }
        String specs = String.join(" ", definition.getColumnSpecs() == null
                ? List.of() : definition.getColumnSpecs()).toUpperCase(Locale.ROOT);
        column.setNullable(!specs.contains("NOT NULL"));
        List<String> tokens = definition.getColumnSpecs() == null ? List.of() : definition.getColumnSpecs();
        for (int i = 0; i + 1 < tokens.size(); i++) {
            if ("DEFAULT".equalsIgnoreCase(tokens.get(i))) {
                column.setDefaultValue(tokens.get(i + 1));
                break;
            }
        }
        column.setComment(CommentGrainParser.parse(commentFromTokens(tokens)).comment());
        return column;
    }

    /**
     * 表级 COMMENT 接进 comment / grain 两个字段，跟导入侧
     * （{@code WorkspaceMetadataExtractor#applyCommentGrain}）共用同一份解析。
     */
    private void applyTableCommentGrain(TableWorkspaceNode table, List<String> tableOptions) {
        CommentGrainParser.Parsed parsed = CommentGrainParser.parse(commentFromTokens(tableOptions));
        table.setComment(parsed.comment());
        if (parsed.grain() != null) {
            table.setGrain(parsed.grain());
        }
    }

    /**
     * JSqlParser 把 {@code COMMENT '...'} 拆成 {@code ["COMMENT", "'...'"]} 两个 token，
     * 混在其余表/列选项（ENGINE、CHARSET、NOT NULL...）里，找到 COMMENT 后面那个值取出并去引号。
     */
    private String commentFromTokens(List<String> tokens) {
        if (tokens == null) return null;
        for (int i = 0; i + 1 < tokens.size(); i++) {
            if ("COMMENT".equalsIgnoreCase(tokens.get(i))) {
                return unquote(tokens.get(i + 1));
            }
        }
        return null;
    }

    private boolean destructiveColumnChange(ColumnWorkspaceNode before, ColumnWorkspaceNode after) {
        if (before.isNullable() && !after.isNullable()) return true;
        return smaller(before.getDataType().getLength(), after.getDataType().getLength())
                || smaller(before.getDataType().getPrecision(), after.getDataType().getPrecision());
    }

    private boolean smaller(Integer before, Integer after) {
        return before != null && after != null && after < before;
    }

    private void addIndex(String alias, TableWorkspaceNode table, Index source) {
        List<String> columns = source.getColumnsNames() == null ? List.of()
                : source.getColumnsNames().stream().map(this::unquote).toList();
        String type = source.getType() == null ? "" : source.getType().toUpperCase(Locale.ROOT);
        if (type.contains("PRIMARY")) {
            for (String name : columns) {
                ColumnWorkspaceNode column = table.findColumn(name);
                if (column != null) addPrimaryKey(alias, table, column);
            }
            return;
        }
        TableIndexMetadata index = new TableIndexMetadata();
        index.setName(unquote(source.getName()));
        index.setUnique(type.contains("UNIQUE"));
        index.setColumns(columns);
        table.getIndexes().add(index);
        for (String name : columns) {
            ColumnWorkspaceNode column = table.findColumn(name);
            if (column != null) column.setIndexed(true);
        }
        if (index.isUnique() && columns.size() == 1) {
            ColumnWorkspaceNode column = table.findColumn(columns.get(0));
            if (column != null) column.setUnique(true);
        }
    }

    private void addPrimaryKey(String alias, TableWorkspaceNode table, ColumnWorkspaceNode column) {
        column.setPrimaryKey(true);
        column.setIndexed(true);
        String id = GraphIds.columnId(alias, table.getSchema(), table.getName(), column.getName());
        if (!table.getPrimaryKey().contains(id)) table.getPrimaryKey().add(id);
    }

    private GraphWorkspace copy(GraphWorkspace workspace) {
        try {
            return mapper.readValue(mapper.writeValueAsBytes(workspace), GraphWorkspace.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to copy workspace", e);
        }
    }

    private <T> T copy(T value, Class<T> type) {
        try {
            return mapper.readValue(mapper.writeValueAsBytes(value), type);
        } catch (Exception e) {
            throw new IllegalStateException("failed to copy projected object", e);
        }
    }

    private Integer integer(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String unquote(String value) {
        if (value == null || value.length() < 2) return value;
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        return (first == last && (first == '`' || first == '"' || first == '\''))
                ? value.substring(1, value.length() - 1) : value;
    }

    private String safeMessage(Exception e) {
        if (e instanceof SqlParseException) return e.getMessage();
        return e.getMessage() == null ? "DDL projection failed" : e.getMessage();
    }

    public record ProjectionResult(
            GraphWorkspace workspace,
            List<ProjectionChange> changes,
            List<ProjectionDiagnostic> diagnostics) {
    }

    public record ProjectionChange(
            String changeType,
            String objectType,
            String targetId,
            Object before,
            Object after,
            String sourceRef,
            boolean destructive) {
    }

    public record ProjectionDiagnostic(String sourceRef, String severity, String message) {
    }
}
