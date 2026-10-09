package dev.denza.apps.platform.media

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one media-session listener of the process, against a platform played by hand.
 *
 * What the three features used to do each for themselves - listen, put a callback on every session,
 * let go of it - is held here once, so the leaks, the ordering and the refusals are tested here once.
 */
class MediaSessionHubTest {
    private val source = FakeMediaSessionSource()
    private val log = ArrayList<String>()
    private val hub = fakeHub(source, log)

    private val yandex = FakeSession("yandex", "ru.yandex.music", PlaybackState.STATE_PLAYING)
    private val vk = FakeSession("vk", "com.vk.vkvideo", PlaybackState.STATE_PAUSED)
    private val stock = FakeSession("stock", "com.byd.mediacenter", PlaybackState.STATE_STOPPED)

    @Test
    fun theFirstSubscriberStartsListeningAndIsHandedTheActiveListInItsOrder() {
        source.active = listOf(vk, yandex)
        val subscriber = RecordingSubscriber()

        hub.subscribe(subscriber)

        assertTrue(hub.isListening)
        assertNotNull(source.listener)
        assertEquals(listOf(MediaSessionChange.ListRead), subscriber.changes)
        assertEquals(listOf("vk", "yandex"), subscriber.last.activeTokens())
        assertEquals(PlaybackState.STATE_PLAYING, subscriber.last["yandex"]?.playbackState)
        assertEquals("ru.yandex.music", subscriber.last["yandex"]?.packageName)
    }

    @Test
    fun aSessionAddedByThePlatformIsTrackedWithItsState() {
        source.active = listOf(vk)
        val subscriber = RecordingSubscriber()
        hub.subscribe(subscriber)

        source.deliver(yandex, vk)

        assertEquals(listOf("yandex", "vk"), subscriber.last.activeTokens())
        assertEquals(PlaybackState.STATE_PLAYING, subscriber.last["yandex"]?.playbackState)
        assertEquals(1, yandex.callbacks.size)
    }

    @Test
    fun aSessionThatLeavesTheListIsDormantWithItsCallbackUntilItIsDestroyed() {
        source.active = listOf(yandex, vk)
        val subscriber = RecordingSubscriber()
        hub.subscribe(subscriber)

        source.deliver(vk)

        assertEquals(listOf("vk"), subscriber.last.activeTokens())
        assertEquals(listOf("yandex"), subscriber.last.dormantTokens())
        assertFalse(subscriber.last["yandex"]!!.active)
        assertEquals("a dormant session is still watched", 1, yandex.callbacks.size)

        yandex.report(PlaybackState.STATE_PLAYING)
        assertEquals(MediaSessionChange.Playback("yandex"), subscriber.changes.last())

        val readsBefore = source.listReads
        yandex.destroy()

        assertNull(subscriber.last["yandex"])
        assertEquals(0, yandex.callbacks.size)
        assertEquals("the list is read again after a death", readsBefore + 1, source.listReads)
        assertEquals(MediaSessionChange.ListRead, subscriber.changes.last())
        assertEquals(listOf("vk"), subscriber.last.activeTokens())
    }

    @Test
    fun aDestroyedActiveSessionLeavesEvenWhenTheListCannotBeRead() {
        source.active = listOf(yandex, vk)
        val subscriber = RecordingSubscriber()
        hub.subscribe(subscriber)
        source.failReads = true

        yandex.destroy()

        assertNull(subscriber.last["yandex"])
        assertEquals(listOf("vk"), subscriber.last.activeTokens())
        assertEquals(MediaSessionChange.ListRead, subscriber.changes.last())
        assertTrue(log.any { it.startsWith("could not refresh media sessions") })
    }

