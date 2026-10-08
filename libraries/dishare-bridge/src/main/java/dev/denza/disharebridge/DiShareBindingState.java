package dev.denza.disharebridge;

/**
 * What one {@code bindService} to DiShare owes, kept apart from whether DiShare is connected.
 *
 * <p>Android keeps a {@code BIND_AUTO_CREATE} binding registered after
 * {@code onServiceDisconnected} and calls {@code onServiceConnected} again once the service is
 * running again. A disconnect is therefore not the end of the binding: it still has to be
 * unbound, and a connection that arrives after the owner let go must not run the owner's work
 * (for the projection bridge that work is a share start). Pure, so the rules are tested without
 * a car; {@link DiShareBinding} applies them to a real {@code ServiceConnection}.
 */
final class DiShareBindingState {
    private boolean bindRequested;
    private boolean connected;
    private boolean everConnected;
    private boolean released;

    /** {@code bindService} was called, whatever it returned: either way it has to be unbound. */
    void onBindRequested() {
        bindRequested = true;
    }

    /**
     * Returns whether this connection reaches the owner. Only the first one does, and only while
     * the owner still holds the binding; a later one is DiShare coming back after a restart.
     */
    boolean onConnected() {
        if (released || everConnected) {
            return false;
        }
        everConnected = true;
        connected = true;
        return true;
    }

    /** Returns whether the owner has to hear about it: it was connected and still holds on. */
    boolean onDisconnected() {
        boolean live = connected && !released;
        connected = false;
        return live;
    }

    /** Returns whether {@code unbindService} is owed now; true at most once. */
    boolean release() {
        if (released) {
            return false;
        }
        released = true;
        connected = false;
        return bindRequested;
    }
}
