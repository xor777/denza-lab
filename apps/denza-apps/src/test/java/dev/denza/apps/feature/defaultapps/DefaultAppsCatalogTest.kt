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

    private fun app(packageName: String, label: String): InstalledDefaultApp =
        InstalledDefaultApp(packageName = packageName, label = label, icon = null)
}
