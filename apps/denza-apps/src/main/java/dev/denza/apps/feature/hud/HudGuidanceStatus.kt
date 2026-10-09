package dev.denza.apps.feature.hud

import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureReducer
import dev.denza.apps.core.FeatureResolution
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.core.FeatureStatus
import dev.denza.apps.core.FeatureWords

/**
 * The HUD tile's status, read from the switch and the preconditions, as the speakers' is.
 *
 * It used to be written inline in the repository, which is why nothing held its words to the tile:
 * «Яндекс Навигатор не найден» and «Повторите настройку доступа» were 26 and 27 characters on a line
 * that holds 17, and the second asked the driver to do what the press itself does.
 */
object HudGuidanceStatus {

    /** The hints come from Яндекс Навигатор alone; the panel's paragraph names it. */
    const val NO_NAVIGATOR = "Нет навигатора"

    fun snapshot(
        enabled: Boolean,
        navigatorInstalled: Boolean,
        accessibilityEnabled: Boolean,
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
        !accessibilityEnabled -> FeatureReducer.needsAction(
            FeatureReducer.starting(FeatureId.HUD_GUIDANCE),
            FeatureWords.NO_ACCESS,
            resolution = FeatureResolution.RETRY,
        )
        !accessibilityConnected -> FeatureReducer.recovering(
            FeatureReducer.starting(FeatureId.HUD_GUIDANCE),
            "Подключаю подсказки",
        )
        else -> FeatureReducer.ready(FeatureId.HUD_GUIDANCE, active = active).copy(details = details())
    }
}
