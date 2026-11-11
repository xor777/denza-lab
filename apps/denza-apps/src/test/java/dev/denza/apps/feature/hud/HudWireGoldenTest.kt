package dev.denza.apps.feature.hud

import dev.denza.apps.core.AppLanguage
import dev.denza.apps.core.inEnglish
import dev.denza.hudlink.HudProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exact bytes this app sends the HUD, frozen. Field order and every byte are compared, not
 * only the decoded fields, so moving the encoder into `:hud-link` cannot change what reaches the
 * HUD. The values were taken from the encoder in `HudSomeIpClient` before that move, and still
 * hold with the packet made by the `:hud-link` compositor from what [HudCaptureSource] publishes.
 */
class HudWireGoldenTest {

    private val guidance = HudGuidance(
        maneuver = HudManeuver.SLIGHT_RIGHT,
        roundaboutExitNumber = null,
        instruction = "Держитесь правее",
        nextRoadName = "Тверская",
        maneuverDistanceMeters = 250,
        remainingDistanceMeters = 12_345,
        remainingTimeSeconds = 1_234,
        remainingTimeText = "21 мин",
        eta = "12:00",
        speedLimitKmh = 60,
    )
    private val ar = HudArGeometry(
        vehicleLatitude = 55.7558,
        vehicleLongitude = 37.6173,
        vehicleAltitudeMeters = 144.6,
        vehicleSpeedMetersPerSecond = 13.4,
        vehicleHeadingDegrees = 87.5,
        guideLine = "[[37.6173,55.7558,0],[37.6181,55.7559,0]]",
        guidePoint = "37.6181,55.7559,0",
        navigatingRatio = 0.25,
    )

    private fun road(distance: String, time: String) =
        hex(HudCaptureSource.roadPacketForTest(guidance, ar, distance, time))

    @Test
    fun theFullRoadPacketWithArIsByteForByteTheSame() {
        assertEquals(FULL_ROAD, hex(HudCaptureSource.roadPacketForTest(guidance, ar)))
    }

    /** What the module sends, through the signatures it compiles against. */
    @Test
    fun theOldSignaturesGiveTheFrozenBytes() {
        assertEquals(FULL_ROAD, road(HudProtocol.routeDistance(12_345), HudProtocol.duration(1_234)))
    }

    @Test
    fun theOverloadWithRussianWordsGivesTheFrozenBytes() {
        val words = HudProtocol.SummaryWords.RUSSIAN
        assertEquals(FULL_ROAD, road(HudProtocol.routeDistance(12_345, words), HudProtocol.duration(1_234, words)))
    }

    @Test
    fun theAppsRussianStringsGiveTheFrozenBytes() {
        val words = HudSummaryWords.forGlass()
        assertEquals(FULL_ROAD, road(HudProtocol.routeDistance(12_345, words), HudProtocol.duration(1_234, words)))
        for (meters in listOf(0, 350, 999, 1_000, 1_200, 9_949, 9_950, 10_000, 12_345, 500_500)) {
            assertEquals(HudProtocol.routeDistance(meters), HudProtocol.routeDistance(meters, words))
        }
    }

    /** Russian never reprints Yandex's text, even one this app would write differently. */
    @Test
    fun russianPassesYandexsOwnTimeOn() {
        val own = guidance.copy(remainingTimeText = "1 ч 05 мин", remainingTimeSeconds = 3_900)
        assertEquals("1 ч 05 мин", HudCaptureSource.summaryTime(own))
    }

    @Test
    fun englishGoesToTheWireAsTheConstantSays() = inEnglish {
        val expected = if (HudSummaryWords.SEND_ENGLISH_TO_GLASS) ENGLISH_ROAD else FULL_ROAD
        assertEquals(expected, hex(HudCaptureSource.roadPacketForTest(guidance, ar)))
    }

    @Test
    fun englishWordsMakeTheEnglishPacket() = inEnglish {
        val words = HudSummaryWords.forGlass(AppLanguage.ENGLISH, true)
        assertEquals(ENGLISH_ROAD, road(HudProtocol.routeDistance(12_345, words), HudProtocol.duration(1_234, words)))
        assertEquals("1.2 km", HudProtocol.routeDistance(1_200, words))
        assertEquals("350 m", HudProtocol.routeDistance(350, words))
        assertEquals("1 h 5 min", HudProtocol.duration(3_900, words))
        assertTrue(HudSummaryWords.printsTime(AppLanguage.ENGLISH, true))
    }

