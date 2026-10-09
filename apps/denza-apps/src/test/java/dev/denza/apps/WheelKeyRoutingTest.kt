package dev.denza.apps

import android.media.session.PlaybackState
import dev.denza.apps.feature.media.MediaKeyDiagnostics
import dev.denza.apps.feature.media.MediaKeyRider
import dev.denza.apps.feature.media.MediaLastPlayed
import dev.denza.apps.feature.media.MediaLastPlayedStore
import dev.denza.apps.feature.media.MediaResumeController
import dev.denza.apps.feature.media.MediaResumeCore
import dev.denza.apps.feature.media.MediaResumeSessions
import dev.denza.apps.feature.navigation.SteeringWheelKeyRider
import dev.denza.apps.platform.accessibility.RiderDispatch
import dev.denza.apps.platform.media.FakeMediaSessionSource
import dev.denza.apps.platform.media.FakeSession
import dev.denza.apps.platform.media.fakeHub
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The wheel's two keys as the service hands them out: the production riders of
 * [DenzaAccessibilityRiders], in their order, through [RiderDispatch]. Only the Android around them
 * is stood in for: behind Play/Pause, the real [MediaResumeController] and policy over a fake
 * session list, and a guard (call, mute) the test answers; behind ★, its switch and navigation.
 */
class WheelKeyRoutingTest {
    private val source = FakeMediaSessionSource()
    private val hub = fakeHub(source)
    private val store = MemoryStore()
    private val core = MediaResumeCore(store)
    private var mediaLogThrows = false
    private val controller = MediaResumeController(core, MediaResumeSessions(hub, core) { _, _ -> }) {
        if (mediaLogThrows) error("the key's log broke")
    }
    private val player = FakeSession("player", "ru.yandex.music", PlaybackState.STATE_PLAYING).apply {
        actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE
    }

    private var guardAllows = true
    private var guardAsked = 0
    private var starOn = true
    private var navigationAccepts = true
    private var navigations = 0
    private var starLogThrows = false

    private val riders = DenzaAccessibilityRiders.create()
    private val media = riders.filterIsInstance<MediaKeyRider>().single()
    private val star = riders.filterIsInstance<SteeringWheelKeyRider>().single()
    private val failures = mutableListOf<String>()
    private val dispatch = RiderDispatch(riders) { rider, call, _ -> failures += "$rider $call" }

    @Before
    fun attach() {
        MediaKeyDiagnostics.clearForTest()
        source.active = listOf(player)
        media.attach(controller) {
            guardAsked++
            guardAllows
        }
        star.attach(
            switchedOn = { starOn },
            perform = {
                navigations++
                navigationAccepts
            },
            log = { if (starLogThrows) error("the star's log broke") },
        )
    }

    @After
    fun clear() {
        MediaKeyDiagnostics.clearForTest()
        assertTrue("no rider may throw out of a key: $failures", failures.isEmpty())
    }

    private fun press(code: Int): List<Boolean> = listOf(
        dispatch.key(code, DOWN, 0),
        dispatch.key(code, DOWN, 1),
        dispatch.key(code, UP, 0),
    )

    @Test
    fun `an accepted play pause press is consumed whole and the star never acts`() {
        assertEquals(listOf(true, true, true), press(386))
        assertEquals(1, player.pauses)
        assertEquals("the guard is asked for the new press alone", 1, guardAsked)
        assertEquals(0, navigations)
    }

