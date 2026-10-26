package mail;

import deadletter.DeadLetterHandler;
import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Header;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.search.FlagTerm;
import jakarta.mail.search.NotTerm;
import model.AlarmRecord;
import output.AlarmApiClient;
import rules.RuleEngine;
import rules.RuleSet;

import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

// IMAP connect/fetch/flag logic for one poll cycle. An email is marked processed only after
// a confirmed sendBatch success - failures leave it untouched so the next poll retries
// (safe because the target API's handle-based upsert makes resending idempotent).
public class MailboxPoller {
    private static final Logger logger = Logger.getLogger(MailboxPoller.class.getName());

    static final String PROCESSED_FLAG = "$MailAlarmBridge-Processed";
    static final String UNMATCHED_FLAG = "$MailAlarmBridge-Unmatched";

    private final String host;
    private final int port;
    private final boolean ssl;
    private final String user;
    private final String password;
    private final String folderName;
    private final String unmatchedFolderName;
    private final boolean moveEnabled;
    private final String moveFolderName;
    private final ProcessedStateStore stateStore;

    public MailboxPoller(String host, int port, boolean ssl, String user, String password,
                          String folderName, String unmatchedFolderName,
                          boolean moveEnabled, String moveFolderName,
                          ProcessedStateStore stateStore) {
        this.host = host;
        this.port = port;
        this.ssl = ssl;
        this.user = user;
        this.password = password;
        this.folderName = folderName;
        this.unmatchedFolderName = unmatchedFolderName;
        this.moveEnabled = moveEnabled;
        this.moveFolderName = moveFolderName;
        this.stateStore = stateStore;
    }

    public PollResult pollOnce(List<RuleSet> rules, RuleEngine ruleEngine, DeadLetterHandler deadLetterHandler,
                                AlarmApiClient apiClient, int batchLimit) {
        PollResult result = new PollResult();
        Store store = null;
        Folder folder = null;
        try {
            Session session = buildSession();
            store = connectStore(session);
            folder = store.getFolder(folderName);
            folder.open(Folder.READ_WRITE);

            Flags processedFlags = new Flags();
            processedFlags.add(PROCESSED_FLAG);
            Message[] found = folder.search(new NotTerm(new FlagTerm(processedFlags, true)));
            result.emailsFetched = found.length;

            int limit = Math.min(found.length, batchLimit);
            for (int i = 0; i < limit; i++) {
                processOneMessage(found[i], folder, rules, ruleEngine, deadLetterHandler, apiClient, result);
            }
        } catch (Exception e) {
            logger.warning("[MailboxPoller] Poll cycle failed: " + e.getMessage());
            result.cycleFullyFailed = true;
        } finally {
            closeFolderQuietly(folder);
            closeStoreQuietly(store);
        }
        return result;
    }

    private void processOneMessage(Message message, Folder folder, List<RuleSet> rules, RuleEngine ruleEngine,
                                     DeadLetterHandler deadLetterHandler, AlarmApiClient apiClient,
                                     PollResult result) {
        try {
            String messageId = messageIdOf(message);
            if (stateStore.isProcessed(messageId)) {
                // Flag got lost to a UID/UIDVALIDITY reset or similar - the local store
                // catches it; just re-stamp the flag and move on.
                setFlag(message, PROCESSED_FLAG, true);
                return;
            }

            String from = fromAddressOf(message);
            String subject = message.getSubject();
            String body = bodyOf(message);
            Map<String, String> headers = headersOf(message);

            RuleSet rule = RuleEngine.match(rules, from, subject);
            List<AlarmRecord> records = rule == null
                    ? List.of()
                    : ruleEngine.extract(rule, subject, body, from, headers, messageId);

            if (rule == null || records.isEmpty()) {
                deadLetterHandler.handleUnmatched(from, subject, messageId);
                markUnmatched(message, folder);
                result.emailsDeadLettered++;
                return;
            }

            if (apiClient.sendBatch(records)) {
                setFlag(message, PROCESSED_FLAG, true);
                message.setFlag(Flags.Flag.SEEN, true);
                if (moveEnabled) {
                    moveMessage(message, folder, moveFolderName);
                }
                stateStore.markProcessed(messageId);
                result.emailsProcessed++;
            } else {
                // Leave completely untouched - next poll retries the whole fetch-and-send flow.
                result.emailsFailed++;
            }
        } catch (Exception e) {
            logger.warning("[MailboxPoller] Failed to process one message: " + e.getMessage());
            result.emailsFailed++;
        }
    }

