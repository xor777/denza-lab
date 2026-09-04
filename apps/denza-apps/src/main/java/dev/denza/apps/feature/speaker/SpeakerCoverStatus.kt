package dev.denza.apps.feature.speaker

import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureReducer
import dev.denza.apps.core.FeatureResolution
import dev.denza.apps.core.FeatureSnapshot

/**
 * The tile's status, read the way every other feature reads its own: from the switch and from
 * the preconditions, never from what the service happens to be doing this second.
 *
 * The previous shape published a phase from the service on every event and mapped «a report is on
 * the wire» onto STARTING - the status the tiles draw as a spinner and mean as «switched on, not
 * working yet». Every window of every app, and every active session at start, passed through that
 * phase before the policy had even said no, so opening the app made the tile flicker. A one-off
 * action is not a status; it is a Boolean for the one button it greys, exactly as the stock
 * language keeps `running`.
 *
 * The one precondition is media-session access, because that is what lets the app see playback
 * at all. RETRY is served by the tile the same way HUD's is: it switches the feature on again,
 * which walks the service through the access repair.
 */
object SpeakerCoverStatus {
    const val ACCESS_MESSAGE = "Повторите настройку доступа"

    fun snapshot(enabled: Boolean, sessionsObservable: Boolean): FeatureSnapshot = when {
        !enabled -> FeatureReducer.disabled(FeatureId.SPEAKER_COVERS)
        !sessionsObservable -> FeatureReducer.needsAction(
            FeatureReducer.starting(FeatureId.SPEAKER_COVERS),
            ACCESS_MESSAGE,
            resolution = FeatureResolution.RETRY,
        )
        else -> FeatureReducer.ready(FeatureId.SPEAKER_COVERS)
    }
}
