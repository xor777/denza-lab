package dev.denza.apps

import dev.denza.apps.feature.defaultapps.InstalledDefaultApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SimulcastAppChoicesTest {
    private val installed = listOf(
        app("ru.rutube.app", "Rutube"),
        app("com.vk.vkvideo", "VK Видео"),
        app("dev.denza.apps", "Denza Apps"),
        app("com.byd.helper", "Помощник", launcher = false),
        app("org.videolan.vlc", "VLC"),
        app("ru.kinopoisk", "Кинопоиск"),
    )

    @Test
    fun `the page offers what the launcher shows, without this app, by name`() {
        val choices = SimulcastAppChoices.of(installed, "dev.denza.apps", selected = listOf("org.videolan.vlc"))

        assertEquals(
            listOf("Rutube", "VK Видео", "VLC", "Кинопоиск"),
            choices.map(SimulcastAppChoice::label),
        )
        assertEquals(listOf("org.videolan.vlc"), SimulcastAppChoices.selected(choices))
        assertEquals(true, choices.all(SimulcastAppChoice::selectable))
    }

    @Test
    fun `a full selection greys what it cannot take, and a press off frees them again`() {
        val six = (1..7).map { app("p.$it", "App $it") }
        val chosen = (1..6).map { "p.$it" }

        val full = SimulcastAppChoices.of(six, "dev.denza.apps", chosen)
        assertEquals(listOf("p.7"), full.filterNot(SimulcastAppChoice::selectable).map { it.packageName })

        val freed = SimulcastAppChoices.withSelection(full, chosen.drop(1))
        assertEquals(true, freed.all(SimulcastAppChoice::selectable))
        assertEquals(chosen.drop(1), SimulcastAppChoices.selected(freed))
    }

    /**
     * Six chosen, one of them removed from the car, and the page read again with the marks it held:
     * the five left are marked, and the place the sixth took is free - the tiles do not say «full»
     * over a count of five.
     */
    @Test
    fun `a chosen application that left the car frees its place`() {
        val seven = (1..7).map { app("p.$it", "App $it") }
        val full = SimulcastAppChoices.of(seven, "dev.denza.apps", (1..6).map { "p.$it" })

        val reread = SimulcastAppChoices.of(
            seven.filterNot { it.packageName == "p.6" },
            "dev.denza.apps",
            selected = emptyList(),
        )
        val marked = SimulcastAppChoices.withSelection(reread, SimulcastAppChoices.selected(full))

        assertEquals((1..5).map { "p.$it" }, SimulcastAppChoices.selected(marked))
        assertEquals(true, marked.all(SimulcastAppChoice::selectable))
    }

    @Test
    fun `moving the marks keeps every tile whose marks did not move`() {
        val choices = SimulcastAppChoices.of(installed, "dev.denza.apps", selected = listOf("ru.kinopoisk"))

        val moved = SimulcastAppChoices.withSelection(choices, listOf("ru.kinopoisk", "ru.rutube.app"))

        assertEquals(choices.map(SimulcastAppChoice::packageName), moved.map(SimulcastAppChoice::packageName))
        assertSame(choices.single { it.packageName == "ru.kinopoisk" }, moved.single { it.packageName == "ru.kinopoisk" })
        assertEquals(true, moved.single { it.packageName == "ru.rutube.app" }.selected)
    }

    private fun app(packageName: String, label: String, launcher: Boolean = true) =
        InstalledDefaultApp(packageName, label, icon = null, launcher = launcher)
}
