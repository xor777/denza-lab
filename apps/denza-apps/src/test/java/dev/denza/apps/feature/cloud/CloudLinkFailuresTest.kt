package dev.denza.apps.feature.cloud

import dev.denza.apps.core.FeatureStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who a failure belongs to decides what clears it.
 *
 * The controller cannot run without a car, so its decisions are [CloudLinkFailures]'s transitions,
 * named by the controller's own events, and this drives them the way the controller does: the
 * pure [CloudLinkCore] says what a pass would send, the transition records how it went, and
 * [CloudLinkStatus.snapshot] says what the tile shows for it.
 */
class CloudLinkFailuresTest {

    /** A car on Wi-Fi under the adapter's profile, not connected - forum cars spend hours here. */
    private val waiting = CloudCarState(
        profile = CloudLinkProtocol.WIFI_PROFILE,
        buildProfile = CloudLinkProtocol.STOCK_PROFILE,
        apn1Disabled = true,
        cloudPid = "113",
        connected = false,
    )

    private fun tile(failures: CloudLinkFailures, enabled: Boolean = true, car: CloudCarState = waiting) =
        CloudLinkStatus.snapshot(
            enabled = enabled,
            car = car,
            network = true,
            failure = failures.press,
            automaticFailure = failures.automatic,
        )

    /**
     * The review's scenario: one ADB timeout during the backoff.
     *
     * The adapter said «ready», the client did not connect, and the next «ready» is held back for
     * five minutes and later up to an hour. One pass in that wait fails to read the car; the next
     * reads it fine and has nothing to send. That pass used to leave the failure standing - only a
     * pass that sent something, or a connected client, cleared it - so the tile stayed coral with
     * a transport class name in it until the backoff ran out.
     */
    @Test
    fun aPassThatFailedOnceClearsWithTheNextPassThatReadsTheCar() {
        val core = CloudLinkCore()
        core.switchedOn(waiting.copy(profile = CloudLinkProtocol.STOCK_PROFILE), network = true, nowMs = 0)
        core.readySent(0)
        var failures = CloudLinkFailures().pressStarted().pressTaken()

        // 100 s: the read times out. The tile says the state; the reason is the report's.
        failures = failures.passFailed(notRead)
        assertEquals(FeatureStatus.ERROR, tile(failures).status)
        assertEquals("Нет свежих данных", CloudLinkStatus.words(tile(failures)))

        // 115 s: the car answers; past the settle, inside the backoff, so nothing is sent.
        val steps = core.reconcile(waiting, network = true, nowMs = 115_000)
        assertEquals(emptyList<CloudStep>(), steps)
        failures = failures.passCompleted(operated = steps.isNotEmpty(), connected = false)

        assertNull(failures.automatic)
        assertEquals(FeatureStatus.STARTING, tile(failures).status)
        assertEquals("Подключается", CloudLinkStatus.words(tile(failures)))
    }

    /** A refused press is the driver's, and a pass with nothing to send says nothing about it. */
    @Test
    fun aRefusedPressStandsUntilThePressOrTheCarAnswersIt() {
        val refused = CloudLinkFailures().pressStarted().pressRefused(onRefused)

        val idle = refused.passCompleted(operated = false, connected = false)
        assertEquals(onRefused, idle.press)
        assertEquals("Не включилось", CloudLinkStatus.words(tile(idle)))

        // The car got where the press was going: the adapter's own «ready» went through, or the
        // client is connected.
        assertNull(refused.passCompleted(operated = true, connected = false).press)
        assertNull(refused.passCompleted(operated = false, connected = true).press)

        // Or the driver asked again and the car took it.
        assertNull(refused.pressStarted().pressTaken().press)
        // A failed pass does not clear it either.
        assertEquals(onRefused, refused.passFailed(notRead).press)
    }

    /** Off and on again is a new episode; a pass that failed in the old one is not news. */
    @Test
    fun aPressForgetsWhatTheAdapterTrippedOverBeforeIt() {
        val tripped = CloudLinkFailures().passFailed(notRead)
        assertNull(tripped.pressStarted().automatic)
    }

    @Test
    fun aConfirmedOffOwesNothing() {
        val both = CloudLinkFailures(press = CloudFailure.refused(false, "x"), automatic = notRead)
        assertEquals(CloudLinkFailures(), both.disableConfirmed())
    }

    /** The adapter's own pass matters only while the link is on; a refused press outranks it. */
    @Test
    fun theTileShowsThePressFirstAndAPassOnlyWhileOn() {
        val pass = CloudLinkFailures(automatic = notRead)
        assertEquals("Выключено", CloudLinkStatus.words(tile(pass, enabled = false)))
        assertEquals(FeatureStatus.ERROR, tile(pass).status)

        val both = pass.pressRefused(onRefused)
        assertEquals("Не включилось", CloudLinkStatus.words(tile(both)))
        // A press refused on the way off is still shown with the switch off.
        assertEquals(
            "Не выключилось",
            CloudLinkStatus.words(tile(CloudLinkFailures(press = CloudFailure.refused(false, "x")), enabled = false)),
        )
    }

    /**
     * What the controller caught never reaches the tile: it used to print «Нет ответа:
     * SocketTimeoutException», «Не прочитано с машины: TCP, профиль, флаг APN1…» and, from a bare
     * `check`, «Check failed.». The kind says the words; the reason is kept for the report.
     */
    @Test
    fun noReasonTheControllerCaughtReachesTheTile() {
        val reasons = listOf(
            "Нет ответа: SocketTimeoutException",
            "Не прочитано с машины: TCP, профиль, флаг APN1, APN1 (чтение не завершено)",
            "Check failed.",
            "Команда облачному сервису отклонена (4)",
            "Выключение не завершено",
        )
        val words = setOf("Не включилось", "Не выключилось", "Нет свежих данных", "Нет связи")
        for (reason in reasons) for (kind in CloudFailure.Kind.entries) {
            val failure = CloudFailure(kind, reason)
            for (failures in listOf(CloudLinkFailures(press = failure), CloudLinkFailures(automatic = failure))) {
                val said = CloudLinkStatus.words(tile(failures))
                assertTrue("$kind/$reason said «$said»", said in words)
            }
            assertTrue(failure.report.endsWith(reason))
        }
    }

    /** A pass that read the car and could not finish does not deny a link the car holds. */
    @Test
    fun aPassThatCouldNotFinishLeavesAConnectedLinkConnected() {
        val connected = waiting.copy(connected = true)
        val notDone = CloudLinkFailures(automatic = CloudFailure(CloudFailure.Kind.NOT_DONE, "x"))
        assertEquals("На связи", CloudLinkStatus.words(tile(notDone, car = connected)))
        assertEquals("Нет связи", CloudLinkStatus.words(tile(notDone)))
        // One that could not read it has nothing fresh to say, whatever the last reading was.
        assertEquals("Нет свежих данных", CloudLinkStatus.words(tile(CloudLinkFailures(automatic = notRead), car = connected)))
    }

    private val notRead = CloudFailure(CloudFailure.Kind.NOT_READ, "Нет ответа: IOException")
    private val onRefused = CloudFailure.refused(true, "Операция не подтвердилась")
}
