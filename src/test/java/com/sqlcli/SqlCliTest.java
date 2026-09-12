package com.sqlcli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlCliTest {

    @Test
    void noArgumentsShowsHelpSuccessfully() {
        assertEquals(0, SqlCli.run());
    }

    @Test
    void shortVersionIsHandledAsGlobalOption() {
        assertEquals(0, SqlCli.run("-V"));
    }

    @Test
    void missingOptionValueReturnsUsageError() {
        assertEquals(2, SqlCli.run("missing-alias", "--format"));
        assertEquals(2, SqlCli.run("missing-alias", "--file"));
    }

    @Test
    void unknownQueryOptionReturnsUsageError() {
        assertEquals(2, SqlCli.run("missing-alias", "--unknown"));
    }

    @Test
    void invalidOutputFormatReturnsUsageErrorBeforeConnecting() {
        assertEquals(2, SqlCli.run("missing-alias", "SELECT 1", "--format", "xml"));
        assertEquals(2, SqlCli.run("missing-alias", "tables", "--format", "xml"));
    }

    @Test
    void multipleStatementsAgainstAnUnresolvableAliasFailsWithExitCodeOne() {
        // 多语句检测现在统一由 SqlTaskModule 的 GuardStage 判断（单一入口，不再有
        // SqlCli 自己的重复前置检查），所以要先能解析出一个 DatabaseConfig 才轮得到
        // GuardStage 说话——这个假别名会先在 alias 解析那步就失败，两种拒绝理由
        // 都合法、都是退出码 1，这里只断言退出码。"Multiple statements" 具体文案的
        // 覆盖在 SqlTaskModuleTest.multipleStatementsAreRejectedBeforeConnecting。
        assertEquals(1, SqlCli.run("__missing_multi_alias__",
                "SELECT 1; DELETE FROM customers WHERE id = 1"));
    }

    @Test
    void duplicateUiShortcutIsRejected() {
        assertEquals(2, SqlCli.run("__missing_ui_test_alias__", "ui", "--no-open"));
    }

    @Test
    void graphUiHasOneRootCommandEntry() {
        assertEquals(0, SqlCli.run("ui", "--help"));
        assertEquals(2, SqlCli.run("__missing_ui_test_alias__", "schema", "ui", "--no-open"));
    }

    @Test
    void duplicateShowActionIsRejected() {
        assertEquals(2, SqlCli.run("__missing_show_test_alias__", "show", "--help"));
    }

    @Test
    void duplicateAndLegacyTopLevelEntrypointsAreRejected() {
        assertEquals(2, SqlCli.run("help"));
        assertEquals(2, SqlCli.run("schema"));
        assertEquals(2, SqlCli.run("alias", "list"));
    }

    @Test
    void driverUsageAndRuntimeFailuresHaveDifferentExitCodes() {
        assertEquals(2, SqlCli.run("driver", "unknown-action"));
        assertEquals(2, SqlCli.run("driver", "show"));
        assertEquals(1, SqlCli.run("driver", "show", "__missing_contract_driver__"));
    }

    @Test
    void cryptoModeErrorsReturnUsageExitCode() {
        assertEquals(2, SqlCli.run(
                "crypto", "sm4", "--text", "plain", "--key", "0123456789ABCDEF"));
    }

    @Test
    void infoActionIsRoutedBeforeQueryParsing() {
        assertEquals(0, SqlCli.run("__missing_info_test_alias__", "info", "--help"));
    }

    @Test
    void aliasActionsReturnStableFailureAndUsageExitCodes() {
        String missing = "__missing_action_contract_alias__";
        assertEquals(1, SqlCli.run(missing, "test", "--json"));
        assertEquals(1, SqlCli.run(missing, "ddl", "orders"));
        assertEquals(1, SqlCli.run(missing, "tables"));
        assertEquals(1, SqlCli.run(missing, "secret", "status", "--json"));
        assertEquals(2, SqlCli.run(missing, "ddl"));
        assertEquals(2, SqlCli.run(missing, "secret", "unknown"));
    }

    @Test
    void infoFailureReturnsNonZeroAndValidJsonForControlCharacters() throws Exception {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            assertEquals(1, SqlCli.run("__missing\ninfo_alias__", "info", "--json"));
        } finally {
            System.setOut(originalOut);
        }
        JsonNode output = new ObjectMapper().readTree(stdout.toString(StandardCharsets.UTF_8));
        assertEquals(false, output.get("ok").asBoolean());
        assertEquals("INFO_FAILED", output.get("code").asText());
        assertTrue(output.get("message").asText().contains("__missing\ninfo_alias__"));
    }

    @Test
    void queryFailureInJsonModeProducesOneMachineReadableError() throws Exception {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            System.setErr(err);
            assertEquals(1, SqlCli.run("__missing_json_query_alias__",
                    "SELECT 1", "--format", "json"));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        JsonNode output = new ObjectMapper().readTree(stdout.toString(StandardCharsets.UTF_8));
        assertEquals(false, output.get("ok").asBoolean());
        assertEquals("EXECUTION_FAILED", output.get("code").asText());
        assertTrue(output.get("message").asText().contains("Unknown alias"));
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
    }

    @Test
    void listJsonUsesStableSuccessEnvelopeInsteadOfBareArray() throws Exception {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            assertEquals(0, SqlCli.run("list", "--json"));
        } finally {
            System.setOut(originalOut);
        }

        JsonNode output = new ObjectMapper().readTree(stdout.toString(StandardCharsets.UTF_8));
        assertTrue(output.get("ok").asBoolean());
        assertTrue(output.get("data").isArray());
    }

    @Test
    void driverListJsonUsesStableSuccessEnvelopeInsteadOfBareArray() throws Exception {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            assertEquals(0, SqlCli.run("driver", "list", "--json"));
        } finally {
            System.setOut(originalOut);
        }

        JsonNode output = new ObjectMapper().readTree(stdout.toString(StandardCharsets.UTF_8));
        assertTrue(output.get("ok").asBoolean());
        assertTrue(output.get("data").isArray());
    }

    @Test
    void connectionTestJsonFailureUsesStableErrorEnvelope() throws Exception {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            System.setErr(err);
            assertEquals(1, SqlCli.run("__missing_json_test_alias__", "test", "--json"));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        JsonNode output = new ObjectMapper().readTree(stdout.toString(StandardCharsets.UTF_8));
        assertEquals(false, output.get("ok").asBoolean());
        assertEquals("CONNECTION_FAILED", output.get("code").asText());
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
    }

    @Test
    void secretStatusJsonFailureUsesStableErrorEnvelope() throws Exception {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            assertEquals(1, SqlCli.run(
                    "__missing_json_secret_alias__", "secret", "status", "--json"));
        } finally {
            System.setOut(originalOut);
        }

        JsonNode output = new ObjectMapper().readTree(stdout.toString(StandardCharsets.UTF_8));
        assertEquals(false, output.get("ok").asBoolean());
        assertEquals("SECRET_FAILED", output.get("code").asText());
    }

    @Test
    void newSchemaCommandsExposeTheirOnlyHelpEntry() {
        assertEquals(0, SqlCli.run("missing-alias", "schema", "policy", "--help"));
    }
}
