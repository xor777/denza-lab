package dev.denza.apps.ui.dashboard

import dev.denza.apps.feature.defaultapps.DefaultAppRole
import dev.denza.apps.feature.defaultapps.DefaultAppChoice
import dev.denza.apps.feature.defaultapps.DefaultAppRoleStatus
import dev.denza.apps.feature.defaultapps.DefaultAppRoleUiState
import dev.denza.apps.feature.defaultapps.DefaultAppsUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultAppsSheetTest {

    @Test
    fun theThreeShortcutsRolesKeepTheBoardLabels() {
        assertEquals("Навигация", defaultAppRoleLabel(DefaultAppRole.NAVIGATION))
        assertEquals("Музыка", defaultAppRoleLabel(DefaultAppRole.MUSIC))
        assertEquals("Видео", defaultAppRoleLabel(DefaultAppRole.VIDEO))

        assertEquals(
            "Приложение для навигации",
            defaultAppSectionTitle(DefaultAppRole.NAVIGATION),
        )
        assertEquals("Приложение для музыки", defaultAppSectionTitle(DefaultAppRole.MUSIC))
        assertEquals("Приложение для видео", defaultAppSectionTitle(DefaultAppRole.VIDEO))
    }

    @Test
    fun helpNamesTheFirmwareSpecificLaunchActionsWithoutPromisingOpenMusic() {
        assertEquals(
            "Shortcuts: Open Navigation открывает навигацию, Open Video — видео. " +
                "На этой прошивке музыку запускает Continue playing, если нет активной " +
                "медиасессии. Open Music открывает штатную музыку; остальные команды " +
                "управляют текущей медиасессией.",
            DEFAULT_APPS_SHORTCUTS_HELP,
        )
    }

    @Test
    fun anErrorWithKnownChoicesRemainsRecoverableButBusyStatesDoNot() {
        val failed = DefaultAppRoleUiState(
            role = DefaultAppRole.MUSIC,
            choices = listOf(choice()),
            status = DefaultAppRoleStatus.ERROR,
        )
        assertTrue(defaultAppsCanSelect(DefaultAppsUiState(roles = listOf(failed)), failed))
        assertFalse(
            defaultAppsCanSelect(
                DefaultAppsUiState(roles = listOf(failed), refreshing = true),
                failed,
            ),
        )
        assertFalse(
            defaultAppsCanSelect(
                DefaultAppsUiState(),
                failed.copy(status = DefaultAppRoleStatus.LOADING),
            ),
        )
        assertFalse(
            defaultAppsCanSelect(
                DefaultAppsUiState(),
                failed.copy(status = DefaultAppRoleStatus.APPLYING),
            ),
        )
    }

    /**
     * Storing a choice is not a reason to show the list as unavailable.
     *
     * The grid used to be greyed out by the same flag that greys it while the provider is being
     * read, so every write faded twenty-odd tiles and brought them back.
     */
    @Test
    fun onlyAReadShowsTheGridAsNotYetAnsweringForTheCar() {
        val ready = DefaultAppRoleUiState(
            role = DefaultAppRole.MUSIC,
            choices = listOf(choice()),
            status = DefaultAppRoleStatus.READY,
        )
        assertFalse(defaultAppsChoicesDimmed(DefaultAppsUiState(), ready))
        assertFalse(
            defaultAppsChoicesDimmed(
                DefaultAppsUiState(),
                ready.copy(status = DefaultAppRoleStatus.APPLYING),
            ),
        )
        assertTrue(
            defaultAppsChoicesDimmed(
                DefaultAppsUiState(),
                ready.copy(status = DefaultAppRoleStatus.LOADING),
            ),
        )
        assertTrue(defaultAppsChoicesDimmed(DefaultAppsUiState(refreshing = true), ready))
    }

    /**
     * The line under the segments always has text.
     *
     * [dev.denza.apps.ui.components.DenzaStatusLine] draws nothing for a blank string, so a status
     * that falls silent when a read or a write finishes takes a line and its gap out of the middle
     * of the panel and everything below it moves.
     */
    @Test
    fun theStatusLineIsNeverBlankInAnyState() {
        val role = DefaultAppRoleUiState(
            role = DefaultAppRole.MUSIC,
            selectedPackageName = "ru.yandex.music",
            selectedLabel = "Яндекс Музыка",
            providerConfirmed = true,
        )
        DefaultAppRoleStatus.entries.forEach { status ->
            listOf(false, true).forEach { refreshing ->
                val state = DefaultAppsUiState(refreshing = refreshing)
                val text = defaultAppsStatusText(state, role.copy(status = status))
                assertTrue("$status/$refreshing", text.isNotBlank())
            }
        }
    }

    @Test
    fun anUnconfirmedProviderValueIsNeverPresentedAsWhatTheCarWouldOpen() {
        val stale = DefaultAppRoleUiState(
            role = DefaultAppRole.MUSIC,
            selectedPackageName = "ru.yandex.music",
            selectedLabel = "Яндекс Музыка",
            status = DefaultAppRoleStatus.ERROR,
            providerConfirmed = false,
        )
        assertEquals("Последнее известное: Яндекс Музыка", defaultAppsOutcomeText(stale))
        assertFalse(stale.configured)

        val unknown = stale.copy(selectedPackageName = null, selectedLabel = "Не выбрано")
        assertEquals("Не подтверждено", defaultAppsOutcomeText(unknown))

        val confirmed = stale.copy(status = DefaultAppRoleStatus.READY, providerConfirmed = true)
        assertEquals("Запустит Яндекс Музыка", defaultAppsOutcomeText(confirmed))
        assertEquals(
            "Откроет Яндекс Навигатор",
            defaultAppsOutcomeText(
                confirmed.copy(
                    role = DefaultAppRole.NAVIGATION,
                    selectedPackageName = "ru.yandex.yandexnavi",
                    selectedLabel = "Яндекс Навигатор",
                ),
            ),
        )
    }

    private fun choice(): DefaultAppChoice = DefaultAppChoice(
        packageName = "ru.yandex.music",
        label = "Яндекс Музыка",
        icon = null,
        selected = false,
        known = true,
        stock = false,
    )
}
