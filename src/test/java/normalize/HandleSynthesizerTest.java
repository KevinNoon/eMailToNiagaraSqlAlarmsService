package normalize;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandleSynthesizerTest {

    @Test
    void sameInputsProduceSameHandle() {
        String h1 = HandleSynthesizer.synthesize("north-campus", List.of("North Campus", "AHU-3 Supply Temp"));
        String h2 = HandleSynthesizer.synthesize("north-campus", List.of("North Campus", "AHU-3 Supply Temp"));
        assertEquals(h1, h2);
    }

    @Test
    void differentPointNameProducesDifferentHandle() {
        String h1 = HandleSynthesizer.synthesize("north-campus", List.of("North Campus", "AHU-3 Supply Temp"));
        String h2 = HandleSynthesizer.synthesize("north-campus", List.of("North Campus", "AHU-4 Supply Temp"));
        assertNotEquals(h1, h2);
    }

    @Test
    void differentClientIdProducesDifferentFullHandle() {
        String h1 = HandleSynthesizer.synthesize("north-campus", List.of("North Campus", "AHU-3 Supply Temp"));
        String h2 = HandleSynthesizer.synthesize("south-campus", List.of("North Campus", "AHU-3 Supply Temp"));
        assertNotEquals(h1, h2);
        assertTrue(h1.startsWith("north-campus:"));
        assertTrue(h2.startsWith("south-campus:"));
    }

    @Test
    void handleFormatMatchesClientIdColonHex24Contract() {
        String handle = HandleSynthesizer.synthesize("north-campus", List.of("North Campus", "AHU-3 Supply Temp"));
        assertTrue(Pattern.matches("north-campus:[0-9a-f]{24}", handle), "unexpected handle format: " + handle);
    }

    @Test
    void nullFieldValuesTreatedAsEmptyString() {
        String h1 = HandleSynthesizer.synthesize("c", List.of("a", "", "b"));
        String h2 = HandleSynthesizer.synthesize("c", java.util.Arrays.asList("a", null, "b"));
        assertEquals(h1, h2);
    }
}
