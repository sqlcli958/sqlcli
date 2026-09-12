package com.sqlcli.connection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryResultRendererTest {
    private final QueryResultRenderer renderer = new QueryResultRenderer();

    @Test
    void jsonPreservesTypesAndEscapesControlCharacters() throws Exception {
        List<Object> row = List.of("line1\nline2\t\"quoted\"", true, 3);

        String output = renderer.render(List.of("text", "enabled", "count"), List.of(row),
                "json", 12L, "prod-main", "mysql", "SELECT", false);
        JsonNode json = new ObjectMapper().readTree(output);

        assertTrue(json.get("ok").asBoolean());
        assertEquals("prod-main", json.get("alias").asText());
        assertEquals("mysql", json.get("dbType").asText());
        assertEquals("SELECT", json.get("statementType").asText());
        assertEquals(1, json.get("rowCount").asInt());
        assertEquals(12, json.get("elapsedMs").asLong());
        assertFalse(json.get("truncated").asBoolean());
        assertEquals("line1\nline2\t\"quoted\"", json.get("rows").get(0).get("text").asText());
        assertTrue(json.get("rows").get(0).get("enabled").isBoolean());
        assertTrue(json.get("rows").get(0).get("count").isNumber());
    }

    @Test
    void jsonMarksTruncatedResults() throws Exception {
        String output = renderer.render(List.of("id"), List.of(List.of(1)),
                "json", 0L, "prod-main", "mysql", "SELECT", true);
        JsonNode json = new ObjectMapper().readTree(output);

        assertTrue(json.get("truncated").asBoolean());
    }

    @Test
    void duplicateColumnNamesKeepBothValuesInCsv() {
        // SELECT a.id, b.id 两个同名列。行按位置存，不按列名去 Map——
        // 旧渲染器把行存进 Map<String,Object> 会让后一个 id 覆盖前一个，丢一整列。
        String output = renderer.render(List.of("id", "id"), List.of(List.of(1, 2)),
                "csv", 0L, "prod-main", "mysql", "SELECT", false);

        assertEquals("id,id\n1,2\n", output);
    }

    @Test
    void csvQuotesHeadersAndValuesAccordingToRfc4180Rules() {
        List<Object> row = List.of("Smith, John", "a \"quote\"\nand newline");

        String output = renderer.render(List.of("display,name", "note"), List.of(row),
                "csv", 0L, "prod-main", "mysql", "SELECT", false);

        assertEquals("\"display,name\",note\n\"Smith, John\",\"a \"\"quote\"\"\nand newline\"\n", output);
    }

    @Test
    void unsupportedFormatIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> options("xml"));
        assertTrue(error.getMessage().contains("Unsupported output format"));
    }

    private QueryExecutionOptions options(String format) {
        return new QueryExecutionOptions(format, Set.of(), null);
    }
}