    /** The constant set to false: English mode puts the frozen Russian bytes on the glass. */
    @Test
    fun theConstantOffKeepsRussianOnTheGlassInEnglish() = inEnglish {
        val words = HudSummaryWords.forGlass(AppLanguage.ENGLISH, false)
        assertFalse(HudSummaryWords.printsTime(AppLanguage.ENGLISH, false))
        assertEquals(FULL_ROAD, road(HudProtocol.routeDistance(12_345, words), guidance.remainingTimeText))
    }

    /** A time Yandex's text did not yield has nothing to reprint, so its text stays. */
    @Test
    fun englishKeepsATimeItCouldNotRead() = inEnglish {
        val unread = guidance.copy(remainingTimeSeconds = null, remainingTimeText = "soon")
        assertEquals("soon", HudCaptureSource.summaryTime(unread))
    }

    private fun time(text: String, seconds: Int?) =
        HudCaptureSource.summaryTime(guidance.copy(remainingTimeText = text, remainingTimeSeconds = seconds))

    /** The seconds the guidance parser gives each text, or a merge left behind, so none of them can decide. */
    private val notReprinted = listOf(
        "1 д 5 ч" to 18_000,
        "2 дня 30 мин" to 1_800,
        "1 ч 5 мин." to 3_600,
        "менее минуты" to 120,
        "0 мин" to null,
        "1 час 5 минут" to 3_900,
        "" to 60,
    )

    @Test
    fun englishPassesOnATextThatIsNotExactlyHoursAndMinutes() = inEnglish {
        notReprinted.forEach { (text, seconds) -> assertEquals(text, time(text, seconds)) }
    }

    /** What is printed comes from the text, whatever seconds stand beside it. */
    @Test
    fun englishReprintsTheCleanShapesFromTheirOwnText() = inEnglish {
        val clean = listOf("1 ч 5 мин" to "1 h 5 min", "17 мин" to "17 min", "2 ч" to "2 h", "1 ч 05 мин" to "1 h 5 min")
        clean.forEach { (text, english) ->
            val expected = if (HudSummaryWords.SEND_ENGLISH_TO_GLASS) english else text
            assertEquals(expected, time(text, null))
            assertEquals(expected, time(text, 120))
        }
    }

    @Test
    fun russianPassesEveryTimeOnUntouched() {
        val clean = listOf("1 ч 5 мин" to 3_900, "17 мин" to 1_020, "2 ч" to 7_200)
        (notReprinted + clean).forEach { (text, seconds) -> assertEquals(text, time(text, seconds)) }
    }

    @Test
    fun theClearIsByteForByteTheSame() {
        val guidance = HudGuidance(
            maneuver = HudManeuver.LEFT,
            roundaboutExitNumber = null,
            instruction = "Поверните налево",
            nextRoadName = "",
            maneuverDistanceMeters = 100,
            remainingDistanceMeters = null,
            remainingTimeSeconds = null,
            remainingTimeText = "",
            eta = "",
        )

        assertEquals(CLEAR_ROAD, hex(HudCaptureSource.clearPacketForTest(guidance)))
    }

    @Test
    fun theMapFrameIsByteForByteTheSame() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 0, 1, 2)
        assertEquals(MAP_FRAME, hex(HudProtocol.mapPayload(png)))
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private companion object {
        const val FULL_ROAD =
            "0aaa01100280010218b96020d20948fa015210d0a2d0b2d0b5d180d181d0bad0b0d18f583cd20107" +
            "313220d0bad0bcda0109323120d0bcd0b8d0bde00105990110e9b7af03cf4240a1018d28ed0dbee0" +
            "4b40a8010db0019101f201295b5b33372e363137332c35352e373535382c305d2c5b33372e363138" +
            "312c35352e373535392c305d5dfa011133372e363138312c35352e373535392c3081020000000000" +
            "e055408902000000000000d03f"
        const val ENGLISH_ROAD =
            "0aa501100280010218b96020d20948fa015210d0a2d0b2d0b5d180d181d0bad0b0d18f583cd20105" +
            "3132206b6dda01063231206d696ee00105990110e9b7af03cf4240a1018d28ed0dbee0" +
            "4b40a8010db0019101f201295b5b33372e363137332c35352e373535382c305d2c5b33372e363138" +
            "312c35352e373535392c305d5dfa011133372e363138312c35352e373535392c3081020000000000" +
            "e055408902000000000000d03f"
        const val CLEAR_ROAD = "0a051002800101"
        const val MAP_FRAME = "0a08695641414151493d"
    }
}
