package dev.denza.apps.feature.adb

import android.content.Context
import android.provider.Settings

/**
 * Android's own ADB switch (`Settings.Global.adb_enabled`) as this process reads it.
 *
 * The handshake alone cannot tell the two failing states apart. A car whose switch is off can
 * still answer on the local endpoint and still refuse this key, which looks exactly like an
 * untrusted key on a car where authorization works — and the owner is then told to approve a
 * system prompt that Android will never render. Any app can read this flag from its own process,
 * with no ADB and no permission, so the product reads it before it decides which state to show.
 */
enum class AdbSystemSwitch {
    /** Non-zero: a submitted public key can still reach a prompt. */
    ENABLED,

    /** Zero: nothing that is enqueued will ever be shown, however often it is enqueued. */
    DISABLED,

    /**
     * The flag could not be read, or this car has never stored a value for it. That is an absence
     * of evidence, not evidence of an off switch, and it must never be reported as one.
     */
    UNKNOWN,
}

/** Reads [AdbSystemSwitch] from the application's own process. */
object AdbSystemSwitchReader {
    fun read(context: Context): AdbSystemSwitch = classify(rawValue(context))

    /** The value mapping, kept free of Android so the fallback is unit-testable. */
    internal fun classify(raw: Int?): AdbSystemSwitch = when {
        raw == null -> AdbSystemSwitch.UNKNOWN
        raw != 0 -> AdbSystemSwitch.ENABLED
        else -> AdbSystemSwitch.DISABLED
    }

    // A row that was never written throws SettingNotFoundException, and a settings provider can
    // fail in ways this classification has no business crashing the app over. Every failure means
    // "nothing was learned", never "the switch is off".
    private fun rawValue(context: Context): Int? = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED)
    } catch (_: Exception) {
        null
    }
}
