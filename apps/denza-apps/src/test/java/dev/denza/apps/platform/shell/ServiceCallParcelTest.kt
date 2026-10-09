package dev.denza.apps.platform.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServiceCallParcelTest {

    /** Replies copied from the feature tests and parsers that read them, word for word. */
    @Test
    fun readsTheWordsOfEveryReplyTheFeaturesRecorded() {
        mapOf(
            // autoservice getInt / getFloat (AutoserviceShellTest)
            "Result: Parcel(00000000 0000002b   '........')" to listOf(0, 0x2b),
            "Result: Parcel(00000000 422c0000   '..,.....')" to listOf(0, 0x422c0000),
            "Result: Parcel(00000000 00000226   '....&...')" to listOf(0, 0x226),
            // ADAS speed limit and its refusals (HudNativeSpeedLimitTest)
            "Result: Parcel(00000000 0000000d   '........')" to listOf(0, 13),
            "Result: Parcel(00000000 ffffd8e3   '........')" to listOf(0, -10_013),
            "Result: Parcel(00000000 ffffd8e5   '........')" to listOf(0, -10_011),
            // a write's single status word (HudNativeSpeedLimitTest, SpeakerCoverProtocolTest)
            "Result: Parcel(00000001    '....')" to listOf(1),
            "Result: Parcel(ffffd8e5    '....')" to listOf(-10_011),
            "Result: Parcel(00000000    '....')" to listOf(0),
            // cloudmanager's TCP getter (CloudLinkProtocolTest)
            "Result: Parcel(00000000 00000001   '........')" to listOf(0, 1),
            "Result: Parcel(ffffffb5 00000001   '........')" to listOf(-75, 1),
            "Result: Parcel(NULL)" to emptyList(),
            // activity_task as service call and the in-process path print it (SplitInProcessCalls)
            "Result: Parcel(00000000 00000003 '........')" to listOf(0, 3),
            "Result: Parcel(00000000 ffffffff '........')" to listOf(0, -1),
            "Result: Parcel(00000000 '....')" to listOf(0),
            // the parked gearbox, as the trip once read it, and a read-shaped write answer
            "Result: Parcel(00000000 00000003   '........')" to listOf(0, 3),
            "Result: Parcel(00000001 00000002   '........')" to listOf(1, 2),
        ).forEach { (reply, words) ->
            assertEquals(reply, words, ServiceCallParcel.words(reply))
        }
    }

    @Test
    fun noParcelIsNoAnswer() {
        assertNull(ServiceCallParcel.words("service: Service cloudmanager does not exist"))
        assertNull(ServiceCallParcel.words(""))
        assertNull(ServiceCallParcel.words("@@0"))
    }

    /** The one-line reader refuses the multi-line form whatever its words; the other reads it whole. */
    @Test
    fun onlyTheOneLineFormIsReadByOneLineWords() {
        val multiLine = "Result: Parcel(\n" +
            "  0x00000000: 00000000 0000000d 00000000 00000000 '................'\n" +
            "  0x00000010: 00000000                            '....')\n"

        assertNull(ServiceCallParcel.oneLineWords(multiLine))
        assertEquals(listOf(0, 0, 13, 0, 0), ServiceCallParcel.words(multiLine))
        assertEquals(listOf(0, 13), ServiceCallParcel.oneLineWords("Result: Parcel(00000000 0000000d   '........')"))
        assertEquals(emptyList<Int>(), ServiceCallParcel.oneLineWords("Result: Parcel(NULL)"))
        assertNull(ServiceCallParcel.oneLineWords("service: Service autoservice does not exist"))
    }

    @Test
    fun theFirstParcelInTheTextIsTheOneRead() {
        assertEquals(
            listOf(0, 0x2b),
            ServiceCallParcel.words(
                "@@0\nResult: Parcel(00000000 0000002B   '+.......')\n" +
                    "@@1\nResult: Parcel(00000000 00000001   '........')\n",
            ),
        )
    }

    /**
     * The four expressions this reader replaced, run on the same replies the way each feature runs
     * them: the autoservice batch line by line, the HUD's read on the whole answer, the cloud link
     * and the split on the whole answer. Each feature's reading comes out the same. The two
     * multi-line replies are in the list on purpose: read whole, the first offset passes for a word
     * through the cloud link's and the split's expressions as much as through [ServiceCallParcel.words],
     * while the HUD's expression never matched the form - which is why it reads through
     * [ServiceCallParcel.oneLineWords]. The second one starts with a non-negative word, so a reader
     * that took its offset for a status would show here as a value.
     */
    @Test
    fun theReplacedExpressionsReadTheSameWords() {
        val replies = listOf(
            "Result: Parcel(00000000 0000002b   '........')",
            "Result: Parcel(00000000 ffffd8e3   '........')",
            "Result: Parcel(00000001    '....')",
            "Result: Parcel(ffffd8e5    '....')",
            "Result: Parcel(00000000 '....')",
            "Result: Parcel(NULL)",
            "Result: Parcel(ffffffb5 00000001   '........')",
            "service: Service autoservice does not exist",
            "",
            "Result: Parcel(\n" +
                "  0x00000000: ffffffff 0000004a 00740041 00650074 '....J...A.t.t.e.'\n" +
                "  0x00000010: 0070006d 00200074 006f0074 00720020 'm.p.t. .t.o. .r.')",
            "Result: Parcel(\n" +
                "  0x00000000: 00000000 0000000d 00000000 00000000 '................'\n" +
                "  0x00000010: 00000000                            '....')",
        )
        replies.forEach { reply ->
            val words = ServiceCallParcel.words(reply)
            // AutoserviceShell: line by line, a status and a value; the value is the reading.
            reply.lines().forEach { line ->
                assertEquals(
                    line,
                    autoserviceValue(line),
                    ServiceCallParcel.words(line)?.takeIf { it.size >= 2 }?.get(1),
                )
            }
            // HudNativeSpeedLimitProtocol.parseRead: the first one or two words decide.
            assertEquals(
                reply,
                hudRead(hudWords(reply)),
                hudRead(ServiceCallParcel.oneLineWords(reply)?.take(2)),
            )
            // CloudLinkProtocol and SplitPickerShellSession: every word of the body.
            assertEquals(reply, bodyWords(reply, Regex("""Parcel\(([^')]*)""")), words)
            assertEquals(reply, bodyWords(reply, Regex("Parcel\\(([^']+)")), words)
        }
    }

    private fun autoserviceValue(line: String): Int? =
        Regex("""Parcel\(([0-9a-fA-F]{8})\s+([0-9a-fA-F]{8})""").find(line)
            ?.groupValues?.get(2)?.toLong(16)?.toInt()

    private fun hudWords(reply: String): List<Int>? =
        Regex("""Parcel\(([0-9a-fA-F]{8})(?:\s+([0-9a-fA-F]{8}))?""").find(reply)
            ?.groupValues?.drop(1)?.filter { it.isNotEmpty() }?.map { it.toLong(16).toInt() }

    /** The decision `parseRead` makes from its words, as text. */
    private fun hudRead(words: List<Int>?): String = when {
        words == null -> "failed"
        words.any { it == -10_013 } -> "wrong transact"
        words.size < 2 || words[1] < 0 -> "failed"
        else -> "value ${words[1]}"
    }

    private fun bodyWords(reply: String, parcel: Regex): List<Int>? =
        parcel.find(reply)?.groupValues?.get(1)?.let { body ->
            Regex("[0-9a-fA-F]{8}").findAll(body).map { it.value.toLong(16).toInt() }.toList()
        }
}
