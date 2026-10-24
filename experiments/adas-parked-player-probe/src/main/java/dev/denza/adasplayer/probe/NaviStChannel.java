package dev.denza.adasplayer.probe;

/**
 * The stock navigation's second channel: {@code SETTING_MPC_IFC_NAVI_ST_SET} (device 1023,
 * {@code 0x4CA0004B}), written by {@code PlatformApiImpl.setNaviSt} (GPS navigation -> 2, Cruise ->
 * 1, stop/destroy -> 0). Outside the map SOME/IP feed; see research/adas-bridge/README.md,
 * "A second stock channel needs tracing".
 *
 * <p>Writing device-1023 settings needs a {@code BYDAUTO_*} permission the app UID does not hold;
 * the stock reaches it, and {@code HudNativeSpeedLimit} reaches device 1023 only through the shell.
 * So this channel is enabled only when the running process can actually perform the write. When it
 * cannot, {@link #enabled()} is false and the H6 step is recorded as disabled, with no invented
 * path (a read before, a restore after; the vendor setting keeps state).
 */
interface NaviStChannel {
    boolean enabled();

    /** Why the channel is disabled, for the record (e.g. the app UID lacks the setting permission). */
    String reason();

    /** The current NaviSt value, read before writing so it can be restored, or null if unreadable. */
    Integer read();

    boolean write(int value);
}
