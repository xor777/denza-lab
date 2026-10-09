package dev.denza.apps.feature.mirrors

import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureReducer
import dev.denza.apps.core.FeatureResolution
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.feature.cluster.ClusterDisplaySelection

/**
 * Mirrors render on the dedicated camera-overlay display, not on the base
 * instrument display used by navigation.
 */
object MirrorDisplayReadiness {
    /**
     * Ambiguous or absent, the cameras have no screen to go to. A state: the press switches the
     * mirrors on again, which looks for it again - «Повторите поиск экрана камер» asked the driver
     * to do what the press does, in 28 characters on a line that holds 17.
     */
    const val SCREEN_NOT_FOUND = "Экран не найден"

    fun snapshot(
        selection: ClusterDisplaySelection,
        active: Boolean,
    ): FeatureSnapshot = when (selection) {
        is ClusterDisplaySelection.Selected -> FeatureReducer.ready(
            FeatureId.MIRRORS,
            active = active,
        )
        is ClusterDisplaySelection.NeedsVerification -> FeatureReducer.needsAction(
            FeatureReducer.starting(FeatureId.MIRRORS),
            message = SCREEN_NOT_FOUND,
            details = "camera overlay display is ambiguous",
            resolution = FeatureResolution.RETRY,
        )
        ClusterDisplaySelection.Missing -> FeatureReducer.needsAction(
            FeatureReducer.starting(FeatureId.MIRRORS),
            message = SCREEN_NOT_FOUND,
            details = "camera overlay display not found",
            resolution = FeatureResolution.RETRY,
        )
    }
}
