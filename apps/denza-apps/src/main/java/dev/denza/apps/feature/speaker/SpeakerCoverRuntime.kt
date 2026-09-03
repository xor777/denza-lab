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

    /**
     * Whether a cover command is on the wire right now - asked without reference to the toggle.
     *
     * [featureSnapshot] cannot answer this. With the automation off it returns `disabled` before it
     * ever looks at the phase, which is right for the tile - the feature *is* off - and wrong for
     * the two buttons underneath it, which work either way and run the same seconds-long adb call
     * either way. So the panel read "not busy" through the whole of a manual command, the buttons
     * stayed live, and from the seat that is a control that did nothing: press, press, press.
     */
    fun commanding(): Boolean = snapshot().phase == SpeakerCoverRuntimePhase.COMMANDING

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
                message = runtime.message.ifBlank { "Динамики выедут под музыку" },
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
                status = FeatureStatus.READY,
                message = runtime.message.ifBlank { "Динамики выедут под музыку" },
                details = runtime.details,
            )
            SpeakerCoverRuntimePhase.COMMANDING -> FeatureSnapshot(
                id = FeatureId.SPEAKER_COVERS,
                desiredEnabled = true,
                status = FeatureStatus.STARTING,
                message = runtime.message.ifBlank { "Говорю машине, что играет музыка" },
                details = runtime.details,
            )
            SpeakerCoverRuntimePhase.DEGRADED -> FeatureSnapshot(
                id = FeatureId.SPEAKER_COVERS,
                desiredEnabled = true,
                status = FeatureStatus.RECOVERING,
                message = runtime.message.ifBlank { "Повторю при следующем запуске музыки" },
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
