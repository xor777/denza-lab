package dev.denza.apps.feature.alice

import org.junit.Assert.*
import org.junit.Test

class NavigatorWindowPolicyTest {
    @Test fun `captured split selects unfocused visible Navigator and ignores divider with its title`() {
        // Live 2026-10-11: Navigator 355 on right, focused peer 346 on left.
        // Divider 1 is titled Yandex Navi but is TYPE_SYSTEM, not a Navigator app window.
        assertEquals(355, NavigatorWindowPolicy.select(listOf(
            NavigatorWindowCandidate(346, navigator = false, focused = true),
            NavigatorWindowCandidate(355, navigator = true),
            NavigatorWindowCandidate(1, type = 3, navigator = false),
        )))
    }

    @Test fun `peer identity and focus transfer do not change Navigator selection`() {
        for (peer in listOf(346, 900, 901)) {
            for (navigatorFocused in listOf(false, true)) {
                assertEquals(355, NavigatorWindowPolicy.select(listOf(
                    NavigatorWindowCandidate(peer, navigator = false, focused = !navigatorFocused),
                    NavigatorWindowCandidate(355, navigator = true, focused = navigatorFocused),
                )))
            }
        }
    }

    @Test fun `full screen Navigator remains eligible`() {
        assertEquals(355, NavigatorWindowPolicy.select(listOf(
            NavigatorWindowCandidate(355, navigator = true, focused = true),
        )))
    }

    @Test fun `hidden absent and completely covered Navigator leave stock route`() {
        assertNull(NavigatorWindowPolicy.select(emptyList()))
        assertNull(NavigatorWindowPolicy.select(listOf(
            NavigatorWindowCandidate(355, navigator = true, visibleArea = false, focused = true),
        )))
        assertNull(NavigatorWindowPolicy.select(listOf(
            NavigatorWindowCandidate(346, navigator = false, focused = true),
        )))
    }

    @Test fun `other displays unknown packages and non app windows cannot capture voice key`() {
        assertNull(NavigatorWindowPolicy.select(listOf(
            NavigatorWindowCandidate(355, displayId = 3, navigator = true, focused = true),
            NavigatorWindowCandidate(356, type = 3, navigator = true, focused = true),
            NavigatorWindowCandidate(357, navigator = false, focused = true),
        )))
    }

    @Test fun `multiple visible Navigator windows are ambiguous even if one is focused`() {
        assertNull(NavigatorWindowPolicy.select(listOf(
            NavigatorWindowCandidate(355, navigator = true, focused = true),
            NavigatorWindowCandidate(356, navigator = true),
        )))
    }
}
