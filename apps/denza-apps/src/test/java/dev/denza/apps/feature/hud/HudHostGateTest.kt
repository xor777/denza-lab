package dev.denza.apps.feature.hud

import dev.denza.hudlink.HudSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Only the Navigator package signed with the YandexHUD key publishes to this app's HUD. */
class HudHostGateTest {
    private val yandexHud = HudHostGate.YANDEX_HUD_CERTIFICATES.single()
    private val yandex = "0".repeat(64)

    @Test
    fun onlyTheDedicatedHudSpeedSignerIsRadar() {
        val radar = HudHostGate.HUD_SPEED_CERTIFICATES.single()
        assertEquals(HudSource.RADAR, HudHostGate.admit(listOf("air.StrelkaHUDFREE")) { setOf(radar) })
        assertNull(HudHostGate.admit(listOf("air.StrelkaHUDFREE")) { setOf(yandexHud) })
        assertNull(HudHostGate.admit(listOf("air.StrelkaHUDFREE")) { setOf(yandex) })
        assertNull(HudHostGate.admit(listOf("other.package")) { setOf(radar) })
        assertNull(HudHostGate.admit(listOf("air.StrelkaHUDFREE")) { setOf(radar, yandex) })
    }

    @Test
    fun theNavigatorSignedWithTheYandexHudKeyIsTheModule() {
        assertEquals(
            HudSource.MODULE,
            HudHostGate.admit(listOf("ru.yandex.yandexnavi")) { setOf(yandexHud) },
        )
    }

    @Test
    fun theOfficialNavigatorIsRefused() {
        assertNull(HudHostGate.admit(listOf("ru.yandex.yandexnavi")) { setOf(yandex) })
    }

    @Test
    fun anotherPackageWithTheSameKeyIsRefused() {
        assertNull(HudHostGate.admit(listOf("dev.denza.hudsurfaces.probe")) { setOf(yandexHud) })
        assertNull(HudHostGate.admit(emptyList()) { setOf(yandexHud) })
    }

    @Test
    fun theDigestIsTheLowerCaseHexOfTheCertificatesSha256() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            HudHostGate.sha256(ByteArray(0)),
        )
    }
}
