package dev.denza.apps.feature.speaker

/** Everything that can make the app speak. */
sealed interface SpeakerCoverTrigger {
    /** A media session belonging to [packageName] started playing. */
    data class Playback(val packageName: String?) : SpeakerCoverTrigger

    /** [packageName] came to the foreground and is a player, before any sound. */
    data class PlayerOpened(val packageName: String?) : SpeakerCoverTrigger

    /** The panel's one button. */
    data object RaisePressed : SpeakerCoverTrigger
}

/**
 * Whether the app tells the car that music is playing. That is the whole feature.
 *
 * The car owns the covers. It raises them while its stock auto-lift is on and the instrument bus
 * says music is playing, and it lowers them itself at power-off and after an idle. For the players
 * it knows it keeps that bus true by itself; for everyone else it asserts «paused», deliberately
 * (see [SpeakerCoverReporting]). The app fills exactly that gap and touches nothing else: not the
 * motor, and not the stock auto-lift setting, which is the car's own switch on both cars - drawn in
 * Settings on the N9, always on and undrawn on the Z9GT.
 *
 * So there is one sentence the app can say and one question here: is now a moment to say it.
 */
object SpeakerCoverPolicy {

    fun reports(trigger: SpeakerCoverTrigger, featureEnabled: Boolean): Boolean = when (trigger) {
        is SpeakerCoverTrigger.Playback ->
            featureEnabled && !SpeakerCoverReporting.carSpeaksFor(trigger.packageName)

        is SpeakerCoverTrigger.PlayerOpened ->
            featureEnabled &&
                SpeakerCoverApps.opensEagerly(trigger.packageName) &&
                !SpeakerCoverReporting.carSpeaksFor(trigger.packageName)

        // The button answers whether or not the automation is switched on: the covers belong to the
        // car, not to the feature, and a driver reaching for them has not asked about a setting.
        SpeakerCoverTrigger.RaisePressed -> true
    }
}
