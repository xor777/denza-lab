package dev.denza.vehiclecontrol.probe;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.*;

/** Recognition uncertainty must request confirmation without changing the parsed command. */
public class VoiceAssessmentTest {
    private static final String USER_PHRASE = "открой правое окно и левую дверь";

    private static JSONObject recognized(String text) throws Exception {
        JSONArray words = new JSONArray();
        for (String word : text.split(" "))
            words.put(new JSONObject().put("word", word).put("conf", .99));
        return new JSONObject().put("text", text).put("result", words);
    }

    private static List<String> commands(VoiceCommands.Assessment assessment) {
        return assessment.plan().commands().stream()
                .map(command -> command.target() + ":" + command.action()).toList();
    }

    @Test public void actualMixedPhraseWithClearWordsIsReadyWithoutConfirmation() throws Exception {
        VoiceCommands.Assessment assessment = VoiceCommands.assessVosk(recognized(USER_PHRASE).toString());
        assertEquals(List.of("fr:open", "door.fl:open"), commands(assessment));
        assertFalse(assessment.needsConfirmation());
        assertEquals(6, assessment.words().size());
        assertTrue(assessment.words().stream().noneMatch(VoiceCommands.WordConfidence::weak));
        assertThrows(UnsupportedOperationException.class, () -> assessment.words().clear());
    }

    @Test public void lowDirectionKeepsExactPlanButRequiresExplicitConfirmationWithEvidence() throws Exception {
        JSONObject result = recognized(USER_PHRASE);
        result.getJSONArray("result").getJSONObject(4).put("conf", .84);
        VoiceCommands.Assessment assessment = VoiceCommands.assessVosk(result.toString());
        assertEquals(List.of("fr:open", "door.fl:open"), commands(assessment));
        assertTrue(assessment.needsConfirmation());
        assertTrue(assessment.reason(), assessment.reason().contains("левую"));
        assertTrue(assessment.reason(), assessment.reason().contains("0.84"));
        VoiceCommands.WordConfidence direction = assessment.words().get(4);
        assertEquals("левую", direction.word());
        assertEquals(.84, direction.confidence(), 0);
        assertEquals(.85, direction.minimum(), 0);
        assertTrue(direction.weak());
        // The original strict entry point remains safe for a caller without a confirmation UI.
        assertThrows(IOException.class, () -> VoiceCommands.parseVosk(result.toString()));
    }

    @Test public void weakConjunctionAlsoNeedsConfirmationRatherThanSilentExecution() throws Exception {
        JSONObject result = recognized(USER_PHRASE);
        result.getJSONArray("result").getJSONObject(3).put("conf", .49);
        VoiceCommands.Assessment assessment = VoiceCommands.assessVosk(result.toString());
        assertEquals(List.of("fr:open", "door.fl:open"), commands(assessment));
        assertTrue(assessment.needsConfirmation());
        assertEquals("и", assessment.words().get(3).word());
        assertEquals(.5, assessment.words().get(3).minimum(), 0);
        assertTrue(assessment.words().get(3).weak());
    }

    @Test public void malformedRecognitionCannotBecomeAConfirmablePlan() throws Exception {
        JSONObject missingScore = recognized(USER_PHRASE);
        missingScore.getJSONArray("result").getJSONObject(4).remove("conf");
        JSONObject mismatch = recognized(USER_PHRASE);
        mismatch.getJSONArray("result").getJSONObject(4).put("word", "правую");
        JSONObject scoreAsString = recognized(USER_PHRASE);
        scoreAsString.getJSONArray("result").getJSONObject(4).put("conf", "0.84");
        JSONObject outsideRange = recognized(USER_PHRASE);
        outsideRange.getJSONArray("result").getJSONObject(4).put("conf", -0.01);
        String notANumber = recognized(USER_PHRASE).toString().replaceFirst("0\\.99", "NaN");
        for (String input : List.of(missingScore.toString(), mismatch.toString(), scoreAsString.toString(),
                outsideRange.toString(), notANumber, "{}", recognized(USER_PHRASE) + "{}"))
            assertThrows(input, IOException.class, () -> VoiceCommands.assessVosk(input));
    }

    @Test public void unknownOrNegatedTailIsRejectedEvenWithLowConfidence() throws Exception {
        for (String phrase : List.of("открой правое окно и левую дверь неизвестно",
                "открой правое окно и не открывай левую дверь", "открой правое окно и открой люк")) {
            JSONObject result = recognized(phrase);
            for (int i = 0; i < result.getJSONArray("result").length(); i++)
                result.getJSONArray("result").getJSONObject(i).put("conf", .4);
            assertThrows(phrase, IOException.class, () -> VoiceCommands.assessVosk(result.toString()));
        }
    }

    @Test public void grammarCoversRepresentativeMixedDirectionTransitions() {
        List<String> grammar = VoiceCommands.phrases();
        for (String phrase : List.of(USER_PHRASE,
                "закрой левое окно и правую дверь",
                "открой переднее левое окно и заднюю правую дверь",
                "закрой правую дверь и левое окно")) {
            if (grammar.contains(phrase)) continue;
            String[] words = phrase.split(" ");
            for (int i = 0; i < words.length - 1; i++) {
                String transition = " " + words[i] + " " + words[i + 1] + " ";
                assertTrue(phrase + ": missing " + transition,
                        grammar.stream().anyMatch(example -> (" " + example + " ").contains(transition)));
            }
        }
    }
}