    private void markUnmatched(Message message, Folder folder) throws MessagingException {
        setFlag(message, UNMATCHED_FLAG, true);
        if (unmatchedFolderName != null && !unmatchedFolderName.isEmpty()) {
            moveMessage(message, folder, unmatchedFolderName);
        }
    }

    private void moveMessage(Message message, Folder sourceFolder, String targetFolderName) {
        if (targetFolderName == null || targetFolderName.isEmpty()) {
            return;
        }
        try {
            Store store = sourceFolder.getStore();
            Folder target = store.getFolder(targetFolderName);
            if (!target.exists()) {
                target.create(Folder.HOLDS_MESSAGES);
            }
            sourceFolder.copyMessages(new Message[]{message}, target);
            message.setFlag(Flags.Flag.DELETED, true);
        } catch (MessagingException e) {
            logger.warning("[MailboxPoller] Could not move message to " + targetFolderName + ": " + e.getMessage());
        }
    }

    private Session buildSession() {
        String protocol = ssl ? "imaps" : "imap";
        Properties props = new Properties();
        props.put("mail.store.protocol", protocol);
        props.put("mail." + protocol + ".host", host);
        props.put("mail." + protocol + ".port", String.valueOf(port));
        props.put("mail." + protocol + ".ssl.enable", String.valueOf(ssl));
        return Session.getInstance(props);
    }

    private Store connectStore(Session session) throws MessagingException {
        Store store = session.getStore();
        store.connect(host, port, user, password);
        return store;
    }

    private static void setFlag(Message message, String keyword, boolean value) throws MessagingException {
        Flags flags = new Flags();
        flags.add(keyword);
        message.setFlags(flags, value);
    }

    private static String messageIdOf(Message message) throws MessagingException {
        String[] values = message.getHeader("Message-ID");
        return (values != null && values.length > 0) ? values[0] : "";
    }

    private static String fromAddressOf(Message message) throws MessagingException {
        Address[] from = message.getFrom();
        if (from == null || from.length == 0) {
            return "";
        }
        if (from[0] instanceof InternetAddress ia) {
            return ia.getAddress();
        }
        return from[0].toString();
    }

    private static String bodyOf(Message message) throws Exception {
        return extractText(message.getContent());
    }

    private static String extractText(Object content) throws Exception {
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof Multipart multipart) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart part = multipart.getBodyPart(i);
                if (part.isMimeType("text/plain") || part.isMimeType("text/html")
                        || part.getContent() instanceof Multipart) {
                    sb.append(extractText(part.getContent()));
                }
            }
            return sb.toString();
        }
        return content == null ? "" : content.toString();
    }

    private static Map<String, String> headersOf(Message message) throws MessagingException {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<?> e = message.getAllHeaders();
        while (e.hasMoreElements()) {
            Object o = e.nextElement();
            if (o instanceof Header h) {
                headers.put(h.getName(), h.getValue());
            }
        }
        return headers;
    }

    private static void closeFolderQuietly(Folder folder) {
        if (folder != null && folder.isOpen()) {
            try {
                folder.close(true);
            } catch (MessagingException e) {
                logger.warning("[MailboxPoller] Error closing folder: " + e.getMessage());
            }
        }
    }

    private static void closeStoreQuietly(Store store) {
        if (store != null) {
            try {
                store.close();
            } catch (MessagingException e) {
                logger.warning("[MailboxPoller] Error closing store: " + e.getMessage());
            }
        }
    }

    public static class PollResult {
        public int emailsFetched;
        public int emailsProcessed;
        public int emailsFailed;
        public int emailsDeadLettered;
        public boolean cycleFullyFailed;
    }
}
