package dev.denza.dipilotkey.probe;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Whole-run regressions with a virtual clock; no real authorization requests are made. */
public class RescueFlowTest {
    @Test
    public void ownersPhotoScenarioEnablesServiceAndScansDespiteTrustedShellAndUnknownQueue() {
        Device device = new Device();
        PortRescue.Result result = new PortRescue(device).run(snapshot -> {
            if (snapshot.contains("Первая проверка shell: ДА")
                    && !device.events.contains("display-first-check")) {
                device.events.add("display-first-check");
            }
        });

        assertFalse(result.needsServiceSettings);
        assertTrue(device.events.indexOf("check") < device.events.indexOf("enable"));
        assertTrue(device.events.indexOf("display-first-check") < device.events.indexOf("enable"));
        assertTrue(device.events.indexOf("enable") < device.events.indexOf("arm"));
        assertEquals(1, device.arms);
        assertEquals(30, device.scans);
        assertEquals(0, device.allows);
        assertEquals(3, device.checks);
        assertTrue(result.report.contains("Поиск окон завершён"));
        assertTrue(result.report.contains("Очередь после (последнее событие журнала): НЕИЗВЕСТНО"));
        assertTrue(result.report.contains("Итоговая повторная проверка shell: ДА"));
        assertFalse(device.armed);
    }

    @Test
    public void historicalEmptyQueueAlsoDoesNotSkipWindowSearch() {
        Device device = new Device();
        device.log = "adbd_auth: no prompts to send";
        new PortRescue(device).run(snapshot -> { });
        assertEquals(30, device.scans);
        assertEquals(0, device.allows);
    }

    @Test
    public void serviceIsNotConsideredReadyUntilItActuallyConnects() {
        Device device = new Device();
        device.connectionDelay = 1500;
        new PortRescue(device).run(snapshot -> { });
        assertEquals(1500, device.armTime);
        assertEquals(13500, device.clock);
    }

    @Test
    public void rejectedServiceSetupRequiresSettingsAndNeverArmsClicks() {
        Device device = new Device();
        device.enableError = new IOException("Permission denial");
        PortRescue.Result result = new PortRescue(device).run(snapshot -> { });
        assertTrue(result.needsServiceSettings);
        assertEquals(0, device.arms);
        assertEquals(0, device.allows);
        assertTrue(result.report.contains("Автоматическое включение не удалось"));
        assertTrue(result.report.contains("Итоговая повторная проверка shell: ДА"));
    }

    @Test
    public void enabledButDisconnectedServiceTimesOutWithoutPretendingToScan() {
        Device device = new Device();
        device.enabled = true;
        device.connectionAt = Long.MAX_VALUE;
        PortRescue.Result result = new PortRescue(device).run(snapshot -> { });
        assertTrue(result.needsServiceSettings);
        assertEquals(5000, device.clock);
        assertEquals(0, device.arms);
        assertTrue(result.report.contains("Служба подключена: НЕТ"));
    }

    @Test
    public void noTrustedShellAndNoServiceRequiresManualSetup() {
        Device device = new Device();
        device.trusted = false;
        PortRescue.Result result = new PortRescue(device).run(snapshot -> { });
        assertTrue(result.needsServiceSettings);
        assertFalse(device.events.contains("enable"));
        assertEquals(0, device.arms);
        assertEquals(2, device.checks);
        assertTrue(result.report.contains("этот ключ ещё не доверен"));
    }

    @Test
    public void clicksKeepScanningWhenShellWasAlreadyTrusted() {
        Device device = new Device();
        device.plannedClicks = List.of(400L, 1600L, 2800L);
        PortRescue.Result result = new PortRescue(device).run(snapshot -> { });
        assertEquals(3, device.clicks);
        assertEquals(30, device.scans);
        assertEquals(6, device.checks);
        assertTrue(result.report.contains("Shell после нажатий 3: ДА"));
    }

    @Test
    public void earlierUnrelatedClickAndLaterTrustedShellDoNotEndWindowScan() {
        Device device = new Device();
        device.enabled = true;
        device.connectionAt = 0;
        device.trusted = false;
        device.trustAfterClicks = 2;
        device.plannedClicks = List.of(400L, 1600L, 2800L);
        PortRescue.Result result = new PortRescue(device).run(snapshot -> { });
        assertEquals(3, device.clicks);
        assertEquals(30, device.scans);
        assertTrue(result.report.contains("Shell после нажатий 1: НЕТ"));
        assertTrue(result.report.contains("Shell после нажатий 2: ДА"));
        assertTrue(result.report.contains("Shell после нажатий 3: ДА"));
    }

