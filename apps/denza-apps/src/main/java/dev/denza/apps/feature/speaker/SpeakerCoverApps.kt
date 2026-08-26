package dev.denza.apps.feature.speaker

/**
 * Applications whose foreground transition is a useful pre-roll hint.
 *
 * This is not the playback detector: MediaSession and the output mix cover
 * applications outside this list. The list only buys the motors time to extend
 * before a known player emits its first sample.
 */
object SpeakerCoverApps {
    val eagerPackages: Set<String> = linkedSetOf(
        // Stock BYD players.
        "com.byd.mediacenter",
        "com.byd.videoplay",
        "com.byd.minikaraoke",

        // Music and general-purpose players.
        "ru.yandex.music",
        "com.google.android.apps.youtube.music",
        "com.spotify.music",
        "com.apple.android.music",
        "com.uma.musicvk",
        "org.videolan.vlc",

        // Video and streaming.
        "com.google.android.youtube",
        "com.vk.vkvideo",
        "ru.rutube.app",
        "ru.kinopoisk",
        "ru.ivi.client",
        "ru.rt.video.app.mobile",
        "ru.mts.mtstv",
        "ru.start.androidmobile",
        "gpm.tnt_premier",
        "com.netflix.mediaclient",
        "com.amazon.avod.thirdpartyclient",
        "com.plexapp.android",
    )

    fun opensEagerly(packageName: String?): Boolean = packageName in eagerPackages
}
