package dev.denza.apps.feature.media

import android.annotation.SuppressLint
import android.content.Context

/**
 * The one fact the resume policy keeps between runs: which package last actually played.
 *
 * It has to outlive the process. Our accessibility service is restarted by every APK update and by
 * quickboot, and the player's own process dies whenever the system needs the memory - and each of
 * those used to mean the first press of the wheel afterwards opened the stock media center,
 * because nothing in memory remembered what the driver had been listening to.
 *
 * A package name is all that is stored, for any package including the vehicle's own. Every PLAYING
 * writes it, so a repeat of the same package changes nothing and `SharedPreferences` leaves the
 * file alone. Until 2026-10-09 a `last_played_at` timestamp went beside it, which nothing read and
 * which made every one of those writes reach the disk; a car updated from such a build keeps the
 * stale key, unread.
 */
internal class MediaLastPlayedPreferences(context: Context) : MediaLastPlayedStore {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun lastPlayed(): MediaLastPlayed? {
        val packageName = preferences.getString(PACKAGE, null)?.takeIf(String::isNotBlank)
            ?: return null
        return MediaLastPlayed(packageName)
    }

    @SuppressLint("UseKtx")
    override fun remember(packageName: String) {
        if (packageName.isBlank()) return
        preferences.edit().putString(PACKAGE, packageName).apply()
    }

    private companion object {
        const val PREFERENCES = "media_resume"
        const val PACKAGE = "last_played_package"
    }
}
