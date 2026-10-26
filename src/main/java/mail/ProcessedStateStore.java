package mail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

// Secondary safety net on top of the IMAP processed-flag: an append-only local file of
// processed Message-IDs (RFC 5322, stable across UID/UIDVALIDITY resets and folder moves).
// Keeps the flat-file philosophy of the existing project rather than pulling in an embedded DB.
public class ProcessedStateStore {
    private static final Logger logger = Logger.getLogger(ProcessedStateStore.class.getName());

    private final Path storePath;
    private final Set<String> processedIds = new HashSet<>();

    public ProcessedStateStore(String storePath) {
        this.storePath = Paths.get(storePath);
        load();
    }

    private void load() {
        if (!Files.exists(storePath)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(storePath, StandardCharsets.UTF_8);
            processedIds.addAll(lines);
        } catch (IOException e) {
            logger.warning("[ProcessedStateStore] Could not read " + storePath + ": " + e.getMessage()
                    + " - degrading to IMAP-flag-only dedupe for this run.");
        }
    }

    public synchronized boolean isProcessed(String messageId) {
        return messageId != null && processedIds.contains(messageId);
    }

    public synchronized void markProcessed(String messageId) {
        if (messageId == null || messageId.isEmpty() || processedIds.contains(messageId)) {
            return;
        }
        processedIds.add(messageId);
        try {
            Path parent = storePath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(storePath, messageId + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // The IMAP flag is still primary; losing this append just thins the secondary
            // safety net for this one message, not the processing result itself.
            logger.warning("[ProcessedStateStore] Could not append " + messageId + " to " + storePath
                    + ": " + e.getMessage());
        }
    }
}
