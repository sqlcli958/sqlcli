package com.sqlcli.connection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把结构化查询结果（列名 + 按位置存的行）渲染成 csv / json / table 文本。
 *
 * <p>行按位置存而不是按列名存进 Map：{@code SELECT a.id, b.id} 两个同名列用 Map
 * 会互相覆盖丢一列，这是旧版渲染器（按 {@code ResultSetMetaData.getColumnLabel} 做
 * Map key）一直存在的 bug。新产品不需要兼容那个行为。
 *
 * <p>不再做 SM4 解密——加密改写在 SQL 执行前，解密在结果之后，是同一个关注点的两端，
 * 现在都在 {@code CipherStage} 里完成（before 加密、after 解密），值到达这里时已经是
 * 明文，渲染器只管格式化。
 */
public class QueryResultRenderer {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    public String render(List<String> columnNames, List<List<Object>> rows, String format,
                         long elapsedMs, String alias, String dbType, String statementType,
                         boolean truncated) {
        return switch (format) {
            case "json" -> renderJson(columnNames, rows, elapsedMs, alias, dbType, statementType, truncated);
            case "csv" -> renderCsv(columnNames, rows);
            case "table" -> renderTable(columnNames, rows, elapsedMs, truncated);
            default -> throw new IllegalArgumentException("Unsupported output format: " + format);
        };
    }

    private String renderJson(List<String> columnNames, List<List<Object>> rows, long elapsedMs,
                              String alias, String dbType, String statementType, boolean truncated) {
        List<Map<String, Object>> jsonRows = new ArrayList<>(rows.size());
        for (List<Object> row : rows) {
            Map<String, Object> jsonRow = new LinkedHashMap<>();
            for (int i = 0; i < columnNames.size(); i++) {
                jsonRow.put(columnNames.get(i), row.get(i));
            }
            jsonRows.add(jsonRow);
        }
        try {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("ok", true);
            output.put("alias", alias);
            output.put("dbType", dbType);
            output.put("statementType", statementType);
            output.put("columns", columnNames);
            output.put("rows", jsonRows);
            output.put("rowCount", jsonRows.size());
            output.put("truncated", truncated);
            output.put("elapsedMs", elapsedMs);
            return JSON_MAPPER.writeValueAsString(output) + System.lineSeparator();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize query result as JSON", e);
        }
    }

    private String renderCsv(List<String> columnNames, List<List<Object>> rows) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columnNames.size(); i++) {
            if (i > 0) sb.append(",");
            appendCsvField(sb, columnNames.get(i));
        }
        sb.append('\n');
        for (List<Object> row : rows) {
            for (int i = 0; i < columnNames.size(); i++) {
                if (i > 0) sb.append(",");
                Object value = row.get(i);
                if (value != null) {
                    appendCsvField(sb, String.valueOf(value));
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private void appendCsvField(StringBuilder sb, String value) {
        if (value == null) return;
        boolean quoted = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!quoted) {
            sb.append(value);
            return;
        }
        sb.append('"').append(value.replace("\"", "\"\"")).append('"');
    }

    private String renderTable(List<String> columnNames, List<List<Object>> rows, long elapsedMs, boolean truncated) {
        List<List<String>> renderedRows = new ArrayList<>();
        for (List<Object> row : rows) {
            List<String> values = new ArrayList<>(columnNames.size());
            for (Object value : row) {
                values.add(value == null ? "NULL" : String.valueOf(value));
            }
            renderedRows.add(values);
        }

        int[] widths = new int[columnNames.size()];
        for (int i = 0; i < columnNames.size(); i++) {
            widths[i] = columnNames.get(i).length();
            for (List<String> row : renderedRows) {
                widths[i] = Math.max(widths[i], row.get(i).length());
            }
        }

        StringBuilder sb = new StringBuilder();
        appendSeparator(sb, widths);
        appendRow(sb, columnNames, widths);
        appendSeparator(sb, widths);
        for (List<String> row : renderedRows) {
            appendRow(sb, row, widths);
        }
        appendSeparator(sb, widths);
        sb.append("(").append(renderedRows.size()).append(" rows");
        if (elapsedMs > 0) {
            sb.append(", ").append(elapsedMs).append("ms");
        }
        if (truncated) {
            sb.append(", truncated");
        }
        sb.append(")\n");
        return sb.toString();
    }

    private void appendSeparator(StringBuilder sb, int[] widths) {
        sb.append("+");
        for (int width : widths) {
            sb.append("-").append("-".repeat(width)).append("-+");
        }
        sb.append('\n');
    }

    private void appendRow(StringBuilder sb, List<String> row, int[] widths) {
        sb.append("|");
        for (int i = 0; i < row.size(); i++) {
            sb.append(" ").append(row.get(i)).append(" ".repeat(widths[i] - row.get(i).length())).append(" |");
        }
        sb.append('\n');
    }
}
