package dev.denza.apps.feature.media

import android.content.Context
import android.media.AudioManager

/** Leaves call and mute handling with the firmware; never changes audio focus or volume. */
class MediaButtonEnvironment(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val vendorMute = runCatching {
        AudioManager::class.java.getMethod("getMuteState", Int::class.javaPrimitiveType)
    }.getOrNull()
    private val propertyGet = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java, String::class.java)
    }.getOrNull()

    /**
     * The one call site the filter has. It notes the reason on the way past, because the press it
     * is about to refuse is handed to stock routing and leaves no other trace of why.
     */
    fun allowsNewPress(): Boolean =
        pressGuard().also(MediaKeyDiagnostics::noteGuard) == MediaKeyGuard.ALLOWED

    /** The same four readings in the same order, saying which one answered. */
    fun pressGuard(): MediaKeyGuard = runCatching {
        val manager = audio ?: return MediaKeyGuard.UNAVAILABLE
        if (manager.mode != AudioManager.MODE_NORMAL) return MediaKeyGuard.AUDIO_MODE
        if (manager.isStreamMute(AudioManager.STREAM_MUSIC)) return MediaKeyGuard.STREAM_MUTE
        if (vendorMute?.invoke(manager, 0) == true) return MediaKeyGuard.VENDOR_MUTE
        if (propertyGet?.invoke(null, "sys.isincall", "false") == "true") {
            return MediaKeyGuard.IN_CALL
        }
        MediaKeyGuard.ALLOWED
    }.getOrDefault(MediaKeyGuard.UNAVAILABLE)
}
