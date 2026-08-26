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
    FAILED,
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
            // Recovery that has stopped being plausible.
            //
            // DEGRADED draws a spinner, and the automaton retries on a 30-second cooldown for as
            // long as the process lives - so a command that genuinely cannot succeed turned that
            // spinner for the rest of the day, promising work that was never going to finish. It
            // is the same shape of lie the startup spinner used to tell. After a few tries the
            // feature says it is broken instead, and the retries carry on quietly underneath: if
            // one lands, the phase moves back on its own.
            SpeakerCoverRuntimePhase.FAILED -> FeatureSnapshot(
                id = FeatureId.SPEAKER_COVERS,
                desiredEnabled = true,
                status = FeatureStatus.ERROR,
                message = runtime.message.ifBlank { "Крышки не отвечают" },
                details = runtime.details,
            )
        }
    }
}
