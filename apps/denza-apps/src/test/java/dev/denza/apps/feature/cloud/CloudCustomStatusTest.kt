package dev.denza.apps.feature.cloud

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.denza.apps.core.FeatureStatus

class CloudCustomStatusTest {
    @After fun reset() {
        CloudLinkRuntime.custom = null
        CloudLinkRuntime.customReadAtMs = null
        CloudLinkRuntime.car = null
        CloudLinkRuntime.failure = null
        CloudLinkRuntime.leaseFailure = null
        CloudLinkRuntime.customServiceStartPendingUntilMs = 0L
        CloudLinkRuntime.busy = false
    }

    private fun status(live: Boolean, stage: String, updated: Long = 100_000L) = CloudCustomStatus(
        pid = 42, sessionLive = live, stage = stage, code = "none", updatedElapsedMs = updated,
        connectedElapsedMs = 0, lastRxElapsedMs = 0, lastTxElapsedMs = 0,
        lastReportElapsedMs = 0, nextRetryElapsedMs = 0, attempts = 1, reportsSent = 0,
        statusReplies = 0, commandsForwarded = 0, commandsCompleted = 0, reconnects = 0,
        callbackAgeMs = -1, events = emptyList(), leaseActive = live,
        leaseUntilUptimeMs = if (live) 130_000L else 0L,
    )

    private fun words(enabled: Boolean = true, network: Boolean = true, pending: Boolean = false,
                      now: Long = 100_000L) = CloudLinkStatus.words(CloudLinkRuntime.snapshot(
        enabled, network, pending, now, CloudSimMode.CUSTOM,
    ))

    @Test fun stockTcpNeverMakesCustomActive() {
        CloudLinkRuntime.car = CloudCarState("double_apn", "triple_apn", true, connected = true)
        assertEquals("Подключается", words())
        CloudLinkRuntime.custom = status(true, "connected")
        CloudLinkRuntime.customReadAtMs = 100_000L
        assertEquals("На связи", words())
        assertEquals("Служба связи остановилась", CloudLinkStatus.words(
            CloudLinkRuntime.snapshot(true, true, false, 100_000L, CloudSimMode.CUSTOM,
                uptimeMs = 130_001L)))
    }

    @Test fun blankPairKeepsTheWishButAsksForNumbersWithoutClaimingAConnection() {
        CloudLinkRuntime.custom = status(true, "connected")
        CloudLinkRuntime.customReadAtMs = 100_000L
        val snapshot = CloudLinkRuntime.snapshot(true, true, false, 100_000L,
            CloudSimMode.CUSTOM, identityValid = false)
        assertEquals(FeatureStatus.NEEDS_ACTION, snapshot.status)
        assertEquals("Нужны номера SIM", CloudLinkStatus.words(snapshot))
    }

    @Test fun deadServiceRemainsActionableWithoutImplicitResume() {
        val stopped = CloudLinkRuntime.snapshot(true, true, false, 100_000L,
            CloudSimMode.CUSTOM, serviceAlive = false)
        assertEquals(FeatureStatus.ERROR, stopped.status)
        assertEquals("Служба связи остановилась",
            CloudLinkStatus.words(stopped))
        CloudLinkRuntime.busy = true
        assertEquals("Подключается", CloudLinkStatus.words(CloudLinkRuntime.snapshot(
            true, true, false, 100_000L, CloudSimMode.CUSTOM, serviceAlive = false)))
        CloudLinkRuntime.busy = false
        assertEquals("Выключено", CloudLinkStatus.words(CloudLinkRuntime.snapshot(
            false, true, false, 100_000L, CloudSimMode.CUSTOM, serviceAlive = false)))
        assertEquals("Нужны номера SIM", CloudLinkStatus.words(CloudLinkRuntime.snapshot(
            true, true, false, 100_000L, CloudSimMode.CUSTOM,
            identityValid = false, serviceAlive = false)))
    }

    @Test fun serviceStartWaitsBrieflyThenReportsMissingServiceOrLiveStartup() {
        CloudLinkRuntime.customServiceStartPendingUntilMs = 110_000L
        assertEquals("Подключается", CloudLinkStatus.words(CloudLinkRuntime.snapshot(
            true, true, false, 100_000L, CloudSimMode.CUSTOM, serviceAlive = false)))
        assertEquals("Подключается", CloudLinkStatus.words(CloudLinkRuntime.snapshot(
            true, true, false, 110_000L, CloudSimMode.CUSTOM, serviceAlive = true)))
        assertEquals("Служба связи остановилась", CloudLinkStatus.words(CloudLinkRuntime.snapshot(
            true, true, false, 110_000L, CloudSimMode.CUSTOM, serviceAlive = false)))
        assertEquals("Выключено", CloudLinkStatus.words(CloudLinkRuntime.snapshot(
            false, true, false, 100_000L, CloudSimMode.CUSTOM, serviceAlive = false)))
    }

