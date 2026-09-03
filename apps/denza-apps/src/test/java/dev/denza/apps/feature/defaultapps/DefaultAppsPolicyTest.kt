package dev.denza.apps.feature.defaultapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultAppsPolicyTest {
    @Test
    fun rolesMatchTheAutoVoiceContract() {
        assertEquals(
            RoleContract("DEFAULT_MAP_SWITCH", 102000, "com.byd.launchermap"),
            DefaultAppRole.NAVIGATION.contract(),
        )
        assertEquals(
            RoleContract("MUSIC_SWITCH", 129003, "com.byd.mediacenter"),
            DefaultAppRole.MUSIC.contract(),
        )
        assertEquals(
            RoleContract("VIDEO_SWITCH", 131500, "com.byd.videoplay"),
            DefaultAppRole.VIDEO.contract(),
        )
    }

    @Test
    fun knownCatalogsHaveStableProductPriority() {
        assertEquals(
            listOf(
                "ru.yandex.yandexnavi",
                "ru.yandex.yandexmaps",
                "com.google.android.apps.maps",
                "com.waze",
                "ru.dublgis.dgismobile",
            ),
            DefaultAppRole.NAVIGATION.knownPackages(),
        )
        assertEquals(
            listOf(
                "ru.yandex.music",
                "com.apple.android.music",
                "com.spotify.music",
                "com.google.android.apps.youtube.music",
                "com.uma.musicvk",
                "org.videolan.vlc",
            ),
            DefaultAppRole.MUSIC.knownPackages(),
        )
        assertEquals(
            listOf(
                "com.vk.vkvideo",
                "ru.rutube.app",
                "ru.kinopoisk",
                "com.google.android.youtube",
                "ru.ivi.client",
                "ru.rt.video.app.mobile",
                "ru.mts.mtstv",
                "ru.start.androidmobile",
                "gpm.tnt_premier",
                "com.netflix.mediaclient",
                "com.amazon.avod.thirdpartyclient",
                "com.plexapp.android",
            ),
            DefaultAppRole.VIDEO.knownPackages(),
        )
        DefaultAppRole.entries.forEach { role ->
            assertEquals(role.knownPackages().distinct(), role.knownPackages())
            assertFalse(role.stockPackageName in role.knownPackages())
        }
    }

    @Test
    fun firstColdStartReplacesUntouchedStockWithFirstLaunchableKnownApp() {
        assertEquals(
            "ru.yandex.yandexnavi",
            resolve(
                role = DefaultAppRole.NAVIGATION,
                provider = "com.byd.launchermap",
                initializationHandled = false,
                launchable = listOf(
                    "com.byd.launchermap",
                    "com.waze",
                    "ru.yandex.yandexnavi",
                ),
            ),
        )
        assertEquals(
            "com.apple.android.music",
            resolve(
                role = DefaultAppRole.MUSIC,
                provider = "com.byd.mediacenter",
                initializationHandled = false,
                launchable = listOf("com.byd.mediacenter", "com.apple.android.music"),
            ),
        )
    }

    @Test
    fun catalogOrderNotDiscoveryOrderChoosesTheDefault() {
        val first = resolve(
            role = DefaultAppRole.VIDEO,
            provider = null,
            initializationHandled = false,
            launchable = listOf("ru.kinopoisk", "ru.rutube.app", "com.vk.vkvideo"),
        )
        val reversed = resolve(
            role = DefaultAppRole.VIDEO,
            provider = null,
            initializationHandled = false,
            launchable = listOf("com.vk.vkvideo", "ru.rutube.app", "ru.kinopoisk"),
        )

        assertEquals("com.vk.vkvideo", first)
        assertEquals(first, reversed)
    }

    @Test
    fun preExistingNonStockProviderChoiceIsPreservedBeforeInitialization() {
        assertEquals(
            "example.custom.player",
            resolve(
                role = DefaultAppRole.MUSIC,
                provider = "example.custom.player",
                initializationHandled = false,
                launchable = listOf("ru.yandex.music", "example.custom.player"),
            ),
        )
    }

    @Test
    fun automaticWriteBoundaryIsOnlyAnUntouchedStockRole() {
        assertTrue(
            DefaultAppsPolicy.shouldAutoApplyColdStartSelection(
                role = DefaultAppRole.MUSIC,
                providerPackageName = "com.byd.mediacenter",
                initializationHandled = false,
                resolvedPackageName = "ru.yandex.music",
            ),
        )
        assertFalse(
            DefaultAppsPolicy.shouldAutoApplyColdStartSelection(
                role = DefaultAppRole.MUSIC,
                providerPackageName = "example.external.player",
                initializationHandled = false,
                resolvedPackageName = "ru.yandex.music",
            ),
        )
        assertFalse(
            DefaultAppsPolicy.shouldAutoApplyColdStartSelection(
                role = DefaultAppRole.MUSIC,
                providerPackageName = "com.byd.mediacenter",
                initializationHandled = true,
                resolvedPackageName = "ru.yandex.music",
            ),
        )
        assertFalse(
            DefaultAppsPolicy.shouldAutoApplyColdStartSelection(
                role = DefaultAppRole.MUSIC,
                providerPackageName = "com.byd.mediacenter",
                initializationHandled = false,
                resolvedPackageName = "com.byd.mediacenter",
            ),
        )
    }

    @Test
    fun handledFirstRunPreservesStockEvenIfAKnownAppAppearsLater() {
        assertEquals(
            "com.byd.mediacenter",
            resolve(
                role = DefaultAppRole.MUSIC,
                provider = "com.byd.mediacenter",
                initializationHandled = true,
                launchable = listOf("com.byd.mediacenter", "ru.yandex.music"),
            ),
        )
        assertEquals(
            "example.custom.player",
            resolve(
                role = DefaultAppRole.MUSIC,
                provider = "example.custom.player",
                initializationHandled = true,
                launchable = listOf("example.custom.player", "ru.yandex.music"),
            ),
        )
    }

    @Test
    fun unavailableCurrentChoiceFallsBackToKnownThenStock() {
        assertEquals(
            "com.spotify.music",
            resolve(
                role = DefaultAppRole.MUSIC,
                provider = "example.removed.player",
                initializationHandled = true,
                launchable = listOf("com.spotify.music", "com.byd.mediacenter"),
            ),
        )
        assertEquals(
            "com.byd.mediacenter",
            resolve(
                role = DefaultAppRole.MUSIC,
                provider = "example.removed.player",
                initializationHandled = true,
                launchable = listOf("com.byd.mediacenter", "example.non.launchable"),
            ),
        )
        assertNull(
            resolve(
                role = DefaultAppRole.MUSIC,
                provider = "example.removed.player",
                initializationHandled = true,
                launchable = listOf("example.unrelated"),
            ),
        )
    }

    @Test
    fun pickerAdmissionUsesLaunchabilityRatherThanKnownCatalog() {
        val launchable = listOf("example.custom.player", "ru.yandex.music")

        assertTrue(DefaultAppsPolicy.isSelectable("example.custom.player", launchable))
        assertTrue(DefaultAppsPolicy.isSelectable("ru.yandex.music", launchable))
        assertFalse(DefaultAppsPolicy.isSelectable("example.not.installed", launchable))
        assertFalse(DefaultAppsPolicy.isSelectable("", launchable))
    }

    @Test
    fun onlyNonStockNavigationUsesTheStableProxyProvider() {
        assertEquals(
            DefaultNavigationProxyContract.PACKAGE_NAME,
            DefaultNavigationProxyContract.providerPackageName(
                DefaultAppRole.NAVIGATION,
                "ru.yandex.yandexnavi",
            ),
        )
        assertEquals(
            DefaultAppRole.NAVIGATION.stockPackageName,
            DefaultNavigationProxyContract.providerPackageName(
                DefaultAppRole.NAVIGATION,
                DefaultAppRole.NAVIGATION.stockPackageName,
            ),
        )
        assertEquals(
            "ru.yandex.music",
            DefaultNavigationProxyContract.providerPackageName(
                DefaultAppRole.MUSIC,
                "ru.yandex.music",
            ),
        )
    }

    @Test
    fun proxyProviderResolvesBackToTheConfiguredNavigationSelection() {
        assertEquals(
            "example.custom.navigation",
            DefaultNavigationProxyContract.selectedPackageName(
                role = DefaultAppRole.NAVIGATION,
                providerPackageName = DefaultNavigationProxyContract.PACKAGE_NAME,
                configuredProxyTarget = "example.custom.navigation",
            ),
        )
        assertEquals(
            DefaultNavigationProxyContract.PACKAGE_NAME,
            DefaultNavigationProxyContract.selectedPackageName(
                role = DefaultAppRole.NAVIGATION,
                providerPackageName = DefaultNavigationProxyContract.PACKAGE_NAME,
                configuredProxyTarget = DefaultNavigationProxyContract.PACKAGE_NAME,
            ),
        )
        assertEquals(
            "ru.yandex.music",
            DefaultNavigationProxyContract.selectedPackageName(
                role = DefaultAppRole.MUSIC,
                providerPackageName = "ru.yandex.music",
                configuredProxyTarget = "example.unused",
            ),
        )
    }

    @Test
    fun launchableDirectNavigationChoiceMigratesButOtherRolesDoNot() {
        val launchable = listOf("ru.yandex.yandexnavi", "ru.yandex.music")

        assertEquals(
            "ru.yandex.yandexnavi",
            DefaultNavigationProxyContract.directMigrationTarget(
                DefaultAppRole.NAVIGATION,
                "ru.yandex.yandexnavi",
                launchable,
            ),
        )
        assertNull(
            DefaultNavigationProxyContract.directMigrationTarget(
                DefaultAppRole.MUSIC,
                "ru.yandex.music",
                launchable,
            ),
        )
        assertNull(
            DefaultNavigationProxyContract.directMigrationTarget(
                DefaultAppRole.NAVIGATION,
                "example.removed.navigation",
                launchable,
            ),
        )
        assertNull(
            DefaultNavigationProxyContract.directMigrationTarget(
                DefaultAppRole.NAVIGATION,
                DefaultNavigationProxyContract.PACKAGE_NAME,
                launchable + DefaultNavigationProxyContract.PACKAGE_NAME,
            ),
        )
    }

    @Test
    fun replacementRepairRequiresStockRowAndBothLaunchablePackages() {
        val launchable = listOf(
            DefaultNavigationProxyContract.PACKAGE_NAME,
            "ru.yandex.yandexnavi",
        )

        assertEquals(
            "ru.yandex.yandexnavi",
            DefaultNavigationProxyContract.repairTarget(
                role = DefaultAppRole.NAVIGATION,
                providerPackageName = DefaultAppRole.NAVIGATION.stockPackageName,
                repairRequested = true,
                configuredProxyTarget = "ru.yandex.yandexnavi",
                installedLaunchablePackages = launchable,
            ),
        )
        assertNull(
            DefaultNavigationProxyContract.repairTarget(
                role = DefaultAppRole.NAVIGATION,
                providerPackageName = DefaultAppRole.NAVIGATION.stockPackageName,
                repairRequested = false,
                configuredProxyTarget = "ru.yandex.yandexnavi",
                installedLaunchablePackages = launchable,
            ),
        )
        assertNull(
            DefaultNavigationProxyContract.repairTarget(
                role = DefaultAppRole.NAVIGATION,
                providerPackageName = "example.external.navigation",
                repairRequested = true,
                configuredProxyTarget = "ru.yandex.yandexnavi",
                installedLaunchablePackages = launchable,
            ),
        )
        assertNull(
            DefaultNavigationProxyContract.repairTarget(
                role = DefaultAppRole.NAVIGATION,
                providerPackageName = DefaultAppRole.NAVIGATION.stockPackageName,
                repairRequested = true,
                configuredProxyTarget = "ru.yandex.yandexnavi",
                installedLaunchablePackages = listOf("ru.yandex.yandexnavi"),
            ),
        )
    }

    @Test
    fun pendingRepairIsRequestedOnItsOwn() {
        assertTrue(
            DefaultNavigationProxyContract.repairRequested(
                repairPending = true,
                proxyActive = false,
                confirmedUpdateTime = 0L,
                currentUpdateTime = 0L,
            ),
        )
    }

    @Test
    fun activeProxyRequestsRepairWhenThePackageUpdateTimeChanged() {
        assertTrue(
            DefaultNavigationProxyContract.repairRequested(
                repairPending = false,
                proxyActive = true,
                confirmedUpdateTime = 100L,
                currentUpdateTime = 200L,
            ),
        )
    }

    @Test
    fun activeProxyDoesNotRequestRepairAtTheConfirmedUpdateTime() {
        assertFalse(
            DefaultNavigationProxyContract.repairRequested(
                repairPending = false,
                proxyActive = true,
                confirmedUpdateTime = 100L,
                currentUpdateTime = 100L,
            ),
        )
    }

    @Test
    fun inactiveProxyDoesNotRequestRepairWhenThePackageUpdateTimeChanged() {
        assertFalse(
            DefaultNavigationProxyContract.repairRequested(
                repairPending = false,
                proxyActive = false,
                confirmedUpdateTime = 100L,
                currentUpdateTime = 200L,
            ),
        )
    }

    @Test
    fun unconfirmedUpdateTimeDoesNotRequestRepair() {
        assertFalse(
            DefaultNavigationProxyContract.repairRequested(
                repairPending = false,
                proxyActive = true,
                confirmedUpdateTime = 0L,
                currentUpdateTime = 200L,
            ),
        )
    }

    private fun resolve(
        role: DefaultAppRole,
        provider: String?,
        initializationHandled: Boolean,
        launchable: Collection<String>,
    ): String? = DefaultAppsPolicy.coldStartSelection(
        role = role,
        providerPackageName = provider,
        initializationHandled = initializationHandled,
        installedLaunchablePackages = launchable,
    )

    private fun DefaultAppRole.contract(): RoleContract = RoleContract(
        roleKey = roleKey,
        verifiedRuntimeCommandId = verifiedRuntimeCommandId,
        stockPackageName = stockPackageName,
    )

    private data class RoleContract(
        val roleKey: String,
        val verifiedRuntimeCommandId: Int,
        val stockPackageName: String,
    )

    private fun DefaultAppRole.knownPackages(): List<String> =
        knownThirdPartyApps.map(DefaultAppDefinition::packageName)
}
