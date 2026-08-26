package dev.denza.apps.feature.speaker

import android.content.Context
import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureReducer
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.core.FeatureStatus

enum class SpeakerCoverRuntimePhase {
    STOPPED,
    STARTING,
    MONITORING,
    COMMANDING,
    DEGRADED,
}

data class SpeakerCoverRuntimeState(
    val phase: SpeakerCoverRuntimePhase = SpeakerCoverRuntimePhase.STOPPED,
    val position: SpeakerCoverPosition = SpeakerCoverPosition.UNKNOWN,
    val message: String = "",
    val details: String? = null,
)

object SpeakerCoverRuntime {
    @Volatile
    private var current = SpeakerCoverRuntimeState()

    fun snapshot(): SpeakerCoverRuntimeState = current

    fun publish(state: SpeakerCoverRuntimeState) {
        current = state
    }

    fun featureSnapshot(context: Context): FeatureSnapshot {
        if (!SpeakerCoverSettings.isEnabled(context)) {
            return FeatureReducer.disabled(FeatureId.SPEAKER_COVERS)
        }
        val runtime = snapshot()
        return when (runtime.phase) {
            SpeakerCoverRuntimePhase.STOPPED,
            SpeakerCoverRuntimePhase.STARTING,
            -> FeatureSnapshot(
                id = FeatureId.SPEAKER_COVERS,
                desiredEnabled = true,
                status = FeatureStatus.STARTING,
                message = runtime.message.ifBlank { "Запускаю наблюдение" },
                details = runtime.details,
            )
            SpeakerCoverRuntimePhase.MONITORING -> FeatureSnapshot(
                id = FeatureId.SPEAKER_COVERS,
                desiredEnabled = true,
                status = if (runtime.position == SpeakerCoverPosition.OPEN) {
                    FeatureStatus.ACTIVE
                } else {
                    FeatureStatus.READY
                },
                message = runtime.message.ifBlank { "Автоматика работает" },
                details = runtime.details,
            )
            SpeakerCoverRuntimePhase.COMMANDING -> FeatureSnapshot(
                id = FeatureId.SPEAKER_COVERS,
                desiredEnabled = true,
                status = FeatureStatus.STARTING,
                message = runtime.message.ifBlank { "Управляю крышками" },
                details = runtime.details,
            )
            SpeakerCoverRuntimePhase.DEGRADED -> FeatureSnapshot(
                id = FeatureId.SPEAKER_COVERS,
                desiredEnabled = true,
                status = FeatureStatus.RECOVERING,
                message = runtime.message.ifBlank { "Восстанавливаю автоматику" },
                details = runtime.details,
            )
        }
    }
}