    @Test
    public void windowApprovalsStopAtEightAndAreDisarmedForBinderApproval() {
        Device device = new Device();
        device.plannedClicks = List.of(400L, 800L, 1200L, 1600L, 2000L, 2400L, 2800L, 3200L, 3600L);
        device.log = "10-09 14:00:00.000 Logging key " + "A".repeat(200) + ", state = 1";
        new PortRescue(device).run(snapshot -> { });
        assertEquals(PromptDecision.MAX_CLICKS, device.clicks);
        assertEquals(1, device.allows);
        assertFalse(device.binderWhileArmed);
        assertFalse(device.armed);
    }

    @Test
    public void everyScanHasAnIndependentDeadlineEvenIfShellCheckBlocks() {
        Device device = new Device();
        device.plannedClicks = List.of(400L);
        device.slowCheckAfterClick = true;
        new PortRescue(device).run(snapshot -> { });
        assertEquals(12000, device.armBudget);
        assertEquals(1, device.clicks);
        assertFalse(device.armed);
    }

    @Test
    public void finalShellFailureIsVisibleAfterAnInitiallySuccessfulConnection() {
        Device device = new Device();
        device.failCheckNumber = 3;
        PortRescue.Result result = new PortRescue(device).run(snapshot -> { });
        assertTrue(result.report.contains("Первая проверка shell: ДА"));
        assertTrue(result.report.contains("Итоговая повторная проверка shell: НЕТ"));
        assertTrue(result.report.contains("контрольную строку"));
    }

    @Test
    public void interruptedRunAlwaysDisarmsAndDoesNotApproveThroughBinder() {
        Device device = new Device();
        device.enabled = true;
        device.connectionAt = 0;
        device.interruptPause = true;
        try {
            PortRescue.Result result = new PortRescue(device).run(snapshot -> { });
            assertTrue(result.report.contains("Остановлено"));
            assertFalse(device.armed);
            assertEquals(0, device.allows);
            assertFalse(result.needsServiceSettings);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static final class Device implements RescueDevice {
        final List<String> events = new ArrayList<>();
        boolean trusted = true;
        boolean enabled;
        boolean armed;
        boolean binderWhileArmed;
        boolean interruptPause;
        boolean slowCheckAfterClick;
        int trustAfterClicks = Integer.MAX_VALUE;
        int failCheckNumber;
        int checks;
        int clicks;
        int scans;
        int arms;
        int allows;
        long clock;
        long connectionAt = Long.MAX_VALUE;
        long connectionDelay = 1000;
        long armTime;
        long armBudget;
        String log = "adbd: adb client authorized";
        Exception enableError;
        List<Long> plannedClicks = List.of();

        @Override public String shell(String command, int timeoutMs) throws Exception {
            if (command.startsWith("printf ")) {
                events.add("check");
                checks++;
                if (checks == failCheckNumber) {
                    return "";
                }
                if (slowCheckAfterClick && clicks > 0) {
                    clock += 8000;
                }
                if (!trusted && clicks < trustAfterClicks) {
                    throw new IOException("ADB authorization pending");
                }
                return "DENZA_DIPILOT_OK";
            }
            if (command.equals(QueueDrain.LOGCAT)) {
                events.add("read-queue");
                return log;
            }
            if (command.startsWith("service call adb ")) {
                binderWhileArmed |= armed;
                allows++;
                log = "adbd_auth: no prompts to send";
                return "Result: Parcel(00000000 '....')";
            }
            if (command.equals(QueueDrain.DUMP_KEYS)) {
                return "(has extras)";
            }
            throw new AssertionError("Unexpected device operation");
        }
        @Override public String systemSwitch() { return "включена (1)"; }
        @Override public boolean clickerEnabled() { return enabled; }
        @Override public boolean clickerConnected() { return enabled && clock >= connectionAt; }
        @Override public void enableClicker() throws Exception {
            events.add("enable");
            if (enableError != null) {
                throw enableError;
            }
            enabled = true;
            connectionAt = clock + connectionDelay;
        }
        @Override public void resetClicks() { armed = false; clicks = scans = 0; }
        @Override public void armClicks(long budgetMs) {
            assertTrue(clickerConnected());
            events.add("arm");
            armed = true;
            arms++;
            armTime = clock;
            armBudget = budgetMs;
        }
        @Override public void disarmClicks() { armed = false; }
        @Override public int clicks() { return clicks; }
        @Override public String clickDiagnostics() {
            return "Проверок окон: " + scans + "\nACTION_CLICK принят: " + clicks + "\n";
        }
        @Override public long now() { return clock; }
        @Override public void pause(long millis) throws InterruptedException {
            if (interruptPause) {
                throw new InterruptedException();
            }
            clock += millis;
            if (armed && clock - armTime <= armBudget) {
                scans++;
                if (clicks < plannedClicks.size() && clicks < PromptDecision.MAX_CLICKS
                        && clock - armTime >= plannedClicks.get(clicks)) {
                    clicks++;
                }
            }
        }
    }
}
