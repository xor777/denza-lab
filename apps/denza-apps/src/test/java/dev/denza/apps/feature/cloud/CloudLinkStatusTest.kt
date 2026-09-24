package dev.denza.apps.feature.cloud

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class CloudLinkStatusTest {
    @Before @After fun resetRuntime() {
        CloudLinkRuntime.car = null
        CloudLinkRuntime.busy = false
        CloudLinkRuntime.failure = null
        CloudLinkRuntime.adapter = null
        CloudLinkRuntime.readAtMs = null
        CloudLinkRuntime.readFailure = null
        CloudLinkRuntime.registrationFailure = null
    }

    private fun words(enabled: Boolean = true, nowMs: Long = 94_000L) =
        CloudLinkStatus.words(CloudLinkRuntime.snapshot(enabled, network = true, pendingDisable = false, nowMs))

    @Test fun enablingAfterTheRecorded94SecondPauseWaitsForTheNewReading() {
        // Owner trace: off confirmed at 13:48:48, on requested at 13:50:22 UTC.
        CloudLinkRuntime.car = CloudCarState("triple_apn", "triple_apn", false, connected = false)
        CloudLinkRuntime.readAtMs = 0L
        assertEquals("Выключено", words(enabled = false))

        CloudLinkRuntime.busy = true
        assertEquals("Подключается", words())

        CloudLinkRuntime.car = CloudCarState("double_apn", "triple_apn", true, connected = true)
        CloudLinkRuntime.readAtMs = 100_000L
        CloudLinkRuntime.busy = false
        assertEquals("На связи", words(nowMs = 100_000L))
    }

    @Test fun anExpiredConnectedReadingIsNotShownAsConnectedDuringRefresh() {
        CloudLinkRuntime.car = CloudCarState("double_apn", "triple_apn", true, connected = true)
        CloudLinkRuntime.readAtMs = 0L
        CloudLinkRuntime.busy = true
        assertEquals("Подключается", words())
        CloudLinkRuntime.busy = false
        assertEquals("Нет свежих данных", words())
    }

    @Test fun anActualReadFailureIsNotHiddenByAnInFlightOperation() {
        CloudLinkRuntime.readAtMs = 0L
        CloudLinkRuntime.readFailure = "Чтение не удалось"
        CloudLinkRuntime.busy = true
        assertEquals("Нет свежих данных", words())
        CloudLinkRuntime.failure = "Выключение не завершено"
        assertEquals("Выключение не завершено", words())
    }

    @Test fun ordinaryPollingKeepsTheNinetySecondFreshnessLimit() {
        CloudLinkRuntime.car = CloudCarState("double_apn", "triple_apn", true, connected = true)
        CloudLinkRuntime.readAtMs = 0L
        assertEquals("На связи", words(nowMs = 90_000L))
        assertEquals("Нет свежих данных", words(nowMs = 90_001L))
    }
}
