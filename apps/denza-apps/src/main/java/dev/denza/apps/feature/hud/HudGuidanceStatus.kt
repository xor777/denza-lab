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
        navigatorInstalled: Boolean,
        accessibility: AccessibilityHealth,
        active: Boolean,
        details: () -> String?,
    ): FeatureSnapshot = snapshot(
        enabled = enabled,
        navigatorInstalled = navigatorInstalled,
        accessibilityAvailable = accessibility.available,
        accessibilityConnected = accessibility.connected,
        active = active,
        details = details,
    )

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
