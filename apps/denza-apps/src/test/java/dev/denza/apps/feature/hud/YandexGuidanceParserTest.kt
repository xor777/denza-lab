package dev.denza.apps.feature.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(HudManeuver.ROUNDABOUT_LEFT, YandexGuidanceParser.parseManeuver("Enter the roundabout"))
        assertEquals(1_609, YandexGuidanceParser.parseDistance("1", "mi"))
        assertEquals(30, YandexGuidanceParser.parseDistance("100", "ft"))
    }

    @Test
    fun keepsUturnAndRoundaboutDirectionsConsistent() {
        assertEquals(HudManeuver.U_TURN_LEFT, YandexGuidanceParser.parseManeuver("Развернитесь"))
        assertEquals(HudManeuver.U_TURN_RIGHT, YandexGuidanceParser.parseManeuver("Разворот направо"))
        assertEquals(HudManeuver.U_TURN_LEFT, YandexGuidanceParser.parseManeuver("Make a U‑turn"))
        assertEquals(HudManeuver.ROUNDABOUT_LEFT, YandexGuidanceParser.parseManeuver("Въезжайте на круговое движение"))
        assertEquals(HudManeuver.ROUNDABOUT_LEFT, YandexGuidanceParser.parseManeuver("На кольце второй съезд"))
        assertEquals(HudManeuver.ROUNDABOUT_RIGHT, YandexGuidanceParser.parseManeuver("At the roundabout, exit right"))
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
    fun schematicRoundaboutShowsPassedExitsWithoutAssumingTheirAngles() {
        assertEquals(0, HudSomeIpClient.schematicPassedExitCount(null))
        assertEquals(0, HudSomeIpClient.schematicPassedExitCount(1))
        assertEquals(1, HudSomeIpClient.schematicPassedExitCount(2))
        assertEquals(2, HudSomeIpClient.schematicPassedExitCount(3))
        assertEquals(6, HudSomeIpClient.schematicPassedExitCount(7))
        assertEquals(11, HudSomeIpClient.schematicPassedExitCount(20))
    }

    @Test
    fun mirrorsOnlyIconsWhoseBaseArtworkFacesTheOtherWay() {
        val mirrored = setOf(
            HudManeuver.LEFT,
            HudManeuver.SLIGHT_LEFT,
            HudManeuver.SHARP_LEFT,
            HudManeuver.U_TURN_RIGHT,
            HudManeuver.ROUNDABOUT_LEFT,
        )

        HudManeuver.entries.forEach { maneuver ->
            if (maneuver in mirrored) {
                assertTrue(maneuver.name, HudSomeIpClient.shouldMirrorIcon(maneuver))
            } else {
                assertFalse(maneuver.name, HudSomeIpClient.shouldMirrorIcon(maneuver))
            }
        }
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
}