    @Test
    fun oneCallbackPerSessionHoweverManySubscribersAndReads() {
        source.active = listOf(yandex, vk)
        val subscribers = List(3) { RecordingSubscriber() }
        subscribers.forEach(hub::subscribe)

        repeat(5) {
            source.deliver(vk, yandex)
            source.deliver(yandex, vk)
            hub.refresh()
        }

        assertEquals(1, yandex.registrations)
        assertEquals(1, yandex.callbacks.size)
        assertEquals(1, vk.registrations)
        assertEquals(1, vk.callbacks.size)
    }

    @Test
    fun aRefusedRegistrationIsNotTrackedAndIsReportedOnceUntilTheSessionLeavesTheList() {
        yandex.refuseRegistrations = 3
        source.active = listOf(yandex, vk)
        val subscriber = RecordingSubscriber()

        hub.subscribe(subscriber)
        source.deliver(yandex, vk)

        assertEquals(listOf("vk"), subscriber.last.activeTokens())
        assertNull(subscriber.last["yandex"])
        assertEquals(1, log.count { it == "media session callback refused package=ru.yandex.music" })

        // It leaves and comes back still refused: that is news again.
        source.deliver(vk)
        source.deliver(yandex, vk)
        assertEquals(2, log.count { it == "media session callback refused package=ru.yandex.music" })

        // Every read tries again, and the first registration that holds is tracked.
        source.deliver(yandex, vk)
        assertEquals(listOf("yandex", "vk"), subscriber.last.activeTokens())
        assertEquals(1, yandex.callbacks.size)
        assertEquals(2, log.count { it == "media session callback refused package=ru.yandex.music" })
    }

    @Test
    fun subscribersAreToldInTheOrderTheyCameAndEachChangeOnce() {
        source.active = listOf(yandex)
        val order = ArrayList<String>()
        val first = MediaSessionSubscriber { _, change -> order += "first $change" }
        val second = MediaSessionSubscriber { _, change -> order += "second $change" }
        hub.subscribe(first)
        hub.subscribe(second)
        order.clear()

        yandex.report(PlaybackState.STATE_PAUSED)
        source.deliver(yandex, vk)

        assertEquals(
            listOf(
                "first Playback(token=yandex)",
                "second Playback(token=yandex)",
                "first ListRead",
                "second ListRead",
            ),
            order,
        )
    }

    @Test
    fun aNewSubscriberIsHandedTheCurrentSessionsAndNobodyElseHearsOfIt() {
        source.active = listOf(yandex, vk)
        val first = RecordingSubscriber()
        hub.subscribe(first)
        val readsBefore = source.listReads

        val second = RecordingSubscriber()
        hub.subscribe(second)

        assertEquals(1, first.changes.size)
        assertEquals(listOf(MediaSessionChange.ListRead), second.changes)
        assertEquals(listOf("yandex", "vk"), second.last.activeTokens())
        assertEquals("the hub already knows the list", readsBefore, source.listReads)
    }

    @Test
    fun theLastSubscriberToLeaveTakesEveryCallbackAndTheListenerWithIt() {
        source.active = listOf(yandex, vk)
        val first = RecordingSubscriber()
        val second = RecordingSubscriber()
        hub.subscribe(first)
        hub.subscribe(second)
        source.deliver(vk)

        hub.unsubscribe(first)
        assertTrue(hub.isListening)
        assertEquals(1, yandex.callbacks.size)

        hub.unsubscribe(second)
        assertFalse(hub.isListening)
        assertNull(source.listener)
        assertEquals("the dormant one too", 0, yandex.callbacks.size)
        assertEquals(0, vk.callbacks.size)
        assertSame(MediaSessions.NONE, hub.sessions)

        val after = second.changes.size
        source.deliver(yandex)
        yandex.report(PlaybackState.STATE_PAUSED)
        assertEquals("nobody is told anything after leaving", after, second.changes.size)
    }

