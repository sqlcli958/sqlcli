package com.sqlcli.graph.policy;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

final class PolicyHashes {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private PolicyHashes() {
    }

    static String object(Object value) {
        try {
            return sha256(MAPPER.writeValueAsBytes(value));
        } catch (Exception e) {
            throw new IllegalArgumentException("cannot hash policy object", e);
        }
    }

    static String fingerprint(String alias, String ruleSetId, String ruleId, String targetId, String field) {
        return sha256(String.join("\u0000", alias, ruleSetId, ruleId, targetId,
                field == null ? "" : field).getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
