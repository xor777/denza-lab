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

    fun allowsNewPress(): Boolean = runCatching {
        val manager = audio ?: return false
        if (manager.mode != AudioManager.MODE_NORMAL) return false
        if (manager.isStreamMute(AudioManager.STREAM_MUSIC)) return false
        if (vendorMute?.invoke(manager, 0) == true) return false
        propertyGet?.invoke(null, "sys.isincall", "false") != "true"
    }.getOrDefault(false)
}
