package dev.denza.apps.ui

import dev.denza.apps.SimulcastAppChoice
import dev.denza.apps.feature.fse.FseInstallApp
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

    /**
     * The passenger chooser lists what can be installed and nothing else, and its foot says so.
     *
     * A split package used to be shown grey under a sentence explaining the grey; on the car the
     * owner saw no grey tile and a sentence about one. What cannot be put across is not in the
     * list, and the sentence says the list is not everything - in words a driver uses.
     */
    @Test
    fun thePassengerChooserHoldsOnlyWhatCanBeInstalledAndSaysSo() {
        val whole = fseApp("ru.rutube.app", "Rutube", installable = true)
        val split = fseApp("ru.kinopoisk", "Кинопоиск", installable = false)
        assertEquals(listOf(whole), fseChooserApps(listOf(whole, split)))
        assertEquals(
            "Показаны только приложения, которые можно поставить на экран справа. " +
                "Несовместимые в список не входят.",
            FSE_INSTALL_HELP,
        )
    }

    private fun app(packageName: String, label: String): SimulcastAppChoice = SimulcastAppChoice(
        packageName = packageName,
        label = label,
        icon = null,
        selected = true,
    )

    private fun fseApp(packageName: String, label: String, installable: Boolean): FseInstallApp =
        FseInstallApp(
            packageName = packageName,
            label = label,
            icon = null,
            versionName = "1.0",
            apkSizeBytes = 1L,
            installable = installable,
            unavailableReason = if (installable) "" else "Split APK пока не поддерживается",
        )
}
