package dev.denza.apps.feature.speaker

/** One thing said to the car. The list of them is the whole feature. */
enum class SpeakerCoverStep {
    /** Put the driver's stock auto-lift back on, which is the amplifier's precondition. */
    ENABLE_AUTO_LIFT,

    /** Tell the cluster that music is playing, which is what raises the covers. */
    REPORT_PLAYING,

    /**
     * Switch the stock auto-lift off, which retracts the covers and is the only way to.
     *
     * Reached from one place only: the driver turning the feature off. It is not a close command -
     * the car has none - and it costs the driver their stock auto-lift until the next start.
     */
    HIDE,
}

/** What the amplifier's own setting reads, or that it has not been read yet. */
enum class SpeakerCoverAutoLift {
    ENABLED,
    DISABLED,
    UNKNOWN,
}

/** Everything that can make the app speak. */
sealed interface SpeakerCoverTrigger {
    /** A media session belonging to [packageName] started playing. */
    data class Playback(val packageName: String?) : SpeakerCoverTrigger

    /** [packageName] came to the foreground and is a player, before any sound. */
    data class PlayerOpened(val packageName: String?) : SpeakerCoverTrigger

    /** The panel's one button. */
    data object RaisePressed : SpeakerCoverTrigger

    data object FeatureEnabled : SpeakerCoverTrigger

    data object FeatureDisabled : SpeakerCoverTrigger
}

/**
 * When the app speaks to the car, and what it says.
 *
 * The covers are not driven from here and cannot be. The amplifier owns the motor: it raises while
 * it believes music is playing, and it lowers on its own at power off and after a long idle. There
 * is no fast close in the vehicle at all, which is why this has a raise and no lower - the earlier
 * design's open/close symmetry was a shape the car never had.
 *
 * So every decision is the same one: is the car about to be told something true that it would
 * otherwise not hear. It hears playback for the players it knows (see [SpeakerCoverReporting]); it
 * does not for the rest, and that gap is this feature.
 */
object SpeakerCoverPolicy {

    fun steps(
        trigger: SpeakerCoverTrigger,
        featureEnabled: Boolean,
        autoLift: SpeakerCoverAutoLift,
    ): List<SpeakerCoverStep> = when (trigger) {
        is SpeakerCoverTrigger.Playback ->
            if (featureEnabled && !SpeakerCoverReporting.carSpeaksFor(trigger.packageName)) {
                raise(autoLift)
            } else {
                emptyList()
            }

        is SpeakerCoverTrigger.PlayerOpened ->
            if (
                featureEnabled &&
                SpeakerCoverApps.opensEagerly(trigger.packageName) &&
                !SpeakerCoverReporting.carSpeaksFor(trigger.packageName)
            ) {
                raise(autoLift)
            } else {
                emptyList()
            }

        // The button answers whether or not the automation is switched on: the covers belong to the
        // car, not to the feature, and a driver reaching for them has not asked about a setting.
        SpeakerCoverTrigger.RaisePressed -> raise(autoLift)

        // Switching on raises them, and does so identically on both cars. Without the report the
        // Z9GT would rise from the enable write alone while the N9 sat still until music started,
        // and the same switch would mean two different things depending on which car it was in.
        SpeakerCoverTrigger.FeatureEnabled -> raise(autoLift)

        SpeakerCoverTrigger.FeatureDisabled -> listOf(SpeakerCoverStep.HIDE)
    }

    /**
     * The report, preceded by the enable only when the setting is not already on.
     *
     * Reading first matters on the Z9GT, where writing the enable also drives the motor: folded in
     * here it can only happen at a moment a raise was wanted anyway. An unknown reading is treated
     * as off, because a missing enable is a feature that silently does nothing, and a redundant one
     * is a write the property ignores.
     */
    private fun raise(autoLift: SpeakerCoverAutoLift): List<SpeakerCoverStep> =
        if (autoLift == SpeakerCoverAutoLift.ENABLED) {
            listOf(SpeakerCoverStep.REPORT_PLAYING)
        } else {
            listOf(SpeakerCoverStep.ENABLE_AUTO_LIFT, SpeakerCoverStep.REPORT_PLAYING)
        }
}
