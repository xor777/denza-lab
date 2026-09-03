package dev.denza.apps.feature.hud

import org.junit.Assert.assertEquals
import org.junit.Test

class HudManeuverStockIdTest {
    @Test
    fun field28FollowsTheStockHudIconTable() {
        val expected = mapOf(
            HudManeuver.UNKNOWN to 0,
            HudManeuver.LEFT to 1,
            HudManeuver.RIGHT to 2,
            HudManeuver.SLIGHT_LEFT to 3,
            HudManeuver.SLIGHT_RIGHT to 5,
            HudManeuver.SHARP_LEFT to 7,
            HudManeuver.SHARP_RIGHT to 8,
            HudManeuver.U_TURN_LEFT to 9,
            HudManeuver.U_TURN_RIGHT to 10,
            HudManeuver.STRAIGHT to 11,
            HudManeuver.ROUNDABOUT_LEFT to 25,
            HudManeuver.ROUNDABOUT_RIGHT to 25,
        )
        assertEquals(expected.keys, HudManeuver.values().toSet())
        expected.forEach { (maneuver, id) ->
            assertEquals(maneuver.name, id, maneuver.stockId)
        }
    }
}
