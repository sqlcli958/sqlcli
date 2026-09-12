package com.sqlcli.graph.ui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLConnection;

import java.util.Map;

/**
 * Serves static resources from the classpath /web directory.
 * <ul>
 *   <li>Prevents path traversal (../)</li>
 *   <li>Vite hash assets get 1-year cache</li>
 *   <li>index.html gets no-cache</li>
 *   <li>SPA fallback: unknown paths return index.html</li>
 * </ul>
 */
public class StaticResourceHandler implements HttpHandler {

    private static final Logger LOG = LoggerFactory.getLogger(StaticResourceHandler.class);

    // Vite hash asset pattern: name-[8+hex chars].ext
    private static final java.util.regex.Pattern HASH_ASSET_PATTERN =
            java.util.regex.Pattern.compile(".+-[a-f0-9]{8,}\\..+");

    private static final Map<String, String> MIME_TYPES = buildMimeTypes();

    private static Map<String, String> buildMimeTypes() {
        Map<String, String> map = new java.util.HashMap<>();
        map.put(".html", "text/html; charset=utf-8");
        map.put(".css", "text/css; charset=utf-8");
        map.put(".js", "application/javascript; charset=utf-8");
        map.put(".json", "application/json; charset=utf-8");
        map.put(".svg", "image/svg+xml");
        map.put(".png", "image/png");
        map.put(".jpg", "image/jpeg");
        map.put(".jpeg", "image/jpeg");
        map.put(".gif", "image/gif");
        map.put(".ico", "image/x-icon");
        map.put(".woff", "font/woff");
        map.put(".woff2", "font/woff2");
        map.put(".ttf", "font/ttf");
        map.put(".eot", "application/vnd.ms-fontobject");
        map.put(".webp", "image/webp");
        map.put(".map", "application/json");
        return map;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod()) && !"HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String path = exchange.getRequestURI().getPath();
        // Normalize: remove leading slash, default to index.html
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.isEmpty()) {
            path = "index.html";
        }

        // Prevent path traversal
        if (path.contains("..") || path.contains("\\")) {
            exchange.sendResponseHeaders(403, -1);
            return;
        }

        // API paths should not be handled here
        if (path.startsWith("api/")) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }

        // Try to serve the exact file first
        byte[] content = loadResource(path);
        if (content != null) {
            serveFile(exchange, path, content);
            return;
        }

        // SPA fallback: serve index.html for unknown paths
        content = loadResource("index.html");
        if (content != null) {
            serveFile(exchange, "index.html", content);
            return;
        }

        // No index.html found (frontend not built/packaged yet)
        exchange.sendResponseHeaders(404, -1);
    }

    private byte[] loadResource(String path) {
        String classpathPath = "web/" + path;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(classpathPath)) {
            if (in == null) {
                return null;
            }
            return in.readAllBytes();
        } catch (IOException e) {
            LOG.debug("Failed to load resource: {}", classpathPath, e);
            return null;
        }
    }

    private void serveFile(HttpExchange exchange, String path, byte[] content) throws IOException {
        String contentType = guessContentType(path);
        exchange.getResponseHeaders().set("Content-Type", contentType);

        // Cache policy
        if ("index.html".equals(path)) {
            // index.html: never cache
            exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
            exchange.getResponseHeaders().set("Pragma", "no-cache");
        } else if (isHashAsset(path)) {
            // Vite hash asset: cache for 1 year
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
        } else {
            // Other files: short cache
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
        }

        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, -1);
            return;
        }

        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(content);
        }
    }

    private String guessContentType(String path) {
        int dotIndex = path.lastIndexOf('.');
        if (dotIndex >= 0) {
            String ext = path.substring(dotIndex).toLowerCase();
            String mime = MIME_TYPES.get(ext);
            if (mime != null) {
                return mime;
            }
        }
        // Fallback
        String guessed = URLConnection.guessContentTypeFromName(path);
        return guessed != null ? guessed : "application/octet-stream";
    }

    private boolean isHashAsset(String path) {
        // Extract the filename (last segment)
        int slashIndex = path.lastIndexOf('/');
        String filename = slashIndex >= 0 ? path.substring(slashIndex + 1) : path;
        return HASH_ASSET_PATTERN.matcher(filename).matches();
    }
}
