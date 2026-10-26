package admin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminServerTest {
    private AdminServer server;
    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getPort();
    }

    @Test
    void indexPageServesBundledHtml(@TempDir Path rulesDir) throws Exception {
        server = new AdminServer(rulesDir.toString());
        server.start(0);

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("<html"));
        assertTrue(response.body().contains("Client Rule Builder"));
    }

    @Test
    void savingValidYamlWritesFileAndListsIt(@TempDir Path rulesDir) throws Exception {
        server = new AdminServer(rulesDir.toString());
        server.start(0);

        String yaml = "client_id: test-client\nmatch:\n  from: [\"a@b.com\"]\nfields:\n  estate:\n    literal: \"X\"\n";
        String payload = "{\"fileName\":\"test-client.yaml\",\"yaml\":\"" + jsonEscape(yaml) + "\"}";

        HttpResponse<String> saveResponse = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/api/rules/save"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, saveResponse.statusCode());
        assertTrue(saveResponse.body().contains("\"success\""));
        assertTrue(Files.exists(rulesDir.resolve("test-client.yaml")));

        HttpResponse<String> listResponse = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/api/rules")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(listResponse.body().contains("test-client.yaml"));

        HttpResponse<String> rawResponse = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/api/rules/raw?file=test-client.yaml")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, rawResponse.statusCode());
        assertTrue(rawResponse.body().contains("client_id: test-client"));
    }

    @Test
    void savingInvalidYamlReturns400AndDoesNotWriteFile(@TempDir Path rulesDir) throws Exception {
        server = new AdminServer(rulesDir.toString());
        server.start(0);

        String payload = "{\"fileName\":\"bad.yaml\",\"yaml\":\"not_a_valid_rule: true\"}";
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/api/rules/save"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("\"error\""));
        assertFalse(Files.exists(rulesDir.resolve("bad.yaml")));
    }

    @Test
    void savingWithUnsafeFileNameIsRejected(@TempDir Path rulesDir) throws Exception {
        server = new AdminServer(rulesDir.toString());
        server.start(0);

        String payload = "{\"fileName\":\"../escape.yaml\",\"yaml\":\"client_id: x\\nmatch:\\n  from: [\\\"a@b.com\\\"]\\n\"}";
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/api/rules/save"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode());
    }

    private static String jsonEscape(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
