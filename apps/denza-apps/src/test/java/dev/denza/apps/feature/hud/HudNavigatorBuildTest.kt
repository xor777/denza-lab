package dev.denza.apps.feature.hud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Яндекс HUD» is told from the ordinary Navigator by its version name alone. The HUD build's name
 * is spelled out as its build tool writes it: builds already handed out carry exactly this, and a
 * marker that stopped matching it would put two writers back on the HUD.
 */
class HudNavigatorBuildTest {

    @Test
    fun theHudBuildDrawsTheHudItself() {
        assertTrue(HudNavigatorBuild.drawsHud("29.8.1-denza-hud-exp.1"))
    }

    @Test
    fun theOrdinaryNavigatorAndNoNavigatorLeaveTheHudToThisApp() {
        assertFalse(HudNavigatorBuild.drawsHud("29.8.1"))
        assertFalse(HudNavigatorBuild.drawsHud("30.4.0"))
        assertFalse(HudNavigatorBuild.drawsHud(""))
        assertFalse(HudNavigatorBuild.drawsHud(null as String?))
    }
}
