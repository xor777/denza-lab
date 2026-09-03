package dev.denza.apps.feature.defaultapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultAppsCatalogTest {
    @Test
    fun choicesKeepKnownPriorityThenStockThenEveryOtherLauncher() {
        val installed = listOf(
            app("example.zebra", "Zebra"),
            app("com.byd.mediacenter", "BYD Media"),
            app("com.spotify.music", "Spotify"),
            app("example.alpha", "Alpha"),
            app("com.apple.android.music", "Apple Music"),
            app("ru.yandex.music", "Яндекс Музыка"),
        )

        val choices = DefaultAppsCatalog.choices(
            role = DefaultAppRole.MUSIC,
            selectedPackageName = "example.zebra",
            installed = installed,
        )

        assertEquals(
            listOf(
                "ru.yandex.music",
                "com.apple.android.music",
                "com.spotify.music",
                "com.byd.mediacenter",
                "example.alpha",
                "example.zebra",
            ),
            choices.map(DefaultAppChoice::packageName),
        )
        assertTrue(choices.single { it.packageName == "example.zebra" }.selected)
        assertTrue(choices.single { it.packageName == "ru.yandex.music" }.known)
        assertTrue(choices.single { it.packageName == "com.byd.mediacenter" }.stock)
        assertFalse(choices.single { it.packageName == "example.alpha" }.known)
    }

    @Test
    fun labelsRemainUsefulWhenTheProviderPackageIsNotLaunchable() {
        val installed = listOf(app("example.other", "Other"))

        assertEquals(
            "Яндекс Навигатор",
            DefaultAppsCatalog.label(
                DefaultAppRole.NAVIGATION,
                "ru.yandex.yandexnavi",
                installed,
            ),
        )
        assertEquals(
            "example.removed",
            DefaultAppsCatalog.label(
                DefaultAppRole.NAVIGATION,
                "example.removed",
                installed,
            ),
        )
        assertEquals(
            "Не выбрано",
            DefaultAppsCatalog.label(DefaultAppRole.NAVIGATION, null, installed),
        )
    }

    @Test
    fun denzaAppsRemainsAnOrdinarySelectableLauncher() {
        val installed = listOf(
            app(DefaultNavigationProxyContract.PACKAGE_NAME, "Denza Apps"),
            app("ru.yandex.yandexnavi", "Яндекс Навигатор"),
        )

        assertTrue(
            DefaultAppsCatalog.isLaunchable(
                DefaultNavigationProxyContract.PACKAGE_NAME,
                installed,
            ),
        )
        DefaultAppRole.entries.forEach { role ->
            assertTrue(
                DefaultNavigationProxyContract.PACKAGE_NAME in
                    DefaultAppsCatalog.choices(role, null, installed)
                        .map(DefaultAppChoice::packageName),
            )
        }
    }

    @Test
    fun uiStateReportsReadingAndConfiguredPerProviderValue() {
        val initial = DefaultAppsUiState()
        assertTrue(initial.reading)
        assertEquals(0, initial.configuredCount)

        val ready = initial.copy(
            roles = initial.roles.map { role ->
                if (role.role == DefaultAppRole.VIDEO) {
                    role.copy(
                        selectedPackageName = "com.vk.vkvideo",
                        status = DefaultAppRoleStatus.READY,
                        providerConfirmed = true,
                    )
                } else {
                    role.copy(status = DefaultAppRoleStatus.READY, providerConfirmed = true)
                }
            },
        )

        assertFalse(ready.reading)
        assertEquals(1, ready.configuredCount)
        assertFalse(ready.hasError)
    }

    /**
     * The switch is read off the car, and a write in flight counts as its target.
     *
     * The tile is the switch, so it has to answer the press before the provider has finished
     * hearing about it - and go back if the provider refuses, which is what leaves
     * `selectedPackageName` alone until a readback confirms.
     */
    @Test
    fun theSwitchFollowsTheCarAndTheWriteInFlight() {
        val stock = DefaultAppsUiState(
            roles = DefaultAppRole.entries.map { role ->
                DefaultAppRoleUiState(
                    role = role,
                    selectedPackageName = role.stockPackageName,
                    status = DefaultAppRoleStatus.READY,
                    providerConfirmed = true,
                    choices = listOf(knownChoice(role)),
                )
            },
        )
        assertFalse(stock.substituting)
        assertTrue(stock.canSubstitute)

        // Switching on: the target is pending, the provider still says stock, the tile is on.
        val turningOn = stock.update(DefaultAppRole.MUSIC) { role ->
            role.copy(pendingPackageName = role.role.knownThirdPartyApps.first().packageName)
        }
        assertTrue(turningOn.substituting)
        assertEquals(1, turningOn.configuredCount)

        // Switching off: the provider still holds the driver's application, the tile is off.
        val turningOff = DefaultAppsUiState(
            roles = DefaultAppRole.entries.map { role ->
                DefaultAppRoleUiState(
                    role = role,
                    selectedPackageName = role.knownThirdPartyApps.first().packageName,
                    pendingPackageName = role.stockPackageName,
                    status = DefaultAppRoleStatus.APPLYING,
                    providerConfirmed = true,
                    choices = listOf(knownChoice(role)),
                )
            },
        )
        assertFalse(turningOff.substituting)

        // Nothing of the catalog installed: there is nothing for the switch to turn on.
        val nothingInstalled = stock.copy(
            roles = stock.roles.map { it.copy(choices = emptyList()) },
        )
        assertFalse(nothingInstalled.canSubstitute)
    }

    private fun knownChoice(role: DefaultAppRole): DefaultAppChoice {
        val known = role.knownThirdPartyApps.first()
        return DefaultAppChoice(
            packageName = known.packageName,
            label = known.fallbackLabel,
            icon = null,
            selected = false,
            known = true,
            stock = false,
        )
    }

    private fun app(packageName: String, label: String): InstalledDefaultApp =
        InstalledDefaultApp(packageName = packageName, label = label, icon = null)
}
