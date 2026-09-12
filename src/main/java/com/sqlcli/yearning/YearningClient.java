package com.sqlcli.yearning;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcli.secret.SecretResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class YearningClient {
    private static final Logger log = LoggerFactory.getLogger(YearningClient.class);
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, String> tokenCache = new ConcurrentHashMap<>();
    private final SecretResolver secretResolver = new SecretResolver();

    public YearningClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    YearningClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public YearningQueryResult query(YearningConfig config, String sql) throws IOException, InterruptedException {
        return query(config, sql, true);
    }

    private YearningQueryResult query(YearningConfig config, String sql, boolean allowRelogin) throws IOException, InterruptedException {
        String authorization = authorization(config);
        try {
            prepareQuery(config, sql, authorization);
        } catch (YearningAuthException e) {
            if (allowRelogin && config.usesLogin()) {
                log.debug("[Yearning] refer auth failed, relogin and retry once");
                refreshToken(config);
                return query(config, sql, false);
            }
            throw e;
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("sql", sql);
        request.put("database", config.getDatabase());
        request.put("source", config.getDatabase());
        String requestBody = mapper.writeValueAsString(request);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(config.getHost() + "/api/v2/query/results"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        printRequest("query", httpRequest.uri(), Map.of(
                "Authorization", maskToken(authorization),
                "Content-Type", "application/json"
        ), requestBody);
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        printResponse("query", response.statusCode(), response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            if (allowRelogin && isAuthFailure(response.statusCode(), response.body()) && config.usesLogin()) {
                log.debug("[Yearning] query auth failed, relogin and retry once");
                refreshToken(config);
                return query(config, sql, false);
            }
            throw new IOException("Yearning HTTP " + response.statusCode() + ": " + response.body());
        }
        try {
            return parseQueryResult(response.body());
        } catch (YearningAuthException e) {
            if (allowRelogin && config.usesLogin()) {
                refreshToken(config);
                return query(config, sql, false);
            }
            throw new IOException(e.getMessage(), e);
        }
    }

    private void prepareQuery(YearningConfig config, String sql, String authorization) throws IOException, InterruptedException {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("export", 1);
        request.put("idc", config.getIdc());
        request.put("assigned", "admin");
        request.put("text", sql == null || sql.isBlank() ? "1" : sql);
        String requestBody = mapper.writeValueAsString(request);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(config.getHost() + "/api/v2/query/refer"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", authorization)
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("Accept", "application/json, text/plain, */*")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        printRequest("refer", httpRequest.uri(), Map.of(
                "Authorization", maskToken(authorization),
                "Content-Type", "application/json;charset=UTF-8",
                "Accept", "application/json, text/plain, */*"
        ), requestBody);
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        printResponse("refer", response.statusCode(), response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            if (isAuthFailure(response.statusCode(), response.body())) {
                throw new YearningAuthException("Yearning refer auth failed");
            }
            throw new IOException("Yearning refer HTTP " + response.statusCode() + ": " + response.body());
        }
        validateYearningSuccess(response.body(), "Yearning refer failed");
    }

    private YearningQueryResult parseQueryResult(String body) throws IOException {
        JsonNode root = mapper.readTree(body);
        validateSuccessOrThrow(root, "Yearning query failed");

        JsonNode payload = root.path("payload");
        YearningQueryResult result = new YearningQueryResult();
        result.setTotal(payload.path("total").asInt());
        result.setElapsedMs(payload.path("time").asLong());

        JsonNode title = payload.path("title");
        if (title.isArray()) {
            for (JsonNode item : title) {
                String key = item.path("key").asText(null);
                if (key == null || key.isBlank()) {
                    key = item.path("title").asText(null);
                }
                if (key != null && !key.isBlank()) {
                    result.getColumns().add(key);
                }
            }
        }

        JsonNode data = payload.path("data");
        if (data.isArray()) {
            for (JsonNode rowNode : data) {
                Map<String, Object> row = mapper.convertValue(rowNode, new TypeReference<LinkedHashMap<String, Object>>() {});
                if (result.getColumns().isEmpty()) {
                    result.getColumns().addAll(row.keySet());
                }
                result.addRow(row);
            }
        }

        return result;
    }

    private void validateYearningSuccess(String body, String defaultMessage) throws IOException {
        JsonNode root = mapper.readTree(body);
        validateSuccessOrThrow(root, defaultMessage);
    }

    private void validateSuccessOrThrow(JsonNode root, String defaultMessage) throws IOException {
        int code = root.path("code").asInt();
        if (code != 1200) {
            String message = root.path("text").asText(defaultMessage);
            if (isAuthFailure(code, message)) {
                throw new YearningAuthException(message);
            }
            throw new IOException(message);
        }
    }

    public boolean test(YearningConfig config) throws IOException, InterruptedException {
        query(config, "SELECT 1");
        return true;
    }

    private String authorization(YearningConfig config) throws IOException, InterruptedException {
        if (!config.usesLogin()) {
            return config.getToken();
        }
        String key = cacheKey(config);
        String token = tokenCache.get(key);
        if (token == null || token.isBlank()) {
            token = loadStoredToken(config);
            if (token != null && !token.isBlank()) {
                tokenCache.put(key, token);
            }
        }
        if (token == null || token.isBlank()) {
            token = loginAndStore(config);
            tokenCache.put(key, token);
        }
        return token;
    }

    private void refreshToken(YearningConfig config) throws IOException, InterruptedException {
        log.debug("[Yearning] refreshing token for {}", config.getTokenSecretName());
        tokenCache.put(cacheKey(config), loginAndStore(config));
    }

    private String login(YearningConfig config) throws IOException, InterruptedException {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("username", config.getUsername());
        request.put("password", config.getPassword());
        String maskedRequestBody = mapper.writeValueAsString(maskPasswordRequest(request));

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(config.getHost() + "/login"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("Accept", "application/json, text/plain, */*")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request)))
                .build();

        printRequest("login", httpRequest.uri(), Map.of(
                "Content-Type", "application/json;charset=UTF-8",
                "Accept", "application/json, text/plain, */*"
        ), maskedRequestBody);
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        printResponse("login", response.statusCode(), response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Yearning login HTTP " + response.statusCode() + ": " + response.body());
        }

        String token = extractToken(response);
        if (token == null || token.isBlank()) {
            throw new IOException("Yearning login response does not contain token");
        }
        return normalizeToken(token);
    }

    private String loginAndStore(YearningConfig config) throws IOException, InterruptedException {
        String token = login(config);
        storeToken(config, token);
        return token;
    }

    private String loadStoredToken(YearningConfig config) {
        String name = config.getTokenSecretName();
        if (name == null || name.isBlank() || !secretResolver.plaintextSecretExists(name)) {
            return null;
        }
        return normalizeToken(secretResolver.getPlaintextSecret(name));
    }

    private void storeToken(YearningConfig config, String token) {
        String name = config.getTokenSecretName();
        if (name == null || name.isBlank()) {
            return;
        }
        secretResolver.storePlaintextSecret(name, normalizeToken(token));
        log.debug("[Yearning] stored token to settings secrets.{}", name);
    }

    private String extractToken(HttpResponse<String> response) throws IOException {
        String headerToken = response.headers().firstValue("Authorization")
                .or(() -> response.headers().firstValue("authorization"))
                .orElse(null);
        if (headerToken != null && !headerToken.isBlank()) {
            return headerToken;
        }

        JsonNode root = mapper.readTree(response.body());
        String token = firstText(root, "token", "jwt", "authorization");
        if (token != null) {
            return token;
        }
        if (root.path("payload").isTextual()) {
            return root.path("payload").asText();
        }
        token = firstText(root.path("payload"), "token", "jwt", "authorization");
        if (token != null) {
            return token;
        }
        if (root.path("data").isTextual()) {
            return root.path("data").asText();
        }
        return firstText(root.path("data"), "token", "jwt", "authorization");
    }

    private String firstText(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        for (String field : fields) {
            String value = node.path(field).asText(null);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String normalizeToken(String value) {
        String token = value.trim();
        if (token.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            return token;
        }
        return "Bearer " + token;
    }

    private String cacheKey(YearningConfig config) {
        return config.getHost() + "|" + config.getUsername();
    }

    private boolean isAuthFailure(int code, String bodyOrMessage) {
        if (code == 401 || code == 403 || code == 1301 || code == 1401) {
            return true;
        }
        String text = bodyOrMessage == null ? "" : bodyOrMessage.toLowerCase();
        return text.contains("unauthorized")
                || text.contains("forbidden")
                || text.contains("token")
                || text.contains("jwt")
                || text.contains("auth")
                || text.contains("login")
                || text.contains("expired")
                || text.contains("过期")
                || text.contains("登录")
                || text.contains("认证");
    }

    private void printRequest(String action, URI uri, Map<String, String> headers, String body) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug("[Yearning] {} request", action);
        log.debug("  url: {}", uri);
        log.debug("  headers: {}", headers);
        log.debug("  body: {}", body);
    }

    private void printResponse(String action, int statusCode, String body) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug("[Yearning] {} response", action);
        log.debug("  status: {}", statusCode);
        log.debug("  body: {}", body);
    }

    private Map<String, Object> maskPasswordRequest(Map<String, Object> request) {
        Map<String, Object> masked = new LinkedHashMap<>(request);
        if (masked.containsKey("password")) {
            masked.put("password", "***");
        }
        return masked;
    }

    private String maskToken(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        int keep = Math.min(16, token.length());
        return token.substring(0, keep) + "...";
    }

    private static class YearningAuthException extends IOException {
        YearningAuthException(String message) {
            super(message);
        }
    }

}
