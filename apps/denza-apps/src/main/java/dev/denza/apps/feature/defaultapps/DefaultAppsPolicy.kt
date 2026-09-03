package dev.denza.apps.feature.defaultapps

/** A launcher that is worth offering first for one of the stock AutoVoice roles. */
data class DefaultAppDefinition(
    val packageName: String,
    val fallbackLabel: String,
)

/**
 * The three AutoVoice default-app roles addressed by stock Shortcuts actions.
 *
 * [roleKey] is the exact `PersonBean.SETTING` value read by AutoVoice.
 * [verifiedRuntimeCommandId] is live diagnostic evidence for an action that honored that role; the
 * product never sends it. Shortcuts catalog/source ids are deliberately not modeled because this
 * firmware normalizes them before execution and similarly named actions need not honor the role.
 * The setting is separate from `Settings.Global.byd_map_package`, which is not part of this
 * feature. The catalog is an ordering policy for a first-run default, not an allowlist: the UI may
 * persist any installed application that has a launcher.
 */
enum class DefaultAppRole(
    val roleKey: String,
    val verifiedRuntimeCommandId: Int,
    val stockPackageName: String,
    val knownThirdPartyApps: List<DefaultAppDefinition>,
) {
    NAVIGATION(
        roleKey = "DEFAULT_MAP_SWITCH",
        verifiedRuntimeCommandId = 102000,
        stockPackageName = "com.byd.launchermap",
        knownThirdPartyApps = listOf(
            DefaultAppDefinition("ru.yandex.yandexnavi", "Яндекс Навигатор"),
            DefaultAppDefinition("ru.yandex.yandexmaps", "Яндекс Карты"),
            DefaultAppDefinition("com.google.android.apps.maps", "Google Maps"),
            DefaultAppDefinition("com.waze", "Waze"),
            DefaultAppDefinition("ru.dublgis.dgismobile", "2ГИС"),
        ),
    ),
    MUSIC(
        roleKey = "MUSIC_SWITCH",
        // Live "Continue playing" honored MUSIC_SWITCH. Current "Open" (129136) did not.
        verifiedRuntimeCommandId = 129003,
        stockPackageName = "com.byd.mediacenter",
        knownThirdPartyApps = listOf(
            DefaultAppDefinition("ru.yandex.music", "Яндекс Музыка"),
            DefaultAppDefinition("com.apple.android.music", "Apple Music"),
            DefaultAppDefinition("com.spotify.music", "Spotify"),
            DefaultAppDefinition("com.google.android.apps.youtube.music", "YouTube Music"),
            DefaultAppDefinition("com.uma.musicvk", "VK Музыка"),
            DefaultAppDefinition("org.videolan.vlc", "VLC"),
        ),
    ),
    VIDEO(
        roleKey = "VIDEO_SWITCH",
        // Live "Open Video" executed as 131500; reverse catalog data may contain 131501.
        verifiedRuntimeCommandId = 131500,
        stockPackageName = "com.byd.videoplay",
        knownThirdPartyApps = listOf(
            DefaultAppDefinition("com.vk.vkvideo", "VK Видео"),
            DefaultAppDefinition("ru.rutube.app", "RUTUBE"),
            DefaultAppDefinition("ru.kinopoisk", "Кинопоиск"),
            DefaultAppDefinition("com.google.android.youtube", "YouTube"),
            DefaultAppDefinition("ru.ivi.client", "Иви"),
            DefaultAppDefinition("ru.rt.video.app.mobile", "Смотрим"),
            DefaultAppDefinition("ru.mts.mtstv", "KION"),
            DefaultAppDefinition("ru.start.androidmobile", "START"),
            DefaultAppDefinition("gpm.tnt_premier", "PREMIER"),
            DefaultAppDefinition("com.netflix.mediaclient", "Netflix"),
            DefaultAppDefinition("com.amazon.avod.thirdpartyclient", "Prime Video"),
            DefaultAppDefinition("com.plexapp.android", "Plex"),
        ),
    ),
}

/**
 * Stable package indirection for the navigation role.
 *
 * AutoVoice clears a role when its exact package receives PACKAGE_REMOVED, including the
 * remove-half of an APK replacement. Keeping the Denza Apps package in PersonBean lets any
 * selected navigation application be replaced without touching the role. Music and video keep
 * their existing direct-package contract.
 */
object DefaultNavigationProxyContract {
    const val PACKAGE_NAME = "dev.denza.apps"

    fun repairRequested(
        repairPending: Boolean,
        proxyActive: Boolean,
        confirmedUpdateTime: Long,
        currentUpdateTime: Long,
    ): Boolean = repairPending || (
        proxyActive && confirmedUpdateTime != 0L && currentUpdateTime != confirmedUpdateTime
    )

