package dev.denza.apps.feature.weather

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One refresh against the switch and against the stock provider.
 *
 * The switch is pressed on the main thread while a refresh waits on another for a location fix,
 * the geocoder or MET; the provider is another app's and can refuse the write. What the car and
 * the tile end up with in each case is what these hold.
 */
class WeatherRefreshTest {
    private val fence = WeatherRefreshFence()
    private val record = Record()
    private val provider = mutableListOf<String>()
    private var announcements = 0

    @Test
    fun aRefreshDeliversAndThenRecordsWhatItDelivered() {
        val outcome = run(prepare = { prepared("forecast-a", 14) })

        assertEquals(WeatherRefresh.Outcome.DELIVERED, outcome)
        assertEquals(listOf("forecast-a"), provider)
        assertEquals(1, announcements)
        assertEquals(14, record.temperature)
        assertEquals(1, record.deliveries)
    }

    /** Switched off while MET was being asked: the car is not written, told, or woken again. */
    @Test
    fun switchingOffWhileTheForecastIsFetchedWritesNothingAndReArmsNothing() {
        val licence = fence.issue()
        val outcome = WeatherRefresh(licence, record).run(
            prepare = {
                fence.revoke() // the switch goes off here, on the main thread
                prepared("forecast-a", 14)
            },
            deliver = { provider += it },
            announce = { announcements++ },
        )

        assertEquals(WeatherRefresh.Outcome.WITHDRAWN, outcome)
        assertTrue("the provider row must not be replaced", provider.isEmpty())
        assertEquals(0, announcements)
        assertNull(record.temperature)
        var reArmed = false
        assertFalse(licence.runIfValid { reArmed = true })
        assertFalse("the service's re-arm after the run must not run", reArmed)
    }

    /** Switched off while the provider was being written: nothing announces it or records it. */
    @Test
    fun switchingOffDuringTheProviderWriteAnnouncesNothing() {
        val outcome = run(
            prepare = { prepared("forecast-a", 14) },
            deliver = { payload ->
                provider += payload
                fence.revoke()
            },
        )

        assertEquals(WeatherRefresh.Outcome.WITHDRAWN, outcome)
        assertEquals(0, announcements)
        assertNull(record.temperature)
    }

    /**
     * The provider refuses the second run's forecast: the widget still shows 14°, and so does the
     * tile, beside the time of the run that put 14° there. Until 2026-11-19 the temperature was
     * recorded before the write, and the tile showed 21° under the first run's time.
     */
    @Test
    fun aRefusedProviderWriteLeavesTheTileOnWhatTheWidgetShows() {
        run(prepare = { prepared("forecast-a", 14) })
        val deliveredAt = record.deliveries

        val outcome = run(
            prepare = { prepared("forecast-b", 21) },
            deliver = { error("Native weather provider did not retain the payload") },
        )

        assertEquals(WeatherRefresh.Outcome.FAILED, outcome)
        assertEquals("the widget still shows the first forecast", listOf("forecast-a"), provider)
        assertEquals("the tile shows what the widget shows", 14, record.temperature)
        assertEquals(deliveredAt, record.deliveries)
        assertEquals(1, record.failures)
    }

    /** Once the switch-off has revoked, no licensed step is running and none will start. */
    @Test
    fun revokingWaitsForALicensedStepAlreadyRunning() {
        val licence = fence.issue()
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = thread {
            licence.runIfValid {
                inside.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        val revoked = CountDownLatch(1)
        thread {
            fence.revoke()
            revoked.countDown()
        }
        assertFalse("revoke must wait for the step under way", revoked.await(200, TimeUnit.MILLISECONDS))
        release.countDown()
        assertTrue(revoked.await(5, TimeUnit.SECONDS))
        worker.join()
        assertFalse(licence.valid)
        assertFalse(licence.runIfValid { error("must not run after the switch went off") })
    }

    private fun run(
        prepare: () -> PreparedWeather,
        deliver: (String) -> Unit = { provider += it },
    ): WeatherRefresh.Outcome = WeatherRefresh(fence.issue(), record).run(
        prepare = prepare,
        deliver = deliver,
        announce = { announcements++ },
    )

    private fun prepared(payload: String, temperature: Int) =
        PreparedWeather(NativeWeather(payload, temperature), "test")

    private class Record : WeatherRecord {
        var temperature: Int? = null
        var deliveries = 0
        var failures = 0

        override fun attempt() = Unit

        override fun delivered(temperature: Int, message: String) {
            this.temperature = temperature
            deliveries++
        }

        override fun failed(failure: Exception) {
            failures++
        }
    }
}
