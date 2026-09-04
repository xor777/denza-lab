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

    /**
     * The help names the commands a driver says, and still keeps the one promise it must not break:
     * music on this firmware is not started by "Открыть музыку".
     */
    @Test
    fun helpNamesTheFirmwareSpecificLaunchActionsWithoutPromisingOpenMusic() {
        assertEquals(
            "Штатные сценарии открывают приложения по команде: «Открыть навигацию» и " +
                "«Открыть видео» запускают выбранные здесь. Музыку на этой прошивке начинает " +
                "«Продолжить воспроизведение», когда ничего не играет; «Открыть музыку» всегда " +
                "открывает штатный плеер.",
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
        assertTrue(defaultAppsCanSelect(failed))
        assertFalse(
            defaultAppsCanSelect(failed.copy(status = DefaultAppRoleStatus.LOADING)),
        )
        assertFalse(
            defaultAppsCanSelect(failed.copy(status = DefaultAppRoleStatus.APPLYING)),
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
        assertFalse(defaultAppsChoicesDimmed(ready))
        assertFalse(
            defaultAppsChoicesDimmed(ready.copy(status = DefaultAppRoleStatus.APPLYING)),
        )
        assertTrue(
            defaultAppsChoicesDimmed(ready.copy(status = DefaultAppRoleStatus.LOADING)),
        )
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
            val text = defaultAppsStatusText(role.copy(status = status))
            assertTrue(status.toString(), text.isNotBlank())
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

    /** Off is a car running its own applications, never an absence of settings. */
    @Test
    fun theSwitchSaysWhatTheCarIsDoingRatherThanWhetherItIsOn() {
        val known = DefaultAppRole.MUSIC.knownThirdPartyApps.first()
        val switchable = DefaultAppRoleUiState(
            role = DefaultAppRole.MUSIC,
            selectedPackageName = DefaultAppRole.MUSIC.stockPackageName,
            status = DefaultAppRoleStatus.READY,
            providerConfirmed = true,
            choices = listOf(
                choice().copy(packageName = known.packageName, label = known.fallbackLabel),
            ),
        )
        val off = DefaultAppsUiState(roles = listOf(switchable))
        assertEquals("Команды открывают штатные приложения", defaultAppsSwitchSubtitle(off))

        val on = DefaultAppsUiState(
            roles = listOf(switchable.copy(selectedPackageName = known.packageName)),
        )
        assertEquals("Команды открывают выбранные приложения", defaultAppsSwitchSubtitle(on))

        val nothingInstalled = DefaultAppsUiState(
            roles = listOf(switchable.copy(choices = emptyList())),
        )
        assertEquals(
            "Нечем заменять: подходящие приложения не установлены",
            defaultAppsSwitchSubtitle(nothingInstalled),
        )

        assertEquals(
            "Команды открывают штатные приложения",
            defaultAppsSwitchSubtitle(off.copy(refreshing = true)),
        )
        assertEquals(
            "Читаем настройку…",
            defaultAppsSwitchSubtitle(
                off.update(DefaultAppRole.MUSIC) { role ->
                    role.copy(status = DefaultAppRoleStatus.LOADING)
                },
            ),
        )
    }

    @Test
    fun aReadyRoleRemainsInteractiveDuringRevalidation() {
        val ready = DefaultAppRoleUiState(
            role = DefaultAppRole.MUSIC,
            selectedPackageName = "ru.yandex.music",
            selectedLabel = "Яндекс Музыка",
            choices = listOf(choice().copy(selected = true)),
            status = DefaultAppRoleStatus.READY,
            providerConfirmed = true,
        )
        val refreshing = DefaultAppsUiState(roles = listOf(ready), refreshing = true)

        assertTrue(defaultAppsCanSelect(ready))
        assertFalse(defaultAppsChoicesDimmed(ready))
        assertTrue(refreshing.substituting || refreshing.canSubstitute)
    }

    /**
     * The row's icon follows the finger, and shows nothing rather than something wrong.
     *
     * A write in flight is where the role is going, so it wins over the package the provider last
     * confirmed - the same rule the grid's own mark follows. With nothing chosen, or with a chosen
     * package the catalog has not listed, the row carries its words alone: an icon standing in for
     * an application that may not be there is worse than no icon.
     */
    @Test
    fun theRowShowsWhereTheRoleIsGoingRatherThanWhereItHasBeen() {
        val music = choice()
        val navigator = choice().copy(
            packageName = "ru.yandex.yandexnavi",
            label = "Яндекс Навигатор",
        )
        val confirmed = DefaultAppRoleUiState(
            role = DefaultAppRole.MUSIC,
            selectedPackageName = music.packageName,
            choices = listOf(music, navigator),
            status = DefaultAppRoleStatus.READY,
            providerConfirmed = true,
        )
        assertEquals(music, defaultAppsRowChoice(confirmed))

        val writing = confirmed.copy(pendingPackageName = navigator.packageName)
        assertEquals(navigator, defaultAppsRowChoice(writing))

        assertEquals(null, defaultAppsRowChoice(confirmed.copy(selectedPackageName = null)))
        assertEquals(null, defaultAppsRowChoice(confirmed.copy(choices = emptyList())))
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
