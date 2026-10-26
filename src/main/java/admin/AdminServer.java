package admin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import rules.RuleLoader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Minimal admin HTTP server for creating/editing per-client rule YAML files through a
// browser while the app is running. Deliberately hardcoded to bind 127.0.0.1 regardless of
// config - this is a local operator convenience tool, not a network-facing service, so it
// gets no separate auth and must never be reachable from outside this machine.
public class AdminServer {
    private static final Logger logger = Logger.getLogger(AdminServer.class.getName());

    private final Path rulesDirectory;
    private HttpServer server;

    public AdminServer(String rulesDirectoryPath) {
        this.rulesDirectory = Paths.get(rulesDirectoryPath);
    }

    public void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", this::handleIndex);
        server.createContext("/api/rules/raw", this::handleRuleRaw);
        server.createContext("/api/rules/save", this::handleRuleSave);
        server.createContext("/api/rules", this::handleRulesList);
        server.setExecutor(null);
        server.start();
        logger.info("[AdminServer] Listening on http://127.0.0.1:" + getPort() + " (loopback only)");
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    private void handleIndex(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        byte[] html = loadResource("/admin/index.html");
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, html.length);
        exchange.getResponseBody().write(html);
        exchange.close();
    }

    private void handleRulesList(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        List<String> names = new ArrayList<>();
        if (Files.isDirectory(rulesDirectory)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(rulesDirectory, "*.y*ml")) {
                for (Path p : stream) {
                    names.add(p.getFileName().toString());
                }
            }
        }
        names.sort(String::compareTo);

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escapeJson(names.get(i))).append('"');
        }
        sb.append(']');
        sendJson(exchange, 200, sb.toString());
    }

    private void handleRuleRaw(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        String file = queryParam(exchange.getRequestURI().getQuery(), "file");
        if (file == null || !isSafeFileName(file)) {
            sendText(exchange, 400, "Invalid or missing 'file' parameter");
            return;
        }
        Path path = rulesDirectory.resolve(file);
        if (!Files.exists(path)) {
            sendText(exchange, 404, "Not found");
            return;
        }
        sendText(exchange, 200, Files.readString(path, StandardCharsets.UTF_8));
    }

    private void handleRuleSave(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        String body = readBody(exchange);
        String fileName = extractJsonValue(body, "fileName");
        String yamlText = extractJsonValue(body, "yaml");

        if (fileName == null || !isSafeFileName(fileName)) {
            sendJson(exchange, 400, errorJson("Invalid or missing file name (must be <name>.yaml / <name>.yml, no path separators)."));
            return;
        }
        if (yamlText == null || yamlText.isBlank()) {
            sendJson(exchange, 400, errorJson("YAML content is empty."));
            return;
        }

        String validationError = RuleLoader.validate(yamlText);
        if (validationError != null) {
            sendJson(exchange, 400, errorJson(validationError));
            return;
        }

        try {
            Files.createDirectories(rulesDirectory);
            Files.writeString(rulesDirectory.resolve(fileName), yamlText, StandardCharsets.UTF_8);
        } catch (IOException e) {
            sendJson(exchange, 500, errorJson(e.getMessage()));
            return;
        }

        logger.info("[AdminServer] Saved rule file " + fileName + " via admin page.");
        sendJson(exchange, 200, "{\"status\":\"success\"}");
    }

    private static String errorJson(String message) {
        return "{\"status\":\"error\",\"message\":\"" + escapeJson(message) + "\"}";
    }

    // No path separators/traversal - this name is resolved directly under rulesDirectory.
    private static boolean isSafeFileName(String name) {
        return name.matches("[A-Za-z0-9_-]+\\.ya?ml");
    }

    private static String queryParam(String query, String key) {
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            if (k.equals(key)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private static byte[] loadResource(String path) throws IOException {
        try (InputStream in = AdminServer.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("Missing bundled resource: " + path);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toByteArray();
        }
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void sendText(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    // Hand-rolled JSON string-value extraction, matching the existing codebase's own
    // regex-based JSON philosophy (see NiagaraSQLAlarmServer.extractJsonValue).
    static String extractJsonValue(String json, String key) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            return null;
        }
        return unescapeJson(matcher.group(1));
    }

    private static String unescapeJson(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    default: sb.append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String escapeJson(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
}
