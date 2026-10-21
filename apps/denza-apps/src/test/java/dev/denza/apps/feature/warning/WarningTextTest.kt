package dev.denza.apps.feature.warning

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WarningTextTest {

    private val zone = ZoneId.of("Europe/Moscow")

    private val names = WarningNames.of(
        """{"version":"1","language":"zh","dictionary":[
            {"id":"13F","text":"车体状态（天窗、车门、前舱盖后备箱、车窗、安全带）"},
            {"id":"1DD","text":"请加注洗涤液"},
            {"id":"20E","text":"第一条"},
            {"id":"20E","text":"第二条"},
            {"id":"23-E","text":"起动型铁电池故障"},
            {"id":"7D","text":"未检测到钥匙"}
        ]}""",
        "zh\tru\n请加注洗涤液\tДолейте омывающую жидкость\n未检测到钥匙\tКлюч не обнаружен\n",
    )

    @Test
    fun aCodeIsNamedByTheCarsTextInRussianWhereTranslated() {
        assertEquals("Долейте омывающую жидкость", names.of(0x1DD))
        // untranslated stays the car's own text, never a guess
        assertEquals("车体状态（天窗、车门、前舱盖后备箱、车窗、安全带）", names.of(0x13F))
        // the first of a repeated id, and no ten-bit value out of `23-E`
        assertEquals("第一条", names.of(0x20E))
        assertNull(names.of(0x23))
        assertEquals(4, names.size)
        assertEquals(0, WarningNames.of(null, null).size)
        assertEquals(0, WarningNames.of("not json", null).size)
    }

    @Test
    fun anUnknownCodeIsTheCodeAlone() {
        assertEquals("1DD · Долейте омывающую жидкость", WarningText.named(0x1DD, names))
        assertEquals("2A7", WarningText.named(0x2A7, names))
        assertEquals("3 раза", WarningText.sightingSummary(WarningSighting(0x2A7, AT_1840, AT_1840, 3), names))
    }

    @Test
    fun timesAgreeWithTheirNumber() {
        assertEquals(
            listOf("1 раз", "2 раза", "4 раза", "5 раз", "11 раз", "12 раз", "21 раз", "22 раза", "111 раз"),
            listOf(1, 2, 4, 5, 11, 12, 21, 22, 111).map(WarningText::times),
        )
    }

    @Test
    fun aSightingSaysWhenOnItsLineAndHowOftenUnderIt() {
        val once = WarningSighting(0x1DD, AT_1840, AT_1840, 1)
        assertEquals("1DD · сегодня, 18:40", WarningText.sightingTitle(once, NOW, zone))
        assertEquals("1 раз · Долейте омывающую жидкость", WarningText.sightingSummary(once, names))
        val often = WarningSighting(0x7D, AT_1840 - 2 * DAY - HOUR, AT_1840 - DAY, 3)
        assertEquals("7D · вчера, 18:40", WarningText.sightingTitle(often, NOW, zone))
        assertEquals("3 раза · Ключ не обнаружен", WarningText.sightingSummary(often, names))
        assertEquals("19.10, 17:40", WarningText.day(often.firstMs, NOW, zone))
    }

    @Test
    fun theServiceRowAndTheStatusLine() {
        val idle = WarningLogState(sinceMs = AT_1840 - 2 * DAY, linked = true)
        assertEquals(WarningText.NONE, WarningText.row(idle, NOW, zone))
        assertEquals("Запись идёт с 19.10, 18:40" to WarningTone.QUIET, WarningText.status(idle, NOW, zone))

        val seen = idle.copy(sightings = listOf(WarningSighting(0x13F, AT_1840, AT_1840, 1)))
        assertEquals("Последнее: 13F · сегодня, 18:40", WarningText.row(seen, NOW, zone))

        val lost = seen.copy(linked = false, lostAtMs = AT_1840)
        assertEquals("Машина не отвечает с 18:40" to WarningTone.ATTENTION, WarningText.status(lost, NOW, zone))

        val off = seen.copy(enabled = false)
        assertEquals("Запись выключена", WarningText.row(off, NOW, zone))
        assertEquals("Запись выключена" to WarningTone.QUIET, WarningText.status(off, NOW, zone))
    }

    @Test
    fun eachEventInWords() {
        fun text(kind: WarningEventKind, code: Int? = null) = WarningText.eventText(WarningEvent(NOW, kind, code), names)
        assertEquals("Приложение запущено", text(WarningEventKind.START))
        assertEquals("Машина отвечает, предупреждений нет", text(WarningEventKind.LINK, 0))
        assertEquals("Машина отвечает · 7D · Ключ не обнаружен", text(WarningEventKind.LINK, 0x7D))
        assertEquals("1DD · Долейте омывающую жидкость", text(WarningEventKind.CODE, 0x1DD))
        assertEquals("Предупреждение снято", text(WarningEventKind.CODE, 0))
        assertEquals("Машина не отвечает", text(WarningEventKind.LOST))
        assertEquals("Журнал очищен", text(WarningEventKind.CLEARED))
        assertEquals("21.10 18:40:00", WarningText.eventTime(AT_1840, zone))
    }

    @Test
    fun theExportCarriesTheCodesAndEveryEvent() {
        val state = WarningLogState(
            sinceMs = AT_1840,
            sightings = listOf(WarningSighting(0x1DD, AT_1840, AT_1840, 1)),
        )
        val events = listOf(
            WarningEvent(AT_1840 - 1_000, WarningEventKind.START),
            WarningEvent(AT_1840, WarningEventKind.CODE, 0x1DD),
        )
        val text = WarningExport.text(listOf("заголовок"), state, events, names, zone)
        val lines = text.lines()
        assertEquals("заголовок", lines[0])
        assertEquals("1DD\t1\t2026-10-21 18:40:00 +03:00\t2026-10-21 18:40:00 +03:00\tДолейте омывающую жидкость", lines[lines.indexOf("[Коды]") + 2])
        assertEquals("2026-10-21 18:40:00 +03:00\tcode\t1DD\t1DD · Долейте омывающую жидкость", lines[lines.indexOf("[События]") + 3])
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
        /** 2026-10-21 18:40 Moscow, and the page opened at 19:00. */
        const val AT_1840 = 1_792_591_200_000L + 100 * 60_000L
        const val NOW = 1_792_591_200_000L + 2 * HOUR
    }
}
