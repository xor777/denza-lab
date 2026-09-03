package dev.denza.apps.feature.speaker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The copy of the vendor's two lists, which is the whole reason the feature exists.
 *
 * These names are read out of the stock `MediaController`, not chosen here. The test pins them so
 * that a firmware update which moves a package between the lists shows up as a failing assertion
 * rather than as covers that stopped moving.
 */
class SpeakerCoverReportingTest {

    @Test
    fun theCarSpeaksForItsOwnPlayersAndItsPartners() {
        listOf(
            "com.byd.mediacenter",
            "com.byd.videoplay",
            "com.byd.videoplay.youku",
            "com.byd.videoplay.youku.fse",
            "com.byd.videoplay.fse",
            "com.byd.videoplay.hd",
            "com.tencent.qqmusiccar",
            "com.netease.cloudmusic.iot",
            "com.ximalaya.ting.android",
            "com.ximalaya.ting.android.car.byd",
            "bubei.tingshu.hd",
        ).forEach { assertTrue(it, SpeakerCoverReporting.carSpeaksFor(it)) }
    }

    @Test
    fun theCarAlsoHandlesItsOwnSystemFocusOwners() {
        listOf("android", "com.android.server.telecom", "com.android.bluetooth")
            .forEach { assertTrue(it, SpeakerCoverReporting.carSpeaksFor(it)) }
    }

    @Test
    fun everythingElseIsOursToReport() {
        listOf(
            "ru.yandex.music",
            "com.spotify.music",
            "com.google.android.youtube",
            "com.byd.minikaraoke",
        ).forEach { assertFalse(it, SpeakerCoverReporting.carSpeaksFor(it)) }
    }

    @Test
    fun anUnnamedFocusOwnerIsNobodyToSpeakFor() {
        assertTrue(SpeakerCoverReporting.carSpeaksFor(null))
    }
}
