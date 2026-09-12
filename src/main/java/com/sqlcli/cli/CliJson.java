package com.sqlcli.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.LinkedHashMap;
import java.util.Map;

final class CliJson {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private CliJson() {
    }

    static void printSuccess(Object data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("ok", true);
        envelope.put("data", data);
        print(envelope);
    }

    static void printFailure(String code, String message) {
        printFailure(code, message, null);
    }

    static void printFailure(String code, String message, Object data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("ok", false);
        envelope.put("code", code);
        envelope.put("message", message == null ? "Unknown error" : message);
        if (data != null) envelope.put("data", data);
        print(envelope);
    }

    private static void print(Object value) {
        try {
            System.out.println(MAPPER.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize JSON output", e);
        }
    }
}