    @Test
    fun noCallbackOutlivesADetachAndAComeBackStartsFromTheList() {
        source.active = listOf(yandex, vk)
        repeat(4) {
            val subscriber = RecordingSubscriber()
            hub.subscribe(subscriber)
            source.deliver(vk, yandex)
            assertEquals(1, yandex.callbacks.size)
            assertEquals(1, vk.callbacks.size)
            hub.unsubscribe(subscriber)
            assertEquals(0, yandex.callbacks.size)
            assertEquals(0, vk.callbacks.size)
        }

        // A session that went dormant before the detach is not brought back by the next listen.
        source.active = listOf(yandex, vk)
        val subscriber = RecordingSubscriber()
        hub.subscribe(subscriber)
        source.deliver(vk)
        hub.unsubscribe(subscriber)
        hub.subscribe(subscriber)
        assertEquals(listOf("vk"), subscriber.last.activeTokens())
        assertEquals(emptyList<Any>(), subscriber.last.dormantTokens())
    }

    @Test
    fun aCallbackFromASessionTheHubLetGoOfIsNotNews() {
        source.active = listOf(yandex)
        val subscriber = RecordingSubscriber()
        hub.subscribe(subscriber)
        val stale = yandex.callbacks.single()

        hub.unsubscribe(subscriber)
        hub.subscribe(subscriber)
        val heard = subscriber.changes.size

        stale.onPlaybackState(PlaybackState.STATE_PAUSED)
        stale.onMetadata()
        stale.onDestroyed()

        assertEquals(heard, subscriber.changes.size)
        assertEquals(listOf("yandex"), subscriber.last.activeTokens())
        assertEquals(1, yandex.callbacks.size)
    }

    @Test
    fun withoutAccessNothingIsWatchedTheRefusalIsLoggedAndListenTriesAgain() {
        source.refuseAccess = true
        source.active = listOf(yandex)
        val subscriber = RecordingSubscriber()

        hub.subscribe(subscriber)

        assertFalse(hub.isListening)
        assertNull(source.listener)
        assertEquals(0, yandex.callbacks.size)
        assertEquals(emptyList<MediaSessionChange>(), subscriber.changes)
        assertEquals(listOf("media-session access unavailable"), log)
        assertTrue(hub.refresh().isFailure)

        source.refuseAccess = false
        assertTrue(hub.listen())

        assertTrue(hub.isListening)
        assertEquals(listOf(MediaSessionChange.ListRead), subscriber.changes)
        assertEquals(listOf("yandex"), subscriber.last.activeTokens())
        assertTrue("already listening", hub.listen())
        assertEquals(1, yandex.registrations)
    }

    @Test
    fun listenWithNobodySubscribedDoesNothing() {
        assertFalse(hub.listen())
        assertNull(source.listener)
    }

    @Test
    fun refreshReadsTheListForItsCallerAndPublishesNothing() {
        source.active = listOf(yandex)
        val subscriber = RecordingSubscriber()
        hub.subscribe(subscriber)
        val heard = subscriber.changes.size
        val yandexReads = yandex.stateReads
        stock.state = PlaybackState.STATE_PLAYING
        source.active = listOf(stock, yandex)

        val read = hub.refresh().getOrThrow()

        assertEquals(listOf("stock", "yandex"), read.activeTokens())
        assertEquals("a session new to the press is tracked from it", 1, stock.callbacks.size)
        assertEquals("nobody else is told", heard, subscriber.changes.size)
        assertEquals("the press reads no state", 0, stock.stateReads)
        assertEquals(yandexReads, yandex.stateReads)
        assertNull(read["stock"]?.playbackState)
        assertSame(read, hub.sessions)

        // The platform's own listener, a moment later, tells everybody and reads the states.
        source.deliver(stock, yandex)
        assertEquals(MediaSessionChange.ListRead, subscriber.changes.last())
        assertEquals(PlaybackState.STATE_PLAYING, subscriber.last["stock"]?.playbackState)
        assertEquals(1, stock.registrations)
    }

    @Test
    fun aFailedRefreshSaysSo() {
        source.active = listOf(yandex)
        hub.subscribe(RecordingSubscriber())
        source.failReads = true

        assertTrue(hub.refresh().isFailure)
        assertTrue(hub.isListening)
    }

