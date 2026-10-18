package dev.denza.apps.feature.hud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «YandexHUD» is told from the ordinary Navigator by its version name alone. The HUD build's names
 * are spelled out as its build tool writes them: builds already handed out carry exactly these, and
 * a marker that stopped matching one would put two writers back on the HUD - which the rename to
 * `29.8.1-YandexHUD.1` did once.
 */
class HudNavigatorBuildTest {

    @Test
    fun theFirstBuildsDrawTheHudThemselves() {
        assertTrue(HudNavigatorBuild.drawsHud("29.8.1-denza-hud-exp.1"))
    }

    @Test
    fun theNumberedBuildsDrawTheHudThemselves() {
        assertTrue(HudNavigatorBuild.drawsHud("29.8.1-YandexHUD.1"))
        assertTrue(HudNavigatorBuild.drawsHud("29.8.1-YandexHUD.12"))
        // The base follows Yandex's APK; the label and the number are what name the build.
        assertTrue(HudNavigatorBuild.drawsHud("30.4.0-YandexHUD.3"))
    }

    @Test
    fun theLabelWithoutItsNumberOrInAnotherCaseIsNotTheBuild() {
        assertFalse(HudNavigatorBuild.drawsHud("29.8.1-YandexHUD"))
        assertFalse(HudNavigatorBuild.drawsHud("29.8.1-YandexHUD."))
        assertFalse(HudNavigatorBuild.drawsHud("29.8.1-YandexHUDx"))
        assertFalse(HudNavigatorBuild.drawsHud("29.8.1-YandexHUD.1x"))
        assertFalse(HudNavigatorBuild.drawsHud("29.8.1-yandexhud.1"))
        assertFalse(HudNavigatorBuild.drawsHud("YandexHUD"))
    }

    @Test
    fun theOrdinaryNavigatorAndNoNavigatorLeaveTheHudToThisApp() {
        assertFalse(HudNavigatorBuild.drawsHud("29.8.1"))
        assertFalse(HudNavigatorBuild.drawsHud("30.4.0"))
        assertFalse(HudNavigatorBuild.drawsHud(""))
        assertFalse(HudNavigatorBuild.drawsHud(null as String?))
    }
}
