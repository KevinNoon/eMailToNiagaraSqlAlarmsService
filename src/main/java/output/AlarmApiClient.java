package output;

import model.AlarmRecord;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Logger;

// Batches + POSTs AlarmRecords to NiagaraSQLAlarmServer's /api/endpoint, with retry/backoff.
// Returns true only on a confirmed 200 - callers rely on that to decide whether it's safe to
// mark the source email(s) processed.
public class AlarmApiClient {
    private static final Logger logger = Logger.getLogger(AlarmApiClient.class.getName());

    private final String apiUrl;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final double backoffMultiplier;

    public AlarmApiClient(String apiUrl, int connectTimeoutMs, int readTimeoutMs,
                           int maxAttempts, long initialBackoffMs, double backoffMultiplier) {
        this.apiUrl = apiUrl;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.maxAttempts = maxAttempts;
        this.initialBackoffMs = initialBackoffMs;
        this.backoffMultiplier = backoffMultiplier;
    }

    public boolean sendBatch(List<AlarmRecord> records) {
        if (records == null || records.isEmpty()) {
            return true;
        }
        String payload = buildPayload(records);
        long backoff = initialBackoffMs;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                int status = postOnce(payload);
                if (status == 200) {
                    return true;
                }
                if (status == 400) {
                    // Structurally rejected - retrying the same batch can't help.
                    logger.warning("[AlarmApiClient] Batch rejected with 400, not retrying. Payload: " + payload);
                    return false;
                }
                logger.warning("[AlarmApiClient] Attempt " + attempt + "/" + maxAttempts
                        + " got HTTP " + status + "; will retry on 5xx.");
                if (status < 500) {
                    return false;
                }
            } catch (Exception e) {
                logger.warning("[AlarmApiClient] Attempt " + attempt + "/" + maxAttempts
                        + " failed to connect: " + e.getMessage());
            }

            if (attempt < maxAttempts) {
                sleepQuietly(backoff);
                backoff = (long) (backoff * backoffMultiplier);
            }
        }
        logger.warning("[AlarmApiClient] All " + maxAttempts + " attempts exhausted; leaving source email(s) unprocessed.");
        return false;
    }

    static String buildPayload(List<AlarmRecord> records) {
        if (records.size() == 1) {
            return records.get(0).toJsonObject();
        }
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < records.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(records.get(i).toJsonObject());
        }
        sb.append(']');
        return sb.toString();
    }

    int postOnce(String payload) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(apiUrl).toURL().openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
            connection.setDoOutput(true);

            byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(body);
            }

            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
