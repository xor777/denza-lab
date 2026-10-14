package dev.denza.hud.accountcheck;

/** Inert registration fixture: no account data, login or network access. */
public final class OwnerService extends android.app.Service {
    @Override public android.os.IBinder onBind(android.content.Intent intent) { return null; }
}
