package dev.denza.apps.platform.shell

/**
 * What `service call` printed, read as the 32-bit words of the reply parcel.
 *
 * A short reply is one line: `Result: Parcel(00000000 0000002b   '........')` - by convention the
 * status (`0` is no exception), then the payload, then the same bytes as text. A reply with no data
 * is `Result: Parcel(NULL)`. Which word means what is the caller's business, not this reader's.
 *
 * Only the short form is understood. A longer reply - an exception's message, a string - is printed
 * over several lines, each starting with its offset (`0x00000000: ...`). Read line by line it has
 * no words; read whole by [words], its first offset passes for a word, which is how the cloud link
 * and the split's `activity_task` calls have always read it. [oneLineWords] refuses that form
 * instead, for the HUD's read, whose own expression never matched it. Nothing this app reads
 * through here is that long when it succeeds.
 *
 * It replaces the expressions the vehicle batch, the HUD speed limit, the cloud link and the split
 * each had of their own; on every reply they each give the same reading (`ServiceCallParcelTest`).
 */
internal object ServiceCallParcel {

    /**
     * The words of the first `Parcel(` in [text], in order: empty for `Parcel(NULL)`, null when
     * [text] holds no parcel at all.
     */
    fun words(text: String): List<Int>? = body(text)?.let(::wordsOf)

    /** [words], and null as well when the parcel is the multi-line form. */
    fun oneLineWords(text: String): List<Int>? =
        body(text)?.takeUnless { '\n' in it || '\r' in it }?.let(::wordsOf)

    private fun body(text: String): String? = PARCEL.find(text)?.groupValues?.get(1)

    private fun wordsOf(body: String): List<Int> =
        WORD.findAll(body).map { it.value.toLong(16).toInt() }.toList()

    private val PARCEL = Regex("""Parcel\(([^')]*)""")
    private val WORD = Regex("""[0-9a-fA-F]{8}""")
}
