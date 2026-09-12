package com.sqlcli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaHelpTest {

    @Test
    void addRelationHelpExplainsOptionsEnumsAndExamples() {
        CliResult result = runCapturing("tour-prd", "schema", "add-relation", "--help");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("--confidence N"));
        assertTrue(result.stdout().contains("foreign_key"));
        assertTrue(result.stdout().contains("join_observed"));
        assertTrue(result.stdout().contains("join_observed"));
        assertTrue(result.stdout().contains("column -> column"));
        assertTrue(result.stdout().contains("不允许手工创建"));
        assertTrue(result.stdout().contains("sql-cli tour-prd schema add-relation"));
    }

    @Test
    void duplicateHelpActionIsRejected() {
        CliResult result = runCapturing("tour-prd", "schema", "help", "add-relation");

        assertEquals(2, result.exitCode());
        assertTrue(result.stderr().contains("Unknown schema action: help"));
        assertTrue(result.stderr().contains("schema <action> --help"));
    }

    @Test
    void importHelpDocumentsModesDefaultsAndTaskActions() {
        CliResult result = runCapturing("tour-prd", "schema", "import", "--help");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("--batch-size N"));
        assertTrue(result.stdout().contains("默认 20"));
        assertTrue(result.stdout().contains("status"));
        assertTrue(result.stdout().contains("resume"));
        assertTrue(result.stdout().contains("reset"));
    }

    @Test
    void globalSchemaHelpPointsToActionHelp() {
        CliResult result = runCapturing("tour-prd", "schema", "--help");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("schema <action> --help"));
        assertFalse(result.stdout().contains("schema help <action>"));
    }

    @Test
    void designAndMigrationHelpExposeTheirOnlyEntrypoints() {
        CliResult design = runCapturing("tour-prd", "schema", "design", "--help");
        CliResult migration = runCapturing("tour-prd", "schema", "migration", "--help");

        assertEquals(0, design.exitCode());
        assertTrue(design.stdout().contains("schema design review --ddl"));
        assertEquals(0, migration.exitCode());
        assertTrue(migration.stdout().contains("schema migration lint --file"));
    }

    @Test
    void unknownHelpTopicReturnsUsageError() {
        CliResult result = runCapturing("tour-prd", "schema", "not-exists", "--help");

        assertEquals(2, result.exitCode());
        assertTrue(result.stderr().contains("Unknown schema action: not-exists"));
    }

    @Test
    void positionalTableNamedLikeAnotherActionDoesNotReplaceTheAction() {
        CliResult result = runCapturing("tour-prd", "schema", "describe", "list", "--help");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("schema describe <schema.table>"));
        assertFalse(result.stdout().contains("schema list [--json]"));
    }

    private CliResult runCapturing(String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            System.setErr(err);
            int exitCode = SqlCli.run(args);
            return new CliResult(exitCode, stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private record CliResult(int exitCode, String stdout, String stderr) {
    }
}
