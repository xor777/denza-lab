package dev.denza.apps.feature.hud

import dev.denza.apps.R
import dev.denza.apps.core.AppText
import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureReducer
import dev.denza.apps.core.FeatureResolution
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.core.FeatureStatus
import dev.denza.apps.core.FeatureWords
import dev.denza.apps.platform.accessibility.AccessibilityHealth

/**
 * The HUD tile's status, read from the switch and the preconditions, as the speakers' is.
 *
 * It used to be written inline in the repository, which is why nothing held its words to the tile:
 * «Яндекс Навигатор не найден» and «Повторите настройку доступа» were 26 and 27 characters on a line
 * that holds 17, and the second asked the driver to do what the press itself does.
 */
object HudGuidanceStatus {

    /** The hints come from Яндекс Навигатор alone; the panel's paragraph names it. */
    val NO_NAVIGATOR: String get() = AppText.s(R.string.hud_no_navigator)

    /**
     * The tile's status over the shared service as [AccessibilityHealth.read] finds it. A service
     * listed and never bound past the deadline is no access, as one switched off is
     * ([AccessibilityHealth.available]); until 2026-11-19 the repository passed the bare setting,
     * and such a service read «Подключаю подсказки» for good.
     */
    fun snapshot(
        enabled: Boolean,
        navigator: HudNavigatorInstall,
        accessibility: AccessibilityHealth,
        active: Boolean,
        details: () -> String?,
    ): FeatureSnapshot = snapshot(
        enabled = enabled,
        navigator = navigator,
        accessibilityAvailable = accessibility.available,
        accessibilityConnected = accessibility.connected,
        active = active,
        details = details,
    )

    /**
     * With a YandexHUD that publishes through the host the hints are on whatever the stored switch
     * says, and their path is the module's: its process, the host's Binder, this app's SOME/IP
     * session and, for the car's sign, the shell. None of it is the accessibility service or the
     * notification listener, which the capture of the ordinary Yandex reads with - with YandexHUD the
     * capture adds only Yandex's limit read off the screen, and the module's own limit wins over it.
     * So the tile is on and reads neither «Нет доступа» nor «Подключаю подсказки» for an access this
     * path does not use (2026-11-21).
     */
    fun snapshot(
        enabled: Boolean,
        navigator: HudNavigatorInstall,
        accessibilityAvailable: Boolean,
        accessibilityConnected: Boolean,
        active: Boolean,
        details: () -> String?,
    ): FeatureSnapshot = if (navigator == HudNavigatorInstall.YANDEX_HUD) {
        FeatureReducer.ready(FeatureId.HUD_GUIDANCE, active = active).copy(details = details())
    } else {
        snapshot(
            enabled = enabled,
            navigatorInstalled = navigator != HudNavigatorInstall.NONE,
            accessibilityAvailable = accessibilityAvailable,
            accessibilityConnected = accessibilityConnected,
            active = active,
            details = details,
        )
    }

    fun snapshot(
        enabled: Boolean,
        navigatorInstalled: Boolean,
        accessibilityAvailable: Boolean,
        accessibilityConnected: Boolean,
        active: Boolean,
        details: () -> String?,
    ): FeatureSnapshot = when {
        !enabled -> FeatureReducer.disabled(FeatureId.HUD_GUIDANCE)
        !navigatorInstalled -> FeatureSnapshot(
            id = FeatureId.HUD_GUIDANCE,
            desiredEnabled = true,
            status = FeatureStatus.UNAVAILABLE,
            message = NO_NAVIGATOR,
        )
        // The press switches the feature on again, which repairs the service.
        !accessibilityAvailable -> FeatureReducer.needsAction(
            FeatureReducer.starting(FeatureId.HUD_GUIDANCE),
            FeatureWords.NO_ACCESS,
            resolution = FeatureResolution.RETRY,
        )
        !accessibilityConnected -> FeatureReducer.recovering(
            FeatureReducer.starting(FeatureId.HUD_GUIDANCE),
            AppText.s(R.string.hud_connecting_hints),
        )
        else -> FeatureReducer.ready(FeatureId.HUD_GUIDANCE, active = active).copy(details = details())
    }
}
