package dev.denza.dipilotkey.probe;

import static org.junit.Assert.*;
import org.junit.Test;

public class AdbWindowDumpTest {
    @Test
    public void includesTheFirmwareActivityAlias() {
        assertEquals("windows: найдены UsbDebuggingActivityAlias.\n", AdbWindowDump.describe(
                "windows", "Window com.android.systemui/.usb.UsbDebuggingActivityAlias"));
    }
    @Test
    public void reportsComponentsWithoutDumpingExtrasOrTreatingPresenceAsVisibility() {
        String dump = "* Hist #0: ActivityRecord{12 u0 com.android.systemui/.usb.UsbDebuggingActivity t41}\n"
                + "  mVisibleRequested=false\n  key=" + "B".repeat(700) + "\n"
                + "Window #0 Window{13 u0 com.android.systemui/com.android.systemui.usb.UsbDebuggingActivity}";
        String result = AdbWindowDump.describe("activities", dump);
        assertEquals("activities: найдены UsbDebuggingActivity.\n", result);
        assertFalse(result.contains("B".repeat(20)));
        assertFalse(result.contains("visible"));
    }

    @Test
    public void permissionFailureOrIncompleteDumpDoesNotMeanTheComponentIsAbsent() {
        for (String dump : new String[]{null, "", "Permission Denial: can't dump WindowManager",
                "Can't find service: window", "*** SERVICE DUMP TIMEOUT EXPIRED ***"}) {
            assertTrue(AdbWindowDump.describe("windows", dump).contains("НЕИЗВЕСТНО"));
        }
    }

    @Test
    public void unrelatedPackagesAndOrdinaryWindowsAreNotAdbComponents() {
        String dump = "Window #0 unrelated.app/.UsbDebuggingActivity\n"
                + "Window #1 com.android.systemui/.volume.VolumeDialog";
        assertTrue(AdbWindowDump.describe("windows", dump).contains("в этом снимке не найдены"));
    }
}
