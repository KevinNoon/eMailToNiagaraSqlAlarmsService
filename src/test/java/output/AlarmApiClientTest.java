package output;

import com.sun.net.httpserver.HttpServer;
import model.AlarmRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlarmApiClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static AlarmRecord sampleRecord() {
        AlarmRecord record = new AlarmRecord();
        record.estate = "North Campus";
        record.handle = "north-campus:abc123";
        record.timestamp = "2026-07-29 19:05:00";
        return record;
    }

    private String startServer(AtomicInteger requestCount, java.util.function.IntSupplier statusForRequest) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/endpoint", exchange -> {
            requestCount.incrementAndGet();
            int status = statusForRequest.getAsInt();
            byte[] response = "{}".getBytes();
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return "http://localhost:" + server.getAddress().getPort() + "/api/endpoint";
    }

    @Test
    void immediate200ReturnsTrueWithOneRequest() throws Exception {
        AtomicInteger count = new AtomicInteger(0);
        String url = startServer(count, () -> 200);
        AlarmApiClient client = new AlarmApiClient(url, 2000, 2000, 5, 10, 2.0);

        boolean result = client.sendBatch(List.of(sampleRecord()));

        assertTrue(result);
        assertEquals(1, count.get());
    }

    @Test
    void status400IsNotRetried() throws Exception {
        AtomicInteger count = new AtomicInteger(0);
        String url = startServer(count, () -> 400);
        AlarmApiClient client = new AlarmApiClient(url, 2000, 2000, 5, 10, 2.0);

        boolean result = client.sendBatch(List.of(sampleRecord()));

        assertFalse(result);
        assertEquals(1, count.get());
    }

    @Test
    void retriesOn500ThenSucceeds() throws Exception {
        AtomicInteger count = new AtomicInteger(0);
        String url = startServer(count, () -> count.get() < 3 ? 500 : 200);
        AlarmApiClient client = new AlarmApiClient(url, 2000, 2000, 5, 10, 2.0);

        boolean result = client.sendBatch(List.of(sampleRecord()));

        assertTrue(result);
        assertEquals(3, count.get());
    }

    @Test
    void alwaysFailingReturnsFalseAfterMaxAttempts() throws Exception {
        AtomicInteger count = new AtomicInteger(0);
        String url = startServer(count, () -> 500);
        AlarmApiClient client = new AlarmApiClient(url, 2000, 2000, 4, 10, 2.0);

        boolean result = client.sendBatch(List.of(sampleRecord()));

        assertFalse(result);
        assertEquals(4, count.get());
    }

    @Test
    void connectionRefusedReturnsFalseGracefully() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        String url = "http://localhost:" + closedPort + "/api/endpoint";
        AlarmApiClient client = new AlarmApiClient(url, 1000, 1000, 3, 10, 2.0);

        boolean result = client.sendBatch(List.of(sampleRecord()));

        assertFalse(result);
    }
}
