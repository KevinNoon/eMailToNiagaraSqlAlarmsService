package normalize;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

// handle = client_id + ":" + sha256(lower(join("|", declared field values)))[:24]
// Deterministic and namespaced per client so the same point always synthesizes the same
// handle (repeated/resent emails upsert instead of duplicating), without manufacturing any
// correlation the client's own email stream never sends.
public class HandleSynthesizer {

    public static String synthesize(String clientId, List<String> declaredFieldValuesInOrder) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < declaredFieldValuesInOrder.size(); i++) {
            if (i > 0) {
                joined.append('|');
            }
            String value = declaredFieldValuesInOrder.get(i);
            joined.append(value == null ? "" : value);
        }
        String hash = sha256Hex(joined.toString().toLowerCase());
        return clientId + ":" + hash.substring(0, Math.min(24, hash.length()));
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every JVM; this can't realistically happen.
            throw new IllegalStateException(e);
        }
    }
}