    fun providerPackageName(role: DefaultAppRole, selectedPackageName: String): String {
        require(selectedPackageName.isNotBlank()) { "Default-app selection must not be blank" }
        return if (role == DefaultAppRole.NAVIGATION &&
            selectedPackageName != role.stockPackageName
        ) {
            require(isValidTarget(selectedPackageName)) {
                "Navigation proxy cannot target $selectedPackageName"
            }
            PACKAGE_NAME
        } else {
            selectedPackageName
        }
    }

    fun selectedPackageName(
        role: DefaultAppRole,
        providerPackageName: String?,
        configuredProxyTarget: String?,
    ): String? = if (
        role == DefaultAppRole.NAVIGATION && providerPackageName == PACKAGE_NAME
    ) {
        configuredProxyTarget?.takeIf(::isValidTarget)
    } else {
        providerPackageName
    }

    fun directMigrationTarget(
        role: DefaultAppRole,
        providerPackageName: String?,
        installedLaunchablePackages: Collection<String>,
    ): String? = providerPackageName?.takeIf { candidate ->
        role == DefaultAppRole.NAVIGATION &&
            candidate != PACKAGE_NAME &&
            isValidTarget(candidate) &&
            candidate in installedLaunchablePackages
    }

    fun repairTarget(
        role: DefaultAppRole,
        providerPackageName: String?,
        repairRequested: Boolean,
        configuredProxyTarget: String?,
        installedLaunchablePackages: Collection<String>,
    ): String? = configuredProxyTarget?.takeIf { candidate ->
        role == DefaultAppRole.NAVIGATION &&
            providerPackageName == role.stockPackageName &&
            repairRequested &&
            isValidTarget(candidate) &&
            PACKAGE_NAME in installedLaunchablePackages &&
            candidate in installedLaunchablePackages
    }

    fun isValidTarget(packageName: String): Boolean =
        packageName.isNotBlank() &&
            packageName != DefaultAppRole.NAVIGATION.stockPackageName
}

/** Pure selection rules; Android discovery and persistence stay at the integration boundary. */
object DefaultAppsPolicy {
    /**
     * Resolves the value to show and persist when the feature is opened after a cold start.
     *
     * AutoVoice starts with a stock package in every role, so that value cannot tell us whether
     * the driver deliberately selected the stock app. [initializationHandled] supplies the
     * missing distinction:
     *
     * - an explicit, still-launchable selection is preserved, including the stock app;
     * - a still-launchable non-stock provider value is preserved even before this product has
     *   handled the role, so an existing external choice is not overwritten;
     * - otherwise the first launchable known third-party app wins by catalog order;
     * - the stock app is the final launchable fallback.
     *
     * `null` means that neither a usable current value nor a known/stock launch target is present.
     * Input iteration order has no effect on the result.
     */
    fun coldStartSelection(
        role: DefaultAppRole,
        providerPackageName: String?,
        initializationHandled: Boolean,
        installedLaunchablePackages: Collection<String>,
    ): String? {
        val launchable = installedLaunchablePackages
            .asSequence()
            .filter(String::isNotBlank)
            .toHashSet()
        val current = providerPackageName?.takeIf(String::isNotBlank)
        val currentWasChosen = initializationHandled || current != role.stockPackageName

        if (current != null && currentWasChosen && current in launchable) return current

        return role.knownThirdPartyApps
            .firstOrNull { it.packageName in launchable }
            ?.packageName
            ?: role.stockPackageName.takeIf { it in launchable }
    }

    /**
     * Whether a cold-start resolution may be written back without a driver gesture.
     *
     * Selection resolution can produce a useful fallback for an unavailable current package, but
     * the product mutation boundary is deliberately narrower: only an untouched stock value may
     * be replaced automatically. A non-stock provider value may have been selected outside Denza
     * Apps, and a stock value observed after initialization has been handled is no longer eligible
     * for a later automatic switch. Both stay visible as-is; if unavailable, the UI reports that
     * role as an error and lets the driver choose a launcher.
     */
    fun shouldAutoApplyColdStartSelection(
        role: DefaultAppRole,
        providerPackageName: String?,
        initializationHandled: Boolean,
        resolvedPackageName: String?,
    ): Boolean =
        !initializationHandled &&
            providerPackageName == role.stockPackageName &&
            resolvedPackageName != null &&
            resolvedPackageName != providerPackageName

    /** Catalog membership never gates the picker; every installed launcher is selectable. */
    fun isSelectable(
        packageName: String,
        installedLaunchablePackages: Collection<String>,
    ): Boolean = packageName.isNotBlank() && packageName in installedLaunchablePackages
}
