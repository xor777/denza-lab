package dev.denza.apps.feature.hud

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «YandexHUD» is told from the ordinary Navigator by its version name alone. The HUD build's names
 * are spelled out as its build tool writes them: builds already handed out carry exactly these, and
 * a marker that stopped matching one would put two writers back on the HUD - which the rename to
 * `29.8.1-YandexHUD.1` did once. The name the build tool writes today is read from its own
 * `identity.py`, so the next rename fails here first.
 */
class HudNavigatorBuildTest {

    @Test
    fun theNameTheBuildToolWritesTodayDrawsTheHud() {
        val name = identityVersionName()
        assertTrue(
            "identity.py writes $name, which this app would read as the ordinary Yandex",
            HudNavigatorBuild.drawsHud(name),
        )
    }

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

    /**
     * `VERSION_NAME` of `tools/yandex_hud_build/identity.py`: its f-string with every `{NAME}`
     * replaced by that file's own `NAME = '…'` or `NAME = <number>`. A form this cannot evaluate
     * fails the test rather than passing it.
     */
    private fun identityVersionName(): String {
        val start = File(requireNotNull(System.getProperty("user.dir")) { "user.dir" })
        val identity = generateSequence(start) { it.parentFile }
            .map { File(it, "tools/yandex_hud_build/identity.py") }
            .firstOrNull { it.isFile }
            ?: error("tools/yandex_hud_build/identity.py is not above ${System.getProperty("user.dir")}")
        val text = identity.readText()
        val constants = Regex("""(?m)^([A-Z_]+)\s*=\s*(?:'([^']*)'|([0-9]+))\s*$""").findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2].ifEmpty { it.groupValues[3] } }
        val template = Regex("""(?m)^VERSION_NAME\s*=\s*f'([^']*)'\s*$""").find(text)?.groupValues?.get(1)
            ?: error("identity.py has no VERSION_NAME = f'…' line")
        return Regex("""\{([A-Z_]+)\}""").replace(template) { match ->
            constants[match.groupValues[1]] ?: error("identity.py does not define ${match.groupValues[1]}")
        }
    }
}
