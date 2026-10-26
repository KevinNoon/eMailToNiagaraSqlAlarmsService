package deadletter;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Properties;
import java.util.logging.Logger;

// Unmatched/failed email handling + SMTP alerting. Reused for circuit-breaker-open and
// retry-exhaustion events, not just unmatched senders - sendAlert is the one shared path.
public class DeadLetterHandler {
    private static final Logger logger = Logger.getLogger(DeadLetterHandler.class.getName());

    private final String smtpHost;
    private final String smtpFrom;
    private final String smtpTo;
    private final int thresholdCount;
    private final long thresholdWindowMillis;

    private final Deque<Long> recentUnmatchedTimestamps = new ArrayDeque<>();

    public DeadLetterHandler(String smtpHost, String smtpFrom, String smtpTo,
                              int thresholdCount, int thresholdWindowMinutes) {
        this.smtpHost = smtpHost;
        this.smtpFrom = smtpFrom;
        this.smtpTo = smtpTo;
        this.thresholdCount = thresholdCount;
        this.thresholdWindowMillis = thresholdWindowMinutes * 60_000L;
    }

    public void handleUnmatched(String fromAddress, String subject, String messageId) {
        // Never silently dropped: logged every time regardless of whether this trips the
        // alert threshold.
        logger.warning("[DeadLetterHandler] Unmatched email - From: " + fromAddress
                + ", Subject: " + subject + ", Message-ID: " + messageId);

        int count;
        synchronized (recentUnmatchedTimestamps) {
            count = pruneAndCountRecent(System.currentTimeMillis(), thresholdWindowMillis);
        }
        if (count >= thresholdCount) {
            sendAlert("MailAlarmBridge: unmatched-sender threshold exceeded",
                    "Unmatched email volume (" + count + " in the last " + (thresholdWindowMillis / 60_000)
                            + " minutes) has crossed the configured threshold of " + thresholdCount + ".\n"
                            + "Most recent unmatched email - From: " + fromAddress + ", Subject: " + subject
                            + ", Message-ID: " + messageId);
        }
    }

    int pruneAndCountRecent(long nowMs, long windowMs) {
        recentUnmatchedTimestamps.addLast(nowMs);
        long cutoff = nowMs - windowMs;
        while (!recentUnmatchedTimestamps.isEmpty() && recentUnmatchedTimestamps.peekFirst() < cutoff) {
            recentUnmatchedTimestamps.pollFirst();
        }
        return recentUnmatchedTimestamps.size();
    }

    public void sendAlert(String subject, String body) {
        if (smtpHost == null || smtpHost.isEmpty()) {
            // Mirrors the reference app's "blank URL = no-op" posture for optional
            // integrations - alerting must never be able to crash the poll loop that calls it.
            logger.warning("[DeadLetterHandler] alert.smtp.host is blank; skipping SMTP alert: " + subject);
            return;
        }
        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", smtpHost);
            Session session = Session.getInstance(props);

            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(smtpFrom));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(smtpTo));
            message.setSubject(subject);
            message.setText(body);

            Transport.send(message);
        } catch (Exception e) {
            logger.warning("[DeadLetterHandler] Failed to send SMTP alert \"" + subject + "\": " + e.getMessage());
        }
    }
}
