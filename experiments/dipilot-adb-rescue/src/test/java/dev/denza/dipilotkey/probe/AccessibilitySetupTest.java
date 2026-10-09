package dev.denza.dipilotkey.probe;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class AccessibilitySetupTest {
    @Test
    public void enablingPreservesOtherServicesAndUsesCurrentUser() throws Exception {
        List<String> writes = new ArrayList<>();
        String otherServices = "other.app/.First:second.app/second.app.Second";
        int[] reads = {0};
        AccessibilitySetup.enable(command -> {
            assertTrue(command.startsWith("settings --user current "));
            if (command.contains(" put ")) {
                writes.add(command);
                return "";
            }
            if (command.endsWith("enabled_accessibility_services")) {
                return reads[0]++ == 0 ? otherServices : otherServices + ":" + AccessibilitySetup.COMPONENT;
            }
            return "1";
        });
        assertEquals(List.of("settings --user current put secure enabled_accessibility_services '"
                + otherServices + ":" + AccessibilitySetup.COMPONENT
                + "' && settings --user current put secure accessibility_enabled 1"), writes);
    }

    @Test
    public void existingFullAndShortComponentAreNotDuplicated() {
        assertEquals(AccessibilitySetup.COMPONENT,
                AccessibilitySetup.withProbe(AccessibilitySetup.COMPONENT));
        String shortList = "other.app/.Service:dev.denza.dipilotkey.probe/.PromptClicker";
        assertEquals(shortList, AccessibilitySetup.withProbe(shortList));
        assertFalse(AccessibilitySetup.containsProbe("dev.denza.dipilotkey.probe/.Other"));
    }

    @Test
    public void emptyOrNullSettingEnablesOnlyThisService() {
        assertEquals(AccessibilitySetup.COMPONENT, AccessibilitySetup.withProbe("null"));
        assertEquals(AccessibilitySetup.COMPONENT, AccessibilitySetup.withProbe(""));
        assertEquals(AccessibilitySetup.COMPONENT, AccessibilitySetup.withProbe(null));
    }

    @Test
    public void unreadableOrMalformedListNeverOverwritesExistingSettings() {
        for (String response : List.of("Error: permission denied", "Unknown command", "bad'component", "bad\nreply")) {
            List<String> writes = new ArrayList<>();
            assertThrows(IOException.class, () -> AccessibilitySetup.enable(command -> {
                if (command.contains(" put ")) {
                    writes.add(command);
                }
                return response;
            }));
            assertTrue(writes.isEmpty());
        }
    }

    @Test
    public void rejectedWriteIsReportedAsFailure() {
        assertThrows(IOException.class, () -> AccessibilitySetup.enable(command ->
                command.contains(" put ") ? "SecurityException: Permission denial" : "null"));
    }

    @Test
    public void writeMustBeConfirmedByBothSecureSettings() {
        assertThrows(IOException.class, () -> AccessibilitySetup.enable(command ->
                command.contains(" put ") ? "" : "null"));
        assertThrows(IOException.class, () -> AccessibilitySetup.enable(command -> {
            if (command.contains(" put ")) {
                return "";
            }
            return command.endsWith("enabled_accessibility_services") ? AccessibilitySetup.COMPONENT : "0";
        }));
    }
}