    @Test fun staleOrUnconfirmedCustomSessionNeverAppearsConnected() {
        CloudLinkRuntime.custom = status(true, "connected")
        CloudLinkRuntime.customReadAtMs = 69_999L
        assertEquals("Нет свежих данных", words())
        CloudLinkRuntime.customReadAtMs = 100_000L
        CloudLinkRuntime.custom = status(false, "retry_wait")
        assertEquals("Нет связи с облаком", words())
        CloudLinkRuntime.custom = status(true, "connected", updated = 9_999L)
        CloudLinkRuntime.customReadAtMs = 100_000L // IPC is fresh; native state is not.
        assertEquals("Нет свежих данных", words())
    }

    @Test fun networkLossAndPendingDisableDoNotUseStockStatus() {
        CloudLinkRuntime.custom = status(true, "connected")
        CloudLinkRuntime.customReadAtMs = 100_000L
        assertEquals("Нет интернета", words(network = false))
        assertEquals("Выключение не завершено", words(enabled = false, pending = true))
        assertEquals("Выключено", words(enabled = false))
    }

    @Test fun unconfirmedStopAppearsBetweenRetriesWithoutFlashingDuringNormalStop() {
        val pending = CloudLinkRequest(enabled = true).request(false)
        assertFalse(pending.enabled)
        assertTrue(pending.pendingDisable)
        assertFalse(CloudLinkSettings.canConfigure(pending.enabled, pending.pendingDisable, busy = false))
        CloudLinkRuntime.busy = true
        // Normal in-flight STOP stays transitional instead of flashing an error.
        assertFalse(CloudLinkRuntime.snapshot(false, true, true, 100_000L, CloudSimMode.CUSTOM)
            .status == FeatureStatus.ERROR)
        CloudLinkRuntime.busy = false
        // An earlier ON cannot erase the durable OFF obligation during the retry delay.
        assertEquals("Выключение не завершено", words(enabled = false, pending = true))
        assertEquals("Выключение не завершено", words(enabled = true, pending = true))
        assertEquals("Выключено", words(enabled = false, pending = false))
    }

    @Test fun retryIsTransitionalButPermanentRejectionIsSpecific() {
        CloudLinkRuntime.customReadAtMs = 100_000L
        CloudLinkRuntime.custom = status(false, "retry_wait").copy(code = "network_retry", retryable = true)
        assertEquals("Подключается", words())
        assertFalse(CloudLinkRuntime.snapshot(true, true, false, 100_000L, CloudSimMode.CUSTOM).status == FeatureStatus.ERROR)
        CloudLinkRuntime.custom = status(false, "failed").copy(code = "registration_rejected")
        assertEquals("Облако отклонило регистрацию", words())
        CloudLinkRuntime.custom = status(false, "failed").copy(code = "unsupported_firmware")
        assertEquals("Эта прошивка пока не поддерживается", words())
        CloudLinkRuntime.custom = status(false, "failed").copy(code = "native_unavailable")
        assertEquals("Не удалось запустить адаптер облака", words())
        CloudLinkRuntime.custom = status(false, "failed").copy(code = "power_lost")
        assertEquals("Машина выключена", words())
        CloudLinkRuntime.custom = status(false, "failed").copy(code = "power_unavailable")
        assertEquals("Не удалось прочитать питание машины", words())
    }

    @Test fun explicitReconcileDoesNotFlashStaleReadWhileBusy() {
        CloudLinkRuntime.custom = status(true, "connected")
        CloudLinkRuntime.customReadAtMs = 60_000L
        CloudLinkRuntime.busy = true
        assertEquals("Подключается", words())
        CloudLinkRuntime.busy = false
        assertEquals("Нет свежих данных", words())
        CloudLinkRuntime.failure = "Адаптер недоступен"
        assertEquals("Нет интернета", words(network = false))
        assertEquals("Выключено", words(enabled = false))
    }

    @Test fun customReportContainsNeitherIdentityNorStockTcpClaim() {
        val identity = CloudIdentity("89860712345678901234", "460011234567890")
        val report = CloudLinkReport.customRows(
            enabled = true, tile = "На связи", failure = null,
            network = CloudNetworkReading(true, false, true, "25001"),
            status = status(true, "connected"), readAtMs = 100_000L,
            busy = false, nowMs = 100_000L,
        ).joinToString("\n")
        assertFalse(report.contains(identity.iccid))
        assertFalse(report.contains(identity.imsi))
        assertFalse(report.contains("cloudmanager"))
        assertFalse(report.contains("TCP"))
    }
}
