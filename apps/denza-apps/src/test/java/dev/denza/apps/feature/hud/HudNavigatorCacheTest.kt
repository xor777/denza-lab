package dev.denza.apps.feature.hud

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The host's filter asks for the navigator on every packet (the map many times a second): the
 * package manager is read once and then only when asked again - on Yandex's package changing, a
 * switch of the panel or a reading of its slice - and the host hears a change.
 */
class HudNavigatorCacheTest {

    @Test
    fun theNavigatorIsReadOnceAndThenOnlyWhenAskedAgain() {
        var installed = HudNavigatorInstall.YANDEX
        var reads = 0
        val cache = HudNavigatorCache<Unit> { reads++; installed }

        repeat(100) { assertEquals(HudNavigatorInstall.YANDEX, cache.current(Unit)) }
        assertEquals(1, reads)

        installed = HudNavigatorInstall.YANDEX_HUD
        assertEquals(HudNavigatorInstall.YANDEX, cache.current(Unit))
        var changes = 0
        assertEquals(HudNavigatorInstall.YANDEX_HUD, cache.refresh(Unit) { changes++ })
        assertEquals(HudNavigatorInstall.YANDEX_HUD, cache.current(Unit))
        assertEquals(1, changes)
        assertEquals(2, reads)

        cache.refresh(Unit) { changes++ }
        assertEquals("the same answer is no change", 1, changes)
    }

    @Test
    fun theFirstReadIsNoChange() {
        var changes = 0
        HudNavigatorCache<Unit> { HudNavigatorInstall.YANDEX_HUD }.refresh(Unit) { changes++ }
        assertEquals(0, changes)
    }
}
