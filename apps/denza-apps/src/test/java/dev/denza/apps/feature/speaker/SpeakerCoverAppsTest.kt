package dev.denza.apps.feature.speaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerCoverAppsTest {

    @Test
    fun eagerListIsTheConfirmedProductList() {
        assertEquals(
            setOf(
                "com.byd.mediacenter",
                "com.byd.videoplay",
                "com.byd.minikaraoke",
                "ru.yandex.music",
                "com.google.android.apps.youtube.music",
                "com.spotify.music",
                "com.apple.android.music",
                "com.uma.musicvk",
                "org.videolan.vlc",
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
            ),
            SpeakerCoverApps.eagerPackages,
        )
        assertTrue(SpeakerCoverApps.opensEagerly("ru.yandex.music"))
        assertFalse(SpeakerCoverApps.opensEagerly("example.unknown.player"))
        assertFalse(SpeakerCoverApps.opensEagerly(null))
    }
}