    @Test
    fun everyListReadReadsTheActiveSessionsStatesAgainAndNotTheDormantOnes() {
        source.active = listOf(yandex, vk)
        val subscriber = RecordingSubscriber()
        hub.subscribe(subscriber)
        source.deliver(yandex)
        val yandexReads = yandex.stateReads
        val vkReads = vk.stateReads

        // A state that changed with no report reaching us yet: the next read sees it.
        yandex.state = PlaybackState.STATE_PAUSED
        source.deliver(yandex)

        assertEquals(yandexReads + 1, yandex.stateReads)
        assertEquals(vkReads, vk.stateReads)
        assertEquals(PlaybackState.STATE_PAUSED, subscriber.last["yandex"]?.playbackState)
    }

    @Test
    fun playbackAndMetadataReportsArePublishedWithTheirSession() {
        source.active = listOf(yandex, vk)
        val subscriber = RecordingSubscriber()
        hub.subscribe(subscriber)

        vk.report(PlaybackState.STATE_PLAYING)
        assertEquals(MediaSessionChange.Playback("vk"), subscriber.changes.last())
        assertEquals(PlaybackState.STATE_PLAYING, subscriber.last["vk"]?.playbackState)
        assertEquals("a report is not a read", PlaybackState.STATE_PLAYING, subscriber.last["yandex"]?.playbackState)

        vk.report(null)
        assertNull(subscriber.last["vk"]?.playbackState)

        vk.reportMetadata(MediaTrack("Song", "Band"))
        assertEquals(MediaSessionChange.Metadata("vk"), subscriber.changes.last())
        assertEquals(MediaTrack("Song", "Band"), subscriber.last["vk"]?.controls?.track())
    }

    @Test
    fun aSubscriberThatLeavesWhileAnotherIsBeingToldHearsNothingMore() {
        source.active = listOf(yandex)
        val second = RecordingSubscriber()
        val first = MediaSessionSubscriber { _, change ->
            if (change is MediaSessionChange.Playback) hub.unsubscribe(second)
        }
        hub.subscribe(first)
        hub.subscribe(second)
        val heard = second.changes.size

        yandex.report(PlaybackState.STATE_PAUSED)

        assertEquals(heard, second.changes.size)
    }

    /**
     * The grant goes: the firmware hands the listener an empty list and drops it. The hub notices
     * on reading that list again, lets go of everything and stops listening, so a subscriber that
     * starts again after the repair - the speakers' restart, the strip coming back - registers a
     * new listener instead of subscribing to a dead one.
     */
    @Test
    fun aRevokedGrantIsNoticedAndASubscriberStartingAgainListensAgain() {
        yandex.state = PlaybackState.STATE_PLAYING
        source.active = listOf(yandex, vk)
        val key = RecordingSubscriber()
        val speakers = RecordingSubscriber()
        hub.subscribe(key)
        hub.subscribe(speakers)

        source.revoke()

        assertFalse(hub.isListening)
        assertNull(source.listener)
        assertEquals(0, yandex.callbacks.size)
        assertEquals(MediaSessionChange.ListRead, speakers.changes.last())
        assertTrue("subscribers let go of what they held", speakers.last.active.isEmpty())
        assertTrue(key.last.dormant.isEmpty())
        assertTrue(log.contains("media-session access lost"))

        // The repair puts the grant back; the speakers restart.
        source.refuseAccess = false
        stock.state = PlaybackState.STATE_PLAYING
        source.active = listOf(stock, yandex, vk)
        hub.unsubscribe(speakers)
        hub.subscribe(speakers)

        assertTrue(hub.isListening)
        assertNotNull(source.listener)
        assertEquals(listOf("stock", "yandex", "vk"), speakers.last.activeTokens())
        assertEquals(listOf("stock", "yandex", "vk"), key.last.activeTokens())

        // And the platform's events reach everybody again.
        source.deliver(yandex, vk)
        assertEquals(listOf("yandex", "vk"), speakers.last.activeTokens())
        assertEquals(1, yandex.callbacks.size)
    }

