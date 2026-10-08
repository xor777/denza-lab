package dev.denza.disharebridge;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DiShareBindingStateTest {
    @Test
    public void aDisconnectLeavesTheBindingToUnbind() {
        DiShareBindingState state = new DiShareBindingState();
        state.onBindRequested();
        assertTrue(state.onConnected());

        assertTrue("the owner hears that DiShare died", state.onDisconnected());

        assertTrue("the system still holds the binding", state.release());
    }

    @Test
    public void dishareComingBackAfterARestartDoesNotRunTheOwnersWorkAgain() {
        DiShareBindingState state = new DiShareBindingState();
        state.onBindRequested();
        assertTrue(state.onConnected());
        state.onDisconnected();

        assertFalse(state.onConnected());
    }

    @Test
    public void aConnectionAfterTheOwnerLetGoIsDropped() {
        DiShareBindingState state = new DiShareBindingState();
        state.onBindRequested();
        assertTrue(state.release());

        assertFalse("a stop or a failure came before DiShare answered", state.onConnected());
        assertFalse(state.onDisconnected());
    }

    @Test
    public void aDisconnectAfterReleaseIsNotReported() {
        DiShareBindingState state = new DiShareBindingState();
        state.onBindRequested();
        state.onConnected();
        state.release();

        assertFalse(state.onDisconnected());
    }

    @Test
    public void theBindingIsUnboundOnce() {
        DiShareBindingState state = new DiShareBindingState();
        state.onBindRequested();

        assertTrue(state.release());
        assertFalse(state.release());
    }

    @Test
    public void aRefusedBindIsStillUnbound() {
        // bindService returned false: Android still wants unbindService for that connection.
        DiShareBindingState state = new DiShareBindingState();
        state.onBindRequested();

        assertTrue(state.release());
    }

    @Test
    public void nothingIsOwedWhenBindWasNeverAsked() {
        assertFalse(new DiShareBindingState().release());
    }

    @Test
    public void aDisconnectBeforeAnyConnectionIsNotReported() {
        DiShareBindingState state = new DiShareBindingState();
        state.onBindRequested();

        assertFalse(state.onDisconnected());
        assertTrue("the first connection still counts", state.onConnected());
    }
}
