package dev.denza.adbrescue.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Covers the one part of the probe that draws a conclusion.
 *
 * <p>Everything else it does is a reading printed verbatim. The verdict is the sentence an owner
 * reads out over the phone, and the case that matters is the switched-off car: telling that person
 * to press a button would send them after a dialog the system will never draw.
 */
public class RescueRunnerTest {
    @Test
    public void switchedOffCarIsNotToldToRequest() {
        String verdict = RescueRunner.verdict(
                RescueRunner.Access.AUTHORIZATION_REQUIRED, false, true, null);
        assertTrue(verdict.contains("ВЫКЛЮЧЕНА"));
    }

    @Test
    public void unreadableSwitchKeepsTheRequestPath() {
        // An unreadable flag is an absence of evidence, never evidence of an off switch: a working
        // car must not be sent to a service centre because one settings row could not be read.
        String verdict = RescueRunner.verdict(
                RescueRunner.Access.AUTHORIZATION_REQUIRED, false, false, null);
        assertTrue(verdict.contains("НЕ РАЗБЛОКИРОВАНА"));
    }

    @Test
    public void enabledSwitchAsksForOneRequest() {
        String verdict = RescueRunner.verdict(
                RescueRunner.Access.AUTHORIZATION_REQUIRED, true, true, null);
        assertTrue(verdict.contains("НЕ РАЗБЛОКИРОВАНА"));
    }

    @Test
    public void silentEndpointNeverClaimsAKeyWasSent() {
        String verdict = RescueRunner.verdict(RescueRunner.Access.UNAVAILABLE, true, true, null);
        assertTrue(verdict.contains("НЕ ОТВЕЧАЕТ"));
    }

    @Test
    public void trustedCarOffersTheRepair() {
        assertEquals(
                "ДОСТУП ЕСТЬ. Можно чинить Denza Apps.",
                RescueRunner.verdict(RescueRunner.Access.TRUSTED, true, true, null));
    }

    /**
     * The two readings of BYD's factory flag are different problems with different remedies.
     *
     * <p>This firmware's SystemUI auto-approves every ADB key when the flag reads 1 and draws no
     * dialog at all, so a refused key under a raised flag means the dialog is never started - and
     * a refused key under a lowered one means the car was never unlocked in the sense that
     * actually grants ADB here. Telling those apart is the whole value of reading it.
     */
    @Test
    public void raisedFactoryFlagWithARefusedKeyIsItsOwnState() {
        String verdict = RescueRunner.verdict(
                RescueRunner.Access.AUTHORIZATION_REQUIRED, true, true, "1");
        assertTrue(verdict.contains("ЗАВОДСКОЙ РЕЖИМ ВКЛЮЧЁН"));
    }

    @Test
    public void loweredFactoryFlagNamesTheRealRemedy() {
        String verdict = RescueRunner.verdict(
                RescueRunner.Access.AUTHORIZATION_REQUIRED, true, true, "0");
        assertTrue(verdict.contains("НЕ РАЗБЛОКИРОВАНА"));
        assertTrue(verdict.contains("persist.sys.factory.version.flag.config"));
    }

    /** An unread flag must never be reported as a lowered one. */
    @Test
    public void unreadFactoryFlagDoesNotClaimTheCarIsLocked() {
        String verdict = RescueRunner.verdict(
                RescueRunner.Access.AUTHORIZATION_REQUIRED, true, true, "?");
        assertTrue(verdict.contains("НЕ РАЗБЛОКИРОВАНА"));
    }
}
