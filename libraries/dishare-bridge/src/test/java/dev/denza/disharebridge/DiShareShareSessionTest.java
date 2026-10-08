package dev.denza.disharebridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DiShareShareSessionTest {
    /** The order the car showed on 2026-09-24: true during the start, false at the 605. */
    @Test
    public void dishareDroppingTheReceiverEndsTheShare() {
        DiShareShareSession session = active();
        assertFalse(session.mirrorClientChanged(true));

        assertTrue("the settle period starts", session.mirrorClientChanged(false));
        assertEquals(DiShareShareSession.Phase.ACTIVE, session.phase());

        assertTrue(session.settled());
        assertEquals(DiShareShareSession.Phase.ENDED, session.phase());
    }

    @Test
    public void theEndIsReportedOnce() {
        DiShareShareSession session = active();
        session.mirrorClientChanged(true);
        session.mirrorClientChanged(false);
        assertTrue(session.settled());

        assertFalse(session.settled());
        assertFalse(session.mirrorClientChanged(false));
        assertFalse(session.dishareLost());
    }

    /**
     * Casting the same app again: the new client hears the previous share's true at creation,
     * then that share's teardown, then its own share.
     */
    @Test
    public void castingTheSameAppAgainIsNotAnEnd() {
        DiShareShareSession session = new DiShareShareSession();
        session.starting();
        session.mirrorClientChanged(true);
        assertTrue(session.started());

        assertTrue(session.mirrorClientChanged(false));
        assertFalse(session.mirrorClientChanged(true));

        assertFalse("the timer armed by the teardown finds nothing to end", session.settled());
        assertEquals(DiShareShareSession.Phase.ACTIVE, session.phase());
    }

    @Test
    public void aFalseBeforeAnyTrueIsNotAnEnd() {
        DiShareShareSession session = active();

        assertFalse(session.mirrorClientChanged(false));
        assertFalse(session.settled());
        assertEquals(DiShareShareSession.Phase.ACTIVE, session.phase());

        session.mirrorClientChanged(true);
        assertTrue(session.mirrorClientChanged(false));
    }

    @Test
    public void flagsWhileStartingNeverEndTheShare() {
        DiShareShareSession session = new DiShareShareSession();
        session.starting();

        session.mirrorClientChanged(true);
        assertFalse(session.mirrorClientChanged(false));
        assertTrue(session.started());

        assertFalse("the earlier share's false was already spent", session.settled());
        assertFalse(session.mirrorClientChanged(false));
    }

    @Test
    public void aSecondFalseRearmsTheSettle() {
        DiShareShareSession session = active();
        session.mirrorClientChanged(true);
        session.mirrorClientChanged(false);
        session.mirrorClientChanged(true);

        assertTrue(session.mirrorClientChanged(false));
        assertTrue(session.endPending());
    }

    @Test
    public void ourStopCancelsAPendingEnd() {
        DiShareShareSession session = active();
        session.mirrorClientChanged(true);
        session.mirrorClientChanged(false);

        session.stopped();

        assertFalse(session.settled());
        assertFalse(session.dishareLost());
        assertEquals(DiShareShareSession.Phase.STOPPED, session.phase());
    }

    @Test
    public void nothingDiShareSaysAfterOurStopMatters() {
        DiShareShareSession session = active();
        session.mirrorClientChanged(true);
        session.stopped();

        assertFalse(session.mirrorClientChanged(false));
        assertFalse(session.settled());
    }

    @Test
    public void dishareDyingEndsAnActiveShareAtOnce() {
        DiShareShareSession session = active();
        session.mirrorClientChanged(true);
        session.mirrorClientChanged(false);

        assertTrue(session.dishareLost());
        assertEquals(DiShareShareSession.Phase.ENDED, session.phase());
        assertFalse("ended once", session.settled());
    }

    @Test
    public void dishareDyingWhileStartingIsAFailedStartNotAnEnd() {
        DiShareShareSession session = new DiShareShareSession();
        session.starting();

        assertFalse(session.dishareLost());
        assertTrue(session.failed());
        assertFalse("one failure per start", session.failed());
    }

    @Test
    public void aFailedStartNeverEnds() {
        DiShareShareSession session = new DiShareShareSession();
        session.starting();
        session.failed();

        assertFalse(session.started());
        assertFalse(session.mirrorClientChanged(true));
        assertFalse(session.mirrorClientChanged(false));
        assertFalse(session.settled());
        assertFalse(session.dishareLost());
    }

    @Test
    public void aLateTimeoutAfterTheEndIsNotAFailure() {
        DiShareShareSession session = active();
        session.dishareLost();

        assertFalse(session.failed());
        assertFalse(session.started());
    }

    @Test
    public void aNewStartForgetsThePreviousShare() {
        DiShareShareSession session = active();
        session.mirrorClientChanged(true);
        session.stopped();

        session.starting();
        session.started();

        assertFalse(session.mirrorClientChanged(false));
    }

    private static DiShareShareSession active() {
        DiShareShareSession session = new DiShareShareSession();
        session.starting();
        assertTrue(session.started());
        return session;
    }
}
