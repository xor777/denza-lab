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
        val choice = DefaultAppChoice(
            packageName = "ru.yandex.music",
            label = "Яндекс Музыка",
            icon = null,
            selected = false,
            known = true,
            stock = false,
        )
        val failed = DefaultAppRoleUiState(
            role = DefaultAppRole.MUSIC,
            choices = listOf(choice),
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

    @Test
    fun anUnconfirmedProviderValueIsNeverPresentedAsCurrent() {
        val stale = DefaultAppRoleUiState(
            role = DefaultAppRole.MUSIC,
            selectedPackageName = "ru.yandex.music",
            selectedLabel = "Яндекс Музыка",
            status = DefaultAppRoleStatus.ERROR,
            providerConfirmed = false,
        )
        assertEquals("Последнее известное", defaultAppsSelectionLabel(stale))
        assertEquals("Яндекс Музыка", defaultAppsSelectionValue(stale))
        assertFalse(stale.configured)

        val unknown = stale.copy(selectedPackageName = null, selectedLabel = "Не выбрано")
        assertEquals("Статус", defaultAppsSelectionLabel(unknown))
        assertEquals("Не подтверждено", defaultAppsSelectionValue(unknown))
    }
}
