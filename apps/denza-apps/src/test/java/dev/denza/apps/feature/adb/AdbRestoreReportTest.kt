package dev.denza.apps.feature.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class AdbRestoreReportTest {
    private val zone = TimeZone.getTimeZone("Europe/Moscow")

    /** 18.10.2026 at [time] in Moscow. */
    private fun at(time: String): Long {
        val p = time.split(':').map(String::toLong) + 0L
        return MIDNIGHT + ((p[0] * 60 + p[1]) * 60 + p[2]) * 1000
    }

    private val now = at("14:02:11")

    private fun snapshot(
        state: AdbRestoreState = AdbRestoreState.NotNeeded,
        look: AdbBootLook? = AdbBootLook.OPEN,
        outcome: AdbBootOutcome? = null,
        closed: String? = null,
        outcomeAt: String? = null,
        enabled: Boolean = true,
        exhausted: Boolean = false,
    ) = AdbRestoreSnapshot(
        enabled = enabled,
        state = state,
        lastTrigger = AdbRestoreManager.SETTINGS,
        lastOutcome = state.label(),
        lastOutcomeAtMs = now,
        retryBudgetExhausted = exhausted,
        boot = AdbBootRecord(bootCount = 412, bootAtMs = at("08:10"), seenAtElapsedMs = 0L, look = look,
            closedAtMs = closed?.let(::at) ?: 0L, outcome = outcome, outcomeAtMs = outcomeAt?.let(::at) ?: 0L),
    )

    private val restored = snapshot(look = AdbBootLook.CLOSED, outcome = AdbBootOutcome.RESTORED,
        closed = "08:10:40", outcomeAt = "08:11:03")

    @Test
    fun `a port open after the boot needed nothing, and the page says so`() {
        val s = snapshot()
        assertEquals("Доступ есть, восстанавливать не нужно", AdbRestoreReport.status(s)!!.text)
        assertEquals(
            listOf("Когда" to "сегодня, 08:10", "Доступ после неё" to "был сразу", "Восстановление" to "не понадобилось"),
            AdbRestoreReport.bootRows(s, now, zone),
        )
        assertEquals("После перезагрузки не понадобилось", AdbRestoreReport.summary(s, now, zone).text)
    }

    @Test
    fun `a restore stays on the page after the check that opening the service made`() {
        assertEquals("Доступ есть: после перезагрузки его вернуло приложение", AdbRestoreReport.status(restored)!!.text)
        assertEquals(
            listOf("Когда" to "сегодня, 08:10", "Доступ после неё" to "был закрыт", "Восстановление" to "удалось в 08:11, за 23 с"),
            AdbRestoreReport.bootRows(restored, now, zone),
        )
        assertEquals("Вернуло доступ сегодня в 08:11", AdbRestoreReport.summary(restored, now, zone).text)
    }

    @Test
    fun `no access is said in the colour of what it waits for`() {
        val wifi = snapshot(state = AdbRestoreState.WaitingWifi, look = AdbBootLook.CLOSED,
            outcome = AdbBootOutcome.WAITING_WIFI, closed = "08:10:40", outcomeAt = "08:10:41")
        assertEquals(AdbRestoreReport.Line("Доступа нет. Вернётся, как только машина подключится к Wi-Fi",
            AdbRestoreReport.Tone.ATTENTION), AdbRestoreReport.status(wifi))
        assertEquals("ждёт Wi-Fi", AdbRestoreReport.bootRows(wifi, now, zone).toMap().getValue("Восстановление"))
        assertEquals(AdbRestoreReport.Line("Ждёт Wi-Fi", AdbRestoreReport.Tone.ATTENTION),
            AdbRestoreReport.summary(wifi, now, zone))

        val refused = snapshot(state = AdbRestoreState.NeedsActivation, look = AdbBootLook.REFUSED,
            outcome = AdbBootOutcome.NEEDS_ACTIVATION, closed = "08:10:40", outcomeAt = "08:10:40")
        assertEquals(AdbRestoreReport.Tone.BROKEN, AdbRestoreReport.status(refused)!!.tone)
        assertEquals("ключ не принят", AdbRestoreReport.bootRows(refused, now, zone).toMap().getValue("Доступ после неё"))

        val failed = snapshot(state = AdbRestoreState.Failed("mDNS timeout", at("08:12")), look = AdbBootLook.CLOSED,
            outcome = AdbBootOutcome.FAILED, closed = "08:10:40", outcomeAt = "08:12")
        assertEquals("не вышло в 08:12", AdbRestoreReport.bootRows(failed, now, zone).toMap().getValue("Восстановление"))
        assertEquals(AdbRestoreReport.Tone.BROKEN, AdbRestoreReport.summary(failed, now, zone).tone)

        val asking = snapshot(state = AdbRestoreState.NeedsDialog, look = AdbBootLook.CLOSED,
            outcome = AdbBootOutcome.NEEDS_DIALOG, closed = "08:10:40", outcomeAt = "08:10:45")
        assertEquals(AdbRestoreReport.Tone.ATTENTION, AdbRestoreReport.status(asking)!!.tone)
        assertEquals(AdbRestoreReport.Tone.BROKEN, AdbRestoreReport.status(asking.copy(retryBudgetExhausted = true))!!.tone)
    }

    @Test
    fun `access that came back by itself is not a restore`() {
        val back = snapshot(look = AdbBootLook.CLOSED, outcome = AdbBootOutcome.RETURNED, closed = "08:10:40", outcomeAt = "08:20")
        assertEquals("Доступ есть, восстанавливать не нужно", AdbRestoreReport.status(back)!!.text)
        assertEquals("доступ появился в 08:20", AdbRestoreReport.bootRows(back, now, zone).toMap().getValue("Восстановление"))
    }

    @Test
    fun `an earlier day is said as yesterday or a date`() {
        val night = restored.copy(boot = restored.boot!!.copy(bootAtMs = at("08:10") - DAY,
            closedAtMs = at("08:10:40") - DAY, outcomeAtMs = at("08:11:03") - DAY))
        val rows = AdbRestoreReport.bootRows(night, now, zone).toMap()
        assertEquals("вчера, 08:10", rows.getValue("Когда"))
        assertEquals("удалось вчера в 08:11, за 23 с", rows.getValue("Восстановление"))
        assertEquals("Вернуло доступ вчера в 08:11", AdbRestoreReport.summary(night, now, zone).text)
        val week = restored.copy(boot = restored.boot!!.copy(bootAtMs = at("08:10") - 5 * DAY))
        assertEquals("13.10, 08:10", AdbRestoreReport.bootRows(week, now, zone).toMap().getValue("Когда"))
    }

    @Test
    fun `off, the switch says it and the row says it in one word`() {
        val off = snapshot(enabled = false, state = AdbRestoreState.Disabled)
        assertNull(AdbRestoreReport.status(off))
        assertEquals("Выключено", AdbRestoreReport.summary(off, now, zone).text)
    }

    @Test
    fun `nothing looked at in this process is not called access`() {
        val fresh = AdbRestoreSnapshot()
        assertEquals("Проверяю доступ…", AdbRestoreReport.status(fresh)!!.text)
        assertTrue(AdbRestoreReport.bootRows(fresh, now, zone).isEmpty())
        assertEquals("Включено", AdbRestoreReport.summary(fresh, now, zone).text)
    }

    @Test
    fun `the technical rows say every fact once`() {
        val rows = AdbRestoreReport.technicalRows(restored.copy(tlsPort = 37451, autoAllow = "allowed"), zone)
        val keys = rows.map { it.first }
        assertEquals(keys.distinct(), keys)
        // The state and the last outcome were one reading twice; the Wi-Fi row named no network.
        assertFalse("Состояние" in keys)
        assertFalse("Последний исход" in keys)
        assertFalse("Wi-Fi" in keys)
        assertFalse("WRITE_SECURE_SETTINGS" in keys)
        val map = rows.toMap()
        assertEquals("18.10 08:10 · №412", map.getValue("Перезагрузка"))
        assertEquals("порт закрыт → восстановлен 08:11:03, за 23 с", map.getValue("После неё"))
        assertEquals("доступ есть · 14:02:11 · открыт «Сервис»", map.getValue("Последняя проверка"))
        assertEquals("37451", map.getValue("TLS-порт"))
    }

    @Test
    fun `with the port rows the section still says the permission once`() {
        val section = AdbRestoreReport.technicalRows(restored, zone).map { it.first } +
            AdbPortRestoreReport.rows(true, AdbPortRestoreState(), nowMs = 0L).map { it.first }
        assertEquals(1, section.count { it == "WRITE_SECURE_SETTINGS" })
        assertEquals(section.distinct(), section)
    }

    private companion object {
        const val DAY = 86_400_000L
        /** 2026-10-18T00:00+03:00. */
        const val MIDNIGHT = 1_792_270_800_000L
    }
}