    /** What `MediaSessionAccess` calls after a repair that granted the listener. */
    @Test
    fun relistenPutsANewListenerOnEvenWhenTheHubNeverHeardItGo() {
        source.active = listOf(yandex, vk)
        val subscriber = RecordingSubscriber()
        hub.subscribe(subscriber)
        source.deliver(vk)
        source.dropListenerSilently()
        assertTrue("the hub cannot know", hub.isListening)
        val readsBefore = source.listReads
        stock.state = PlaybackState.STATE_PLAYING
        source.active = listOf(stock, vk)

        assertTrue(hub.relisten())

        assertNotNull(source.listener)
        assertEquals(readsBefore + 1, source.listReads)
        assertEquals(MediaSessionChange.ListRead, subscriber.changes.last())
        assertEquals(listOf("stock", "vk"), subscriber.last.activeTokens())
        assertEquals("a dormant session is kept", listOf("yandex"), subscriber.last.dormantTokens())
        assertEquals(1, yandex.callbacks.size)
        assertEquals(1, vk.registrations)

        source.deliver(vk, stock)
        assertEquals(listOf("vk", "stock"), subscriber.last.activeTokens())
    }

    @Test
    fun relistenWithNobodySubscribedDoesNothing() {
        assertFalse(hub.relisten())
        assertNull(source.listener)
    }

    @Test
    fun anEmptyListThatTheReadConfirmsIsJustAnEmptyList() {
        source.active = listOf(yandex)
        val subscriber = RecordingSubscriber()
        hub.subscribe(subscriber)

        source.deliver()

        assertTrue(hub.isListening)
        assertNotNull(source.listener)
        assertTrue(subscriber.last.active.isEmpty())
        assertEquals(listOf("yandex"), subscriber.last.dormantTokens())
    }

    @Test
    fun aSubscriberThatThrowsDoesNotKeepTheOthersFromHearing() {
        source.active = listOf(yandex)
        val broken = MediaSessionSubscriber { _, _ -> error("broken") }
        val after = RecordingSubscriber()
        hub.subscribe(broken)
        hub.subscribe(after)

        yandex.report(PlaybackState.STATE_PAUSED)
        source.deliver(yandex, vk)

        assertEquals(
            listOf(
                MediaSessionChange.ListRead,
                MediaSessionChange.Playback("yandex"),
                MediaSessionChange.ListRead,
            ),
            after.changes,
        )
        assertTrue(hub.isListening)
        assertTrue(log.any { it.startsWith("media-session subscriber failed") })
    }

    /** This firmware's `MediaController.getPackageName` answers null once the session's process died. */
    @Test
    fun aSessionWhosePackageCannotBeReadIsNotTrackedAndTheRestAre() {
        val dying = FakeSession("dying", null, PlaybackState.STATE_PLAYING)
        source.active = listOf(dying, yandex)
        val subscriber = RecordingSubscriber()

        hub.subscribe(subscriber)
        source.deliver(dying, yandex)

        assertTrue(hub.isListening)
        assertEquals(listOf("yandex"), subscriber.last.activeTokens())
        assertEquals("no callback on a session we do not track", 0, dying.registrations)
        assertEquals(1, log.count { it == "media session without a package" })

        dying.packageName = "com.example.player"
        source.deliver(dying, yandex)
        assertEquals(listOf("dying", "yandex"), subscriber.last.activeTokens())
    }

    @Test
    fun subscribingTwiceIsOneSubscription() {
        source.active = listOf(yandex)
        val subscriber = RecordingSubscriber()
        hub.subscribe(subscriber)
        hub.subscribe(subscriber)

        yandex.report(PlaybackState.STATE_PAUSED)
        assertEquals(2, subscriber.changes.size)

        hub.unsubscribe(subscriber)
        assertFalse(hub.isListening)
    }
}
