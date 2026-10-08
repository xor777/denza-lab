package dev.denza.apps.feature.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YandexGuidanceParserTest {
    @Test
    fun parsesRussianGuidanceAndRouteSummary() {
        val guidance = YandexGuidanceParser.parse(
            instruction = "Поверните направо",
            nextRoadName = "Профсоюзная улица",
            maneuverDistance = "30",
            maneuverUnit = " м",
            remainingDistance = "56 км",
            remainingTime = "1 ч 12 мин",
            eta = "19:23",
        )

        requireNotNull(guidance)
        assertEquals(HudManeuver.RIGHT, guidance.maneuver)
        assertNull(guidance.roundaboutExitNumber)
        assertEquals("Профсоюзная улица", guidance.nextRoadName)
        assertEquals(30, guidance.maneuverDistanceMeters)
        assertEquals(56_000, guidance.remainingDistanceMeters)
        assertEquals(4_320, guidance.remainingTimeSeconds)
        assertEquals("19:23", guidance.eta)
    }

    @Test
    fun parsesEnglishFamiliesAndImperialDistance() {
        assertEquals(HudManeuver.SLIGHT_LEFT, YandexGuidanceParser.parseManeuver("Keep left at the fork"))
        assertEquals(HudManeuver.SHARP_RIGHT, YandexGuidanceParser.parseManeuver("Make a sharp right turn"))
        assertEquals(HudManeuver.U_TURN_LEFT, YandexGuidanceParser.parseManeuver("Make a U-turn"))
        assertEquals(HudManeuver.ROUNDABOUT, YandexGuidanceParser.parseManeuver("Enter the roundabout"))
        assertEquals(1_609, YandexGuidanceParser.parseDistance("1", "mi"))
        assertEquals(30, YandexGuidanceParser.parseDistance("100", "ft"))
    }

    @Test
    fun keepsUturnAndRoundaboutDirectionsConsistent() {
        assertEquals(HudManeuver.U_TURN_LEFT, YandexGuidanceParser.parseManeuver("Развернитесь"))
        assertEquals(HudManeuver.U_TURN_RIGHT, YandexGuidanceParser.parseManeuver("Разворот направо"))
        assertEquals(HudManeuver.U_TURN_LEFT, YandexGuidanceParser.parseManeuver("Make a U‑turn"))
        assertEquals(HudManeuver.ROUNDABOUT, YandexGuidanceParser.parseManeuver("Въезжайте на круговое движение"))
        assertEquals(HudManeuver.ROUNDABOUT, YandexGuidanceParser.parseManeuver("На кольце второй съезд"))
        assertEquals(HudManeuver.ROUNDABOUT, YandexGuidanceParser.parseManeuver("At the roundabout, exit right"))
        assertEquals(HudManeuver.SLIGHT_LEFT, YandexGuidanceParser.parseManeuver("Держитесь слева"))
        assertEquals(HudManeuver.SLIGHT_LEFT, YandexGuidanceParser.parseManeuver("Плавный левый поворот"))
        assertEquals(HudManeuver.SHARP_RIGHT, YandexGuidanceParser.parseManeuver("Резкий правый поворот"))
        assertEquals(HudManeuver.RIGHT, YandexGuidanceParser.parseManeuver("Поверните направо на Круглую улицу"))
        assertEquals(HudManeuver.STRAIGHT, YandexGuidanceParser.parseManeuver("Направляйтесь прямо"))
        assertEquals(HudManeuver.UNKNOWN, YandexGuidanceParser.parseManeuver("Take the exit"))
    }

    @Test
    fun extractsRoundaboutExitNumberFromDedicatedViewAndInstructions() {
        assertEquals(7, YandexGuidanceParser.parseRoundaboutExitNumber("7", "Enter the roundabout"))
        assertEquals(2, YandexGuidanceParser.parseRoundaboutExitNumber("", "На кольце второй съезд"))
        assertEquals(3, YandexGuidanceParser.parseRoundaboutExitNumber("", "На круговом движении 3-й съезд"))
        assertEquals(5, YandexGuidanceParser.parseRoundaboutExitNumber("", "Take the 5th exit at the roundabout"))
        assertEquals(8, YandexGuidanceParser.parseRoundaboutExitNumber("", "At the roundabout, take the eighth exit"))
        assertNull(YandexGuidanceParser.parseRoundaboutExitNumber("", "Через 300 м въезжайте на кольцо"))
    }

    @Test
    fun carriesExitNumberOnlyForRoundaboutManeuvers() {
        val roundabout = YandexGuidanceParser.parse(
            instruction = "На кольце третий съезд",
            nextRoadName = "",
            maneuverDistance = "120",
            maneuverUnit = "м",
            remainingDistance = "",
            remainingTime = "",
            eta = "",
            roundaboutExitNumber = "",
        )
        val regularTurn = YandexGuidanceParser.parse(
            instruction = "Поверните направо",
            nextRoadName = "",
            maneuverDistance = "120",
            maneuverUnit = "м",
            remainingDistance = "",
            remainingTime = "",
            eta = "",
            roundaboutExitNumber = "4",
        )

        assertEquals(3, requireNotNull(roundabout).roundaboutExitNumber)
        assertNull(requireNotNull(regularTurn).roundaboutExitNumber)
    }

    @Test
    fun schematicRoundaboutShowsPassedExitsAndMovesTheTargetArrow() {
        assertEquals(0, HudSomeIpClient.schematicPassedExitCount(null))
        assertEquals(0, HudSomeIpClient.schematicPassedExitCount(1))
        assertEquals(1, HudSomeIpClient.schematicPassedExitCount(2))
        assertEquals(2, HudSomeIpClient.schematicPassedExitCount(3))
        assertEquals(6, HudSomeIpClient.schematicPassedExitCount(7))
        assertEquals(11, HudSomeIpClient.schematicPassedExitCount(20))
        assertEquals(0f, HudSomeIpClient.schematicRoundaboutExitAngle(1, 1))
        assertEquals(-90f, HudSomeIpClient.schematicRoundaboutExitAngle(2, 2))
        assertEquals(-180f, HudSomeIpClient.schematicRoundaboutExitAngle(3, 3))
        assertEquals(-225f, HudSomeIpClient.schematicRoundaboutExitAngle(4, 4))
        assertEquals(-245f, HudSomeIpClient.schematicRoundaboutExitAngle(7, 7))
    }

    @Test
    fun theRoundaboutTargetExitIsDrawnWhereARightHandTrafficDriverLeavesTheCircle() {
        // Exits count counter-clockwise from the entry at the bottom: the first leaves to the
        // right, the second straight ahead, the third to the left.
        val first = roundaboutTip("На кольце первый съезд")
        val second = roundaboutTip("На кольце второй съезд")
        val third = roundaboutTip("На круговом движении 3-й съезд")
        val thirdFromTheExitView = roundaboutTip("Въезжайте на круговое движение", exitView = "3")

        assertTrue("exit 1 at x=${first[0]}", first[0] > ICON_CENTRE_X + 40f)
        assertEquals("exit 2 at x=${second[0]}", ICON_CENTRE_X, second[0], 1f)
        assertTrue("exit 2 at y=${second[1]}", second[1] < 45f)
        assertTrue("exit 3 at x=${third[0]}", third[0] < ICON_CENTRE_X - 40f)
        assertTrue("exit 3 at x=${thirdFromTheExitView[0]}", thirdFromTheExitView[0] < ICON_CENTRE_X - 40f)
    }

    @Test
    fun aRoundaboutFromTheNotificationIsDrawnTheSameWayRound() {
        val patch = requireNotNull(
            YandexNotificationGuidanceParser.parse(
                YandexNotificationGuidanceFields(
                    maneuverResourceName = "notification_roundabout_sdl",
                    maneuverDescription = "На кольце 3-й съезд",
                    title = "300 м",
                ),
            ),
        )

        val tip = requireNotNull(HudSomeIpClient.arrowTip(patch.maneuver, patch.roundaboutExitNumber))

        assertEquals(3, patch.roundaboutExitNumber)
        assertTrue("exit 3 at x=${tip[0]}", tip[0] < ICON_CENTRE_X - 40f)
    }

    @Test
    fun everyTurnArrowPointsToTheSideItsInstructionNames() {
        val leftward = listOf(
            "Поверните налево",
            "Держитесь левее",
            "Резкий левый поворот",
            "Развернитесь",
        )
        val rightward = listOf(
            "Поверните направо",
            "Держитесь правее",
            "Резкий правый поворот",
            "Разворот направо",
        )

        leftward.forEach { instruction ->
            val tip = tip(instruction)
            assertTrue("$instruction at x=${tip[0]}", tip[0] < ICON_CENTRE_X - 20f)
        }
        rightward.forEach { instruction ->
            val tip = tip(instruction)
            assertTrue("$instruction at x=${tip[0]}", tip[0] > ICON_CENTRE_X + 20f)
        }
        val straight = tip("Продолжайте прямо")
        assertEquals(ICON_CENTRE_X, straight[0], 1f)
        assertTrue(straight[1] < 45f)
        assertNull(HudSomeIpClient.arrowTip(HudManeuver.UNKNOWN, null))
    }

    @Test
    fun rejectsIncompleteManeuverInsteadOfGuessing() {
        assertNull(
            YandexGuidanceParser.parse(
                instruction = "",
                nextRoadName = "",
                maneuverDistance = "30",
                maneuverUnit = "м",
                remainingDistance = "",
                remainingTime = "",
                eta = "",
            ),
        )
        assertNull(
            YandexGuidanceParser.parse(
                instruction = "Поверните направо",
                nextRoadName = "",
                maneuverDistance = "",
                maneuverUnit = "",
                remainingDistance = "",
                remainingTime = "",
                eta = "",
            ),
        )
    }

    @Test
    fun formatsRouteDistanceForHudSummary() {
        val routeOnly = HudGuidance(
            maneuver = HudManeuver.RIGHT,
            roundaboutExitNumber = null,
            instruction = "Поверните направо",
            nextRoadName = "",
            maneuverDistanceMeters = 30,
            remainingDistanceMeters = 51_000,
            remainingTimeSeconds = 2_880,
            remainingTimeText = "48 мин",
            eta = "19:34",
        )
        assertEquals("51 км", HudSomeIpClient.routeDistance(routeOnly))
        assertEquals(
            "5,6 км",
            HudSomeIpClient.routeDistance(routeOnly.copy(
                nextRoadName = "Профсоюзная улица",
                remainingDistanceMeters = 5_600,
            )),
        )
    }

    private fun tip(instruction: String): FloatArray {
        val maneuver = YandexGuidanceParser.parseManeuver(instruction)
        return requireNotNull(HudSomeIpClient.arrowTip(maneuver, null)) { instruction }
    }

    private fun roundaboutTip(instruction: String, exitView: String = ""): FloatArray {
        val guidance = requireNotNull(
            YandexGuidanceParser.parse(
                instruction = instruction,
                nextRoadName = "",
                maneuverDistance = "300",
                maneuverUnit = "м",
                remainingDistance = "",
                remainingTime = "",
                eta = "",
                roundaboutExitNumber = exitView,
            ),
        )
        return requireNotNull(
            HudSomeIpClient.arrowTip(guidance.maneuver, guidance.roundaboutExitNumber),
        ) { instruction }
    }

    private companion object {
        /** The icon is 192 px square. */
        const val ICON_CENTRE_X = 96f
    }
}
