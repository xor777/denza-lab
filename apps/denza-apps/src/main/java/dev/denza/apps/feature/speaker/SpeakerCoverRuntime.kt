package dev.denza.apps.feature.speaker

import android.content.Context
import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureReducer
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.core.FeatureStatus

enum class SpeakerCoverRuntimePhase {
    STOPPED,
    MONITORING,
    COMMANDING,
}

data class SpeakerCoverRuntimeState(
    val phase: SpeakerCoverRuntimePhase = SpeakerCoverRuntimePhase.STOPPED,
    val message: String = "",
)

object SpeakerCoverRuntime {
    @Volatile
    private var current = SpeakerCoverRuntimeState()

    fun snapshot(): SpeakerCoverRuntimeState = current

    fun publish(state: SpeakerCoverRuntimeState) {
        current = state
    }

    /**
     * Whether a report is on the wire right now - asked without reference to the toggle.
     *
     * [featureSnapshot] cannot answer this. With the automation off it returns `disabled` before it
     * ever looks at the phase, which is right for the tile and wrong for «Поднять» underneath it,
     * which works either way and runs the same second-long adb call either way.
     */
    fun commanding(): Boolean = snapshot().phase == SpeakerCoverRuntimePhase.COMMANDING

    /**
     * On, off, or busy for the second a report takes. There is no failure state: a report that did
     * not land is logged, and whether the car can be reached at all is the «Сервис» tile's news,
     * not this feature's.
     */
    fun featureSnapshot(context: Context): FeatureSnapshot {
        if (!SpeakerCoverSettings.isEnabled(context)) {
            return FeatureReducer.disabled(FeatureId.SPEAKER_COVERS)
        }
        val runtime = snapshot()
        return when (runtime.phase) {
            SpeakerCoverRuntimePhase.STOPPED,
            SpeakerCoverRuntimePhase.MONITORING,
            -> FeatureSnapshot(
                id = FeatureId.SPEAKER_COVERS,
                desiredEnabled = true,
                status = FeatureStatus.READY,
                message = runtime.message.ifBlank { WATCHING },
            )
            SpeakerCoverRuntimePhase.COMMANDING -> FeatureSnapshot(
                id = FeatureId.SPEAKER_COVERS,
                desiredEnabled = true,
                status = FeatureStatus.STARTING,
                message = runtime.message.ifBlank { "Говорю машине, что играет музыка" },
            )
        }
    }

    const val WATCHING = "Динамики выедут под музыку"
}
