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
            // Switched on and nothing running yet is not the same as starting up. Both used to
            // report STARTING, which draws a spinner on the tile - and a stopped watcher never
            // leaves that phase on its own, so the spinner turned forever, promising work that was
            // not happening. On is on; the spinner is for the seconds it actually takes.
            SpeakerCoverRuntimePhase.STOPPED -> FeatureSnapshot(
                id = FeatureId.SPEAKER_COVERS,
                desiredEnabled = true,
                status = FeatureStatus.READY,
                message = runtime.message.ifBlank { "Автоматика включена" },
                details = runtime.details,
            )
            SpeakerCoverRuntimePhase.STARTING -> FeatureSnapshot(
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
