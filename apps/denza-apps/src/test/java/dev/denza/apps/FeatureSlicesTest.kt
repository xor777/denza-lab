package dev.denza.apps

import dev.denza.apps.feature.locale.SystemLanguageSnapshot
import dev.denza.apps.feature.mirrors.MirrorsPosition
import dev.denza.apps.feature.weather.WeatherSnapshot
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * A feature that reads its own slice (`SliceFeature`) sees its value and nothing else of the
 * state; [FeatureSlice] is where that value lies. These hold the two halves of that promise: what
 * a reading lays, and that what the feature publishes goes through the publisher's one queue.
 */
class FeatureSlicesTest {

    private val weather = WeatherSnapshot(enabled = false, temperature = -3, updatedMillis = 5_000L)

    @Test
    fun aReadingLaysTheFeaturesValueOnItsFieldsAndNothingElse() {
        val before = DenzaUiState(mirrorsPosition = MirrorsPosition.CENTER, cloudLinkBusy = true)

        val read = before.withReadings(listOf(FeatureSlices.WEATHER.reading(weather)))

        assertEquals(
            before.copy(weatherEnabled = false, weatherTemperature = -3, weatherUpdatedMillis = 5_000L),
            read,
        )
        val language = SystemLanguageSnapshot(name = "Қазақ тілі")
        assertEquals(
            before.copy(systemLanguage = language),
            before.withReadings(listOf(FeatureSlices.SYSTEM_LANGUAGE.reading(language))),
        )
    }

    /** Equal reads publish nothing, so two readings of the same value are one. */
    @Test
    fun readingsAreEqualByWhatWasRead() {
        assertEquals(FeatureSlices.WEATHER.reading(weather), FeatureSlices.WEATHER.reading(weather.copy()))
        assertEquals(
            FeatureSlices.WEATHER.reading(weather).hashCode(),
            FeatureSlices.WEATHER.reading(weather.copy()).hashCode(),
        )
        assertNotEquals(
            FeatureSlices.WEATHER.reading(weather),
            FeatureSlices.WEATHER.reading(weather.copy(temperature = 4)),
        )
        assertEquals(StateSlice.WEATHER, FeatureSlices.WEATHER.reading(weather).slice)
    }

    /**
     * The switch's position is published in its turn: after a read asked for before it, so that
     * read cannot put the old position back - the guarantee the repository's own setter had - and
     * a mark is a read of the feature's slice, after both.
     */
    @Test
    fun aFeaturePublishesThroughThePublishersQueueAndMarksItsOwnSlice() {
        val queued = ArrayDeque<Runnable>()
        val events = mutableListOf<String>()
        val store = DenzaUiStateStore()
        val publisher = DenzaStatePublisher(
            store = store,
            executor = Executor { queued.addLast(it) },
            read = { slices ->
                events += "read $slices"
                // The car as it was read before the switch was pressed: still on.
                { state -> state.copy(weatherEnabled = true) }
            },
        )
        val handle = FeatureSlices.WEATHER.handle(publisher)

        publisher.invalidate(StateSlice.WEATHER, "resume")
        handle.publish("weather switch") { weather ->
            events += "switch"
            weather.copy(enabled = false)
        }
        assertEquals(listOf<String>(), events)
        queued.removeFirst().run()
        assertEquals(listOf("read [WEATHER]", "switch"), events)
        assertEquals(false, store.state.value.weatherEnabled)

        handle.mark("weather")
        queued.removeFirst().run()
        assertEquals(listOf("read [WEATHER]", "switch", "read [WEATHER]"), events)
    }

    @Test
    fun aPublishedValueChangesOnlyTheFeaturesFields() {
        val queued = ArrayDeque<Runnable>()
        val store = DenzaUiStateStore(DenzaUiState(weatherTemperature = 12, cloudLinkBusy = true))
        val publisher = DenzaStatePublisher(
            store = store,
            executor = Executor { queued.addLast(it) },
            read = { null },
        )

        FeatureSlices.WEATHER.handle(publisher).publish("weather switch") { it.copy(enabled = false) }
        while (queued.isNotEmpty()) queued.removeFirst().run()

        assertEquals(
            DenzaUiState(weatherEnabled = false, weatherTemperature = 12, cloudLinkBusy = true),
            store.state.value,
        )
    }
}
