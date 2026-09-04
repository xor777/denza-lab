package dev.denza.apps.ui

import dev.denza.apps.SimulcastAppChoice
import org.junit.Assert.assertEquals
import org.junit.Test

class AppPickersTest {

    /**
     * The allowance is said once, and it is the one the row storage enforces.
     *
     * Six is `SimulcastApps.MAX_SELECTED`, read through
     * [dev.denza.apps.SIMULCAST_MAX_SELECTED] rather than typed into the sentence: a number that
     * appears twice is a number that will one day appear as two.
     */
    @Test
    fun theChooserSaysHowManyItWillCarryAndHowManyArePicked() {
        assertEquals("Можно выбрать до 6 · выбрано 3", simulcastChooserSubtitle(3))
        assertEquals("Можно выбрать до 6 · выбрано 0", simulcastChooserSubtitle(0))
    }

    /**
     * The row says something only when the icons cannot.
     *
     * With applications chosen, their icons are the answer and a line repeating them in words is
     * the kind of text that gets read once. With none, there is nothing to look at, so the row
     * says so as a state - never as a complaint about an empty list.
     */
    @Test
    fun theRowSpeaksOnlyWhenThereAreNoIconsToSpeakFor() {
        assertEquals("Ничего не выбрано", simulcastChoiceValue(emptyList()))
        assertEquals("", simulcastChoiceValue(listOf(app("ru.rutube.app", "Rutube"))))
        assertEquals(
            "",
            simulcastChoiceValue(
                listOf(app("ru.rutube.app", "Rutube"), app("ru.kinopoisk", "Кинопоиск")),
            ),
        )
    }

    private fun app(packageName: String, label: String): SimulcastAppChoice = SimulcastAppChoice(
        packageName = packageName,
        label = label,
        icon = null,
        selected = true,
    )
}
