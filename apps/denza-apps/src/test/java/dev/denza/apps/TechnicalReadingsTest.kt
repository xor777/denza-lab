package dev.denza.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class TechnicalReadingsTest {

    @Test
    fun `a bracketed line opens a section and the lines under it are its rows`() {
        val sections = TechnicalReadings.parse(
            """
            [Облако]
            Связь=включена, плитка «На связи»
            Отказ=нет
            [Приложение]
            Версия=0.7.0 · сборка 54
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                TechnicalSection(
                    "Облако",
                    listOf(TechnicalRow("Связь", "включена, плитка «На связи»"), TechnicalRow("Отказ", "нет")),
                ),
                TechnicalSection("Приложение", listOf(TechnicalRow("Версия", "0.7.0 · сборка 54"))),
            ),
            sections,
        )
    }

    @Test
    fun `a value keeps every equals sign and semicolon after the first`() {
        val row = TechnicalReadings.row("Доставка=phase=idle; start=—; fire=ok")
        assertEquals(TechnicalRow("Доставка", "phase=idle; start=—; fire=ok"), row)
    }

    @Test
    fun `nothing read is a dash, and lines before any section share an untitled one`() {
        val sections = TechnicalReadings.parse("Отдельно\nПусто=\n\n[Пустой]\n[Потом]\nКлюч=значение")
        assertEquals(
            listOf(
                TechnicalSection(null, listOf(TechnicalRow("Отдельно", "—"), TechnicalRow("Пусто", "—"))),
                TechnicalSection("Потом", listOf(TechnicalRow("Ключ", "значение"))),
            ),
            sections,
        )
    }

    @Test
    fun `what is rendered parses back to the same sections`() {
        val sections = listOf(
            TechnicalSection("Облако", listOf(TechnicalRow("SIM", "25001"), TechnicalRow("TCP", "a=b; c=d"))),
            TechnicalSection("Экраны Android", listOf(TechnicalRow("Всего", "3"))),
        )
        assertEquals(sections, TechnicalReadings.parse(TechnicalReadings.render(sections)))
    }
}
