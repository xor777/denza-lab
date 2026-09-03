package dev.denza.apps.feature.speaker

/**
 * The two vehicle properties this feature writes, and how their replies read.
 *
 * Both go through the shell, because the app's own UID is refused at the property layer:
 * `CarMediaServiceImpl.setPlaybackState` carries no permission check of its own, and the write
 * behind it still answers `20004, Permission not granted for:
 * android.permission.BYDAUTO_INSTRUMENT_SET`. Shell holds it. That is the same route the feature
 * has always used, so nothing new goes into the product manifest.
 */
internal object SpeakerCoverProtocol {
    /** `BYDAUTO_DEVICE_INSTRUMENT`. */
    private const val DEVICE_INSTRUMENT = 1007

    /** `BYDAUTO_DEVICE_AUDIO`. */
    private const val DEVICE_AUDIO = 1002

    /** `INSTRUMENT_MUSIC_STATE_SET`, `0x43E0000A`. */
    private const val MUSIC_STATE = 1_138_753_546

    /** `AUDIO_RLSA_STATE_SET`, `0x16300025`. */
    private const val AUTO_LIFT = 372_244_517

    /** `AUDIO_SPEAKER_FLIP_SETTING_STATUS`, `0x35A000DA`, readable. */
    private const val AUTO_LIFT_STATUS = 899_678_426

    const val PLAYING = 1
    const val AUTO_LIFT_ON = 1
    const val AUTO_LIFT_OFF = 2

    fun reportPlayingCommand(): String = set(DEVICE_INSTRUMENT, MUSIC_STATE, PLAYING)

    fun autoLiftCommand(value: Int): String {
        require(value == AUTO_LIFT_ON || value == AUTO_LIFT_OFF) { "unsupported auto-lift value" }
        return set(DEVICE_AUDIO, AUTO_LIFT, value)
    }

    fun readAutoLiftCommand(): String = "service call autoservice 5 i32 $DEVICE_AUDIO i32 $AUTO_LIFT_STATUS"

    fun command(step: SpeakerCoverStep): String = when (step) {
        SpeakerCoverStep.ENABLE_AUTO_LIFT -> autoLiftCommand(AUTO_LIFT_ON)
        SpeakerCoverStep.REPORT_PLAYING -> reportPlayingCommand()
        SpeakerCoverStep.HIDE -> autoLiftCommand(AUTO_LIFT_OFF)
    }

    /**
     * Whether a write was carried out.
     *
     * A write answers with one word and it is the status: `Parcel(00000001    '....')`. This used
     * to require exactly two words and read the second, which is the shape of a *read* reply, so
     * every successful command was scored as a refusal and the panel sat turning a spinner over
     * covers that had done as they were told.
     */
    fun accepted(output: String): Boolean = words(output).singleOrNull() == 1

    /**
     * The value behind a read, or null if the car did not answer with one.
     *
     * A read answers with two words, an exception code and then the value:
     * `Parcel(00000000 00000001   '........')`. A non-zero code is the car saying the property is
     * not supported here - `ffffd8e5`, -10011 - and that is not a value to reason from.
     */
    fun readValue(output: String): Int? {
        val words = words(output)
        if (words.size != 2 || words[0] != 0) return null
        return words[1]
    }

    fun autoLift(output: String): SpeakerCoverAutoLift = when (readValue(output)) {
        AUTO_LIFT_ON -> SpeakerCoverAutoLift.ENABLED
        AUTO_LIFT_OFF -> SpeakerCoverAutoLift.DISABLED
        else -> SpeakerCoverAutoLift.UNKNOWN
    }

    private fun set(device: Int, feature: Int, value: Int): String =
        "service call autoservice 6 i32 $device i32 $feature i32 $value null"

    private fun words(output: String): List<Int> {
        val body = PARCEL.find(output)?.groupValues?.get(1) ?: return emptyList()
        return WORD.findAll(body).map { it.value.toLong(16).toInt() }.toList()
    }

    private val PARCEL = Regex("""Parcel\(([^')]*)""")
    private val WORD = Regex("""[0-9a-fA-F]{8}""")
}