    /** A call or a mute leaves the press to the firmware, whole: the policy is not even asked. */
    @Test
    fun `a play pause the guard refuses goes to the firmware untouched`() {
        guardAllows = false

        assertEquals(listOf(false, false, false), press(KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(listOf(false, false, false), press(386))
        assertEquals(0, player.pauses + player.plays)
        assertEquals(2, guardAsked)
    }

    /** No player and nothing played before: the policy refuses, and the firmware answers. */
    @Test
    fun `a play pause the policy refuses goes to the firmware, both halves`() {
        val silentCore = MediaResumeCore(MemoryStore())
        val silent = fakeHub(FakeMediaSessionSource())
        media.attach(MediaResumeController(silentCore, MediaResumeSessions(silent, silentCore) { _, _ -> }) {}) { true }

        assertEquals(listOf(false, false, false), press(386))
        assertEquals(0, player.pauses + player.plays)
    }

    @Test
    fun `a consumed DOWN keeps its UP even when the guard, the switch and the player turn before it`() {
        assertTrue(dispatch.key(KEYCODE_MEDIA_PLAY_PAUSE, DOWN, 0))
        guardAllows = false
        starOn = false
        player.report(PlaybackState.STATE_PAUSED)

        assertTrue(dispatch.key(KEYCODE_MEDIA_PLAY_PAUSE, DOWN, 1))
        assertTrue(dispatch.key(KEYCODE_MEDIA_PLAY_PAUSE, UP, 0))
    }

    @Test
    fun `an accepted star press is consumed whole, after play pause let it pass`() {
        assertEquals(listOf(true, true, true), press(321))
        assertEquals(1, navigations)
        assertEquals("321 is not a media key: the guard is not asked", 0, guardAsked)
        assertEquals(0, player.pauses)
    }

    @Test
    fun `a star press navigation refuses, or with its switch off, goes to the firmware`() {
        navigationAccepts = false
        assertEquals(listOf(false, false, false), press(321))

        starOn = false
        navigationAccepts = true
        assertEquals(listOf(false, false, false), press(321))
        assertEquals("off, navigation is not asked", 1, navigations)
    }

    @Test
    fun `a star press taken before its switch went off keeps its UP`() {
        assertTrue(dispatch.key(321, DOWN, 0))
        starOn = false
        assertTrue(dispatch.key(321, UP, 0))
        assertFalse(dispatch.key(321, DOWN, 0))
    }

    @Test
    fun `other keys pass both riders untouched`() {
        for (code in listOf(87, 88, 322, 334, 335)) assertEquals(listOf(false, false, false), press(code))
        assertEquals(0, guardAsked + player.pauses + player.plays + navigations)
    }

    /** A press answered stays answered when the telling about it throws: never ours and the firmware's too. */
    @Test
    fun `a play pause press stays consumed when its log throws after the policy acted`() {
        mediaLogThrows = true

        assertEquals(listOf(true, true, true), press(386))
        assertEquals(1, player.pauses)
    }

    @Test
    fun `a star press stays consumed when its log line throws after navigation acted`() {
        starLogThrows = true

        assertEquals(listOf(true, true, true), press(321))
        assertEquals(1, navigations)
    }

    @Test
    fun `riders the service never connected take no key`() {
        val fresh = DenzaAccessibilityRiders.create()
        val idle = RiderDispatch(fresh) { rider, call, _ -> failures += "$rider $call" }

        assertEquals(listOf(false, false), listOf(idle.key(386, DOWN, 0), idle.key(321, DOWN, 0)))
    }

    /**
     * The owner of a DOWN answers its UP because it is asked first and no later rider takes its
     * code: the key riders' codes, as the riders themselves consume them with everything accepting,
     * do not meet.
     */
    @Test
    fun `no two key riders take the same code`() {
        val taken = mutableMapOf<String, MutableSet<Int>>()
        for (rider in riders.filter { it.takesKeys }) {
            for (code in 0..1023) {
                val down = rider.onKeyEvent(code, DOWN, 0)
                val up = rider.onKeyEvent(code, UP, 0)
                if (down || up) taken.getOrPut(rider.name) { mutableSetOf() } += code
            }
        }

        assertEquals(
            mapOf(
                "media-key" to setOf(KEYCODE_MEDIA_PLAY_PAUSE, KEYCODE_MEDIA_PLAY, KEYCODE_MEDIA_PAUSE, 386),
                "steering-wheel-key" to setOf(321),
            ),
            taken,
        )
        val all = taken.values.flatten()
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun `only a first DOWN of the four media keys is a new media press`() {
        for (code in listOf(KEYCODE_MEDIA_PLAY_PAUSE, KEYCODE_MEDIA_PLAY, KEYCODE_MEDIA_PAUSE, 386)) {
            assertTrue(MediaKeyRider.isNewMediaPress(code, DOWN, 0))
            assertFalse(MediaKeyRider.isNewMediaPress(code, DOWN, 1))
            assertFalse(MediaKeyRider.isNewMediaPress(code, UP, 0))
        }
        for (code in listOf(321, 322, 87, 88, 334, 335)) {
            assertFalse(MediaKeyRider.isNewMediaPress(code, DOWN, 0))
        }
    }

    private class MemoryStore : MediaLastPlayedStore {
        private var record: MediaLastPlayed? = null

        override fun lastPlayed(): MediaLastPlayed? = record

        override fun remember(packageName: String) {
            record = MediaLastPlayed(packageName)
        }
    }

    private companion object {
        const val DOWN = 0
        const val UP = 1
        const val KEYCODE_MEDIA_PLAY_PAUSE = 85
        const val KEYCODE_MEDIA_PLAY = 126
        const val KEYCODE_MEDIA_PAUSE = 127
    }
}
