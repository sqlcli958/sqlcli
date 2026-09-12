package com.sqlcli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AliasHelpTest {

    @Test
    void aliasHelpShowsTheCompleteLifecycle() {
        CliResult result = runCapturing("alias", "--help");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("add（创建） -> show（查看） -> update（修改） -> remove（删除）"));
        assertTrue(result.stdout().contains("Commands:"));
        assertTrue(result.stdout().contains("sql-cli alias add my-db"));
        assertTrue(result.stdout().contains("sql-cli alias update my-db"));
        assertTrue(result.stdout().contains("sql-cli alias remove my-db"));
        assertTrue(result.stdout().contains("alias <command> --help"));
    }

    @Test
    void addHelpDocumentsJdbcYearningAndSecretOptions() {
        CliResult result = runCapturing("alias", "add", "--help");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("--driver-ref=<name>"));
        assertTrue(result.stdout().contains("--jdbc-url=<url>"));
        assertTrue(result.stdout().contains("--secret-ref=<ref>"));
        assertTrue(result.stdout().contains("env:VAR、keyring:name、encrypted:name"));
        assertTrue(result.stdout().contains("--access-mode=<jdbc|yearning>"));
        assertTrue(result.stdout().contains("-i, --interactive"));
        assertTrue(result.stdout().contains("sql-cli alias add my-db -i"));
        assertTrue(result.stdout().contains("sql-cli alias add report-db"));
    }

    @Test
    void interactiveModeRejectsConfigurationOptionsWithoutAStackTrace() {
        CliResult result = runCapturing("alias", "add", "contract-test", "-i", "--host", "localhost");

        assertEquals(2, result.exitCode());
        assertTrue(result.stderr().contains("cannot be combined with alias configuration options"));
        assertTrue(!result.stderr().contains("at com.sqlcli"));
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
