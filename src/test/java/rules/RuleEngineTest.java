package rules;

import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import model.AlarmRecord;
import normalize.HandleSynthesizer;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleEngineTest {

    private static MimeMessage loadFixture(String relativePath) throws Exception {
        Session session = Session.getDefaultInstance(new Properties());
        try (InputStream input = Files.newInputStream(Path.of("src/test/java/fixtures", relativePath))) {
            return new MimeMessage(session, input);
        }
    }

    private static RuleSet loadNorthCampusRule() throws Exception {
        return RuleLoader.parseFile(Path.of("rules/north-campus.yaml"));
    }

    private static String bodyText(MimeMessage message) throws Exception {
        Object content = message.getContent();
        return content instanceof String ? (String) content : String.valueOf(content);
    }

    @Test
    void northCampusOffnormalFixtureProducesExpectedAlarmRecord() throws Exception {
        MimeMessage message = loadFixture("north-campus/offnormal.eml");
        RuleSet rule = loadNorthCampusRule();
        RuleEngine engine = new RuleEngine("UTC");

        String from = ((InternetAddress) message.getFrom()[0]).getAddress();
        String subject = message.getSubject();
        String body = bodyText(message);
        String messageId = message.getMessageID();

        assertNotNull(RuleEngine.match(List.of(rule), from, subject),
                "north-campus.yaml should match this fixture's From/Subject");

        List<AlarmRecord> records = engine.extract(rule, subject, body, from, Map.of(), messageId);
        assertEquals(1, records.size());
        AlarmRecord record = records.get(0);

        assertEquals("North Campus", record.estate);
        assertEquals("AHU-3 Supply Temp", record.pointName);
        assertEquals("91.2 F", record.pointValue);
        assertEquals("offnormal", record.alarmState);
        assertEquals("High supply air temperature", record.message);
        // 14:05 America/Chicago -> 19:05 UTC per the spec's worked example.
        assertEquals("2026-07-29 19:05:00", record.timestamp);
        assertEquals("", record.normalTime);
        assertEquals("", record.offNormalTime);

        String expectedHandle = HandleSynthesizer.synthesize("north-campus", List.of("North Campus", "AHU-3 Supply Temp"));
        assertEquals(expectedHandle, record.handle);
        assertTrue(record.handle.startsWith("north-campus:"));
    }

    @Test
    void sameFixtureProcessedTwiceProducesIdenticalHandle() throws Exception {
        RuleSet rule = loadNorthCampusRule();
        RuleEngine engine = new RuleEngine("UTC");

        MimeMessage message1 = loadFixture("north-campus/offnormal.eml");
        MimeMessage message2 = loadFixture("north-campus/offnormal.eml");
        String from = ((InternetAddress) message1.getFrom()[0]).getAddress();

        AlarmRecord record1 = engine.extract(rule, message1.getSubject(), bodyText(message1), from,
                Map.of(), message1.getMessageID()).get(0);
        AlarmRecord record2 = engine.extract(rule, message2.getSubject(), bodyText(message2), from,
                Map.of(), message2.getMessageID()).get(0);

        assertEquals(record1.handle, record2.handle);
    }

    @Test
    void belowMinBlocksTreatedAsUnmatchedNotEmptySuccess() {
        RuleSet rule = new RuleSet();
        rule.clientId = "test-client";
        rule.enabled = true;
        rule.match = new RuleSet.MatchSpec();
        rule.multiAlarm = new RuleSet.MultiAlarmSpec();
        rule.multiAlarm.blockSeparatorRegex = "(?=^Point:\\s)";
        rule.multiAlarm.minBlocks = 1;
        rule.fields = new LinkedHashMap<>();

        RuleEngine engine = new RuleEngine("UTC");
        List<AlarmRecord> records = engine.extract(
                rule, "subject", "no matching separator anywhere in this body", "from@x.com", Map.of(), "msg-1");

        assertTrue(records.isEmpty(), "a stale/changed-format body should dead-letter, not produce an empty success");
    }
}
