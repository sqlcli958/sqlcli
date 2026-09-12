package com.sqlcli.graph.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sqlcli.graph.ui.dto.ApiError;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * JSON HTTP support utilities for request parsing and response writing.
 */
public class JsonHttpSupport {

    private static final int MAX_BODY_SIZE = 1024 * 1024; // 1 MiB
    private final ObjectMapper objectMapper;

    public JsonHttpSupport() {
        this.objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.disable(SerializationFeature.INDENT_OUTPUT);
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    /**
     * Read and parse the request body as the given type.
     * Limits body size to 1 MiB.
     */
    public <T> T readBody(HttpExchange exchange, Class<T> type) throws IOException {
        // Check Content-Length header if available
        String contentLengthStr = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLengthStr != null) {
            try {
                long contentLength = Long.parseLong(contentLengthStr);
                if (contentLength > MAX_BODY_SIZE) {
                    throw new IOException("Request body too large (max 1 MiB)");
                }
            } catch (NumberFormatException ignored) {
                // ignore
            }
        }
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY_SIZE + 1);
            if (bytes.length > MAX_BODY_SIZE) {
                throw new IOException("Request body too large (max 1 MiB)");
            }
            if (bytes.length == 0) {
                return null;
            }
            return objectMapper.readValue(bytes, type);
        }
    }

    /**
     * Write a JSON response with the given status code.
     * Silently handles client disconnects (broken pipe).
     */
    public void writeJson(HttpExchange exchange, int statusCode, Object body) throws IOException {
        byte[] bytes = objectMapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        try {
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException e) {
            if (isClientDisconnect(e)) {
                // Client disconnected — response cannot be delivered, ignore
                return;
            }
            throw e;
        }
    }

    /**
     * Check if an exception (or one of its causes) is caused by the client disconnecting.
     * 顺着 cause 链找：断开连接常被包一层 IOException 之外的异常再抛出来。
     */
    public static boolean isClientDisconnect(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            String msg = cause.getMessage();
            if (msg != null && (msg.contains("Broken pipe")
                    || msg.contains("Connection reset by peer")
                    || msg.contains("An existing connection was forcibly closed")
                    || msg.contains("远程主机强迫关闭了一个现有的连接"))) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * Write a 200 OK JSON response.
     */
    public void writeOk(HttpExchange exchange, Object body) throws IOException {
        writeJson(exchange, 200, body);
    }

    /**
     * Write an error response mapping exceptions to HTTP status codes.
     */
    public void writeError(HttpExchange exchange, Exception e) throws IOException {
        if (e instanceof IllegalArgumentException) {
            writeJson(exchange, 400, ApiError.badRequest(e.getMessage()));
        } else if (e instanceof SecurityException) {
            writeJson(exchange, 403, ApiError.unauthorized(e.getMessage()));
        } else {
            writeJson(exchange, 500, ApiError.internal(e.getMessage()));
        }
    }

    /**
     * Write a 404 Not Found response.
     */
    public void writeNotFound(HttpExchange exchange, String message) throws IOException {
        writeJson(exchange, 404, ApiError.notFound(message));
    }

    /**
     * 校验请求的 Origin 头。通过返回 true；不通过时自己写完 403 响应并返回 false，
     * 调用方只需 {@code if (!json.requireAllowedOrigin(exchange, allowedOrigin)) return;}。
     */
    public boolean requireAllowedOrigin(HttpExchange exchange, String allowedOrigin) throws IOException {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (!OriginCheck.allows(allowedOrigin, origin)) {
            writeError(exchange, new SecurityException("Invalid Origin"));
            return false;
        }
        return true;
    }

    /**
     * Parse query parameters from the request URI.
     */
    public Map<String, String> parseQueryParams(HttpExchange exchange) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null || query.isBlank()) {
            return Map.of();
        }
        Map<String, String> params = new java.util.LinkedHashMap<>();
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length == 2) {
                try {
                    params.put(pair[0], java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
                } catch (Exception e) {
                    params.put(pair[0], pair[1]);
                }
            } else if (pair.length == 1 && !pair[0].isEmpty()) {
                params.put(pair[0], "");
            }
        }
        return params;
    }

    /**
     * Get a query parameter value or default.
     */
    public String getParam(Map<String, String> params, String key, String defaultValue) {
        String value = params.get(key);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }

    /**
     * Get an integer query parameter value or default.
     */
    public int getIntParam(Map<String, String> params, String key, int defaultValue) {
        String value = params.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
