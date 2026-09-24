package dev.denza.apps.feature.cloud

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class CloudRegistrationFailureTest {
    private val epoch = Instant.parse("2026-09-24T09:55:00Z").toEpochMilli()
    private val car = CloudCarState(cloudPid = "113", connected = false, registrationError = 3)
    private fun event(message: String, age: Long = 1_000) =
        CloudNativeLog.Event(Instant.ofEpochMilli(epoch - age).toString(), "113", "4", message)
    private fun snapshot(vararg events: CloudNativeLog.Event) = CloudNativeLog.Snapshot(
        "OK", "113", "2", "1", "MISSING", "MISSING", events.size, 0, events.toList(), false)
    private fun failure(snapshot: CloudNativeLog.Snapshot, since: Long = 0) =
        CloudRegistrationFailure.latest(snapshot, since, epoch, 10_000)

    @Test fun freshReplyExplainsFailureButStoredPropertyAloneDoesNot() {
        val rejected = failure(snapshot(event("registration_reply code=3")))!!
        assertEquals("Облако отклонило регистрацию (код 3)", rejected.message(car, 10_000))
        assertNull(failure(snapshot()))
        assertNull(failure(snapshot(event("registration_failed code=3 retry=1"))))
        assertNull(rejected.message(car.copy(cloudPid = "114"), 10_000))
        assertNull(rejected.message(car.copy(connected = true), 10_000))
        assertNull(rejected.message(car, 100_000))
    }

    @Test fun oldFutureOrPreviousRequestEventsCannotBecomeANewFailure() {
        for (age in listOf(-1L, 90_000L, 100_000L)) {
            assertNull(failure(snapshot(event("registration_reply code=3", age))))
        }
        assertNull(failure(snapshot(event("registration_reply code=3")), epoch))
        assertNull(failure(snapshot(event("registration_reply code=3")).copy(status = "PROCESS_CHANGED")))
        assertNull(failure(snapshot(event("registration_reply code=3")).copy(status = "TIMEOUT")))
    }

    @Test fun successOrANewNativeAttemptSupersedesARejection() {
        for (next in listOf("registration_start", "registration_reply code=0", "registration_reply code=1")) {
            assertNull(failure(snapshot(event("registration_reply code=3"), event(next, 0))))
        }
    }

    @Test fun repeatedCaptureDoesNotExtendAnOldReplyAndActiveTcpWinsInUi() {
        val capture = snapshot(event("registration_reply code=3"))
        val first = failure(capture)!!
        val repeated = CloudRegistrationFailure.latest(capture, 0, epoch + 30_000, 40_000)!!
        assertEquals(first.expiresAtMs, repeated.expiresAtMs)
        val message = first.message(car, 10_000)
        assertEquals(message, CloudLinkStatus.words(CloudLinkStatus.snapshot(true, car, true, null, registrationFailure = message)))
        assertEquals("На связи", CloudLinkStatus.words(CloudLinkStatus.snapshot(true, car.copy(connected = true), false, null, registrationFailure = message)))
    }
}
