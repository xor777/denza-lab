package dev.denza.apps.feature.speaker

/**
 * The one vehicle property this feature writes, and how its reply reads.
 *
 * It goes through the shell, because the app's own UID is refused at the property layer:
 * `CarMediaServiceImpl.setPlaybackState` carries no permission check of its own, and the write
 * behind it still answers `20004, Permission not granted for:
 * android.permission.BYDAUTO_INSTRUMENT_SET`. Shell holds it, so nothing new goes into the product
 * manifest.
 */
internal object SpeakerCoverProtocol {
    /** `BYDAUTO_DEVICE_INSTRUMENT`. */
    private const val DEVICE_INSTRUMENT = 1007

    /** `INSTRUMENT_MUSIC_STATE_SET`, `0x43E0000A`. */
    private const val MUSIC_STATE = 1_138_753_546

    /** The value that raised the covers on the Z9GT (2026-09-03) and on the N9 (2026-09-04). */
    const val PLAYING = 1

    fun reportPlayingCommand(): String =
        "service call autoservice 6 i32 $DEVICE_INSTRUMENT i32 $MUSIC_STATE i32 $PLAYING null"

    /**
     * Whether the write was carried out.
     *
     * A write answers with one word and it is the status: `Parcel(00000001    '....')`. A two-word
     * reply is the shape of a *read* and is not an acknowledgement of anything.
     */
    fun accepted(output: String): Boolean {
        val body = PARCEL.find(output)?.groupValues?.get(1) ?: return false
        val words = WORD.findAll(body).map { it.value.toLong(16).toInt() }.toList()
        return words.singleOrNull() == 1
    }

    private val PARCEL = Regex("""Parcel\(([^')]*)""")
    private val WORD = Regex("""[0-9a-fA-F]{8}""")
}
