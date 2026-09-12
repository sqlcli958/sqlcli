package com.sqlcli.graph.ui.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcli.graph.ui.JsonHttpSupport;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖需求里明写的行为：只列目录 + sqlite 后缀文件、过滤其他文件、非回环 403、
 * 读不了的路径给出可读错误而不是 500。
 */
class FileBrowseControllerTest {
    private HttpServer server;
    private Path root;

    @AfterEach
    void cleanUp() throws Exception {
        if (server != null) server.stop(0);
        if (root != null) {
            try (var files = Files.walk(root)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void listsDirectoriesAndSqliteFilesOnly() throws Exception {
        root = Files.createTempDirectory("file-browse-");
        Files.createDirectory(root.resolve("sub"));
        Files.writeString(root.resolve("shop.db"), "x");
        Files.writeString(root.resolve("notes.txt"), "x");
        Files.writeString(root.resolve("cache.sqlite3"), "x");

        start(true);
        JsonNode body = get("/api/fs/list?path=" + URLEncoder.encode(root.toString(), StandardCharsets.UTF_8));

        assertEquals(root.toAbsolutePath().normalize().toString(), body.get("path").asText());
        JsonNode entries = body.get("entries");
        assertEquals(3, entries.size(), entries.toString());
        boolean sawSub = false, sawDb = false, sawSqlite = false;
        for (JsonNode entry : entries) {
            String name = entry.get("name").asText();
            if (name.equals("sub")) { sawSub = true; assertTrue(entry.get("dir").asBoolean()); }
            if (name.equals("shop.db")) { sawDb = true; assertFalse(entry.get("dir").asBoolean()); }
            if (name.equals("cache.sqlite3")) { sawSqlite = true; }
            assertFalse(name.equals("notes.txt"), "非 sqlite 后缀的文件不该出现");
        }
        assertTrue(sawSub && sawDb && sawSqlite);
    }

    @Test
    void noPathGivesRootsAsStartingPoint() throws Exception {
        root = Files.createTempDirectory("file-browse-root-");
        start(true);
        JsonNode body = get("/api/fs/list");
        assertTrue(body.get("path").isNull());
        assertTrue(body.get("entries").size() > 0);
    }

    @Test
    void rejectsWhenNotLoopback() throws Exception {
        root = Files.createTempDirectory("file-browse-remote-");
        start(false);
        HttpResponse<String> response = send("/api/fs/list");
        assertEquals(403, response.statusCode());
    }

    @Test
    void missingPathFailsGracefullyNot500() throws Exception {
        root = Files.createTempDirectory("file-browse-missing-");
        start(true);
        String missing = root.resolve("does-not-exist").toString();
        HttpResponse<String> response = send("/api/fs/list?path=" + URLEncoder.encode(missing, StandardCharsets.UTF_8));
        assertEquals(400, response.statusCode());
    }

    private void start(boolean loopbackOnly) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/fs", new FileBrowseController(loopbackOnly, new JsonHttpSupport()));
        server.start();
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> response = send(path);
        assertEquals(200, response.statusCode());
        return new ObjectMapper().readTree(response.body());
    }

    private HttpResponse<String> send(String path) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        return client.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
