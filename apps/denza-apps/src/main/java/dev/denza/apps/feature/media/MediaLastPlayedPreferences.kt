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
 * A package name is all that is stored, for any package including the vehicle's own. The timestamp
 * is written beside it and deliberately not read: see [MediaLastPlayedStore].
 */
internal class MediaLastPlayedPreferences(context: Context) : MediaLastPlayedStore {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun lastPlayed(): MediaLastPlayed? {
        val packageName = preferences.getString(PACKAGE, null)?.takeIf(String::isNotBlank)
            ?: return null
        return MediaLastPlayed(packageName, preferences.getLong(AT, 0L))
    }

    @SuppressLint("UseKtx")
    override fun remember(packageName: String) {
        if (packageName.isBlank()) return
        preferences.edit()
            .putString(PACKAGE, packageName)
            .putLong(AT, System.currentTimeMillis())
            .apply()
    }

    private companion object {
        const val PREFERENCES = "media_resume"
        const val PACKAGE = "last_played_package"
        const val AT = "last_played_at"
    }
}
