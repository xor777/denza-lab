package dev.denza.apps.feature.defaultapps

import dev.denza.apps.DenzaUiState
import dev.denza.apps.DenzaUiStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The claims that mark a tap before its write leaves, over the roles' field of the dashboard's
 * state.
 *
 * A role's write owns it until the provider has echoed it back: the grid has already moved its mark,
 * and a second tap, the switch or a refresh landing over it would put a mark the car never confirmed
 * on top of one it is still writing. These are the two claims that keep off a role in APPLYING; the
 * writes themselves need the car's provider and are not run here.
 */
class DefaultAppsRuntimeTest {
    private val store = DenzaUiStateStore()
    private val runtime = DefaultAppsRuntime(
        state = store.cell(
            get = DenzaUiState::defaultApps,
            set = { state, defaults -> state.copy(defaultApps = defaults) },
        ),
        context = { null },
    )

    private val yandexMusic = "ru.yandex.music"
    private val vlc = "org.videolan.vlc"

    @Test
    fun aTapOnARoleWhoseWriteIsInFlightIsRefusedAndWritesNothing() {
        roles { role ->
            if (role == DefaultAppRole.MUSIC) {
                ready(role, yandexMusic).copy(status = DefaultAppRoleStatus.APPLYING, pendingPackageName = vlc)
            } else {
                ready(role, role.stockPackageName)
            }
        }
        val before = store.snapshot()

        assertFalse(runtime.claimSelection(DefaultAppRole.MUSIC, yandexMusic, setOf(yandexMusic, vlc)))
        assertSame("a refused tap wrote over the write in flight", before, store.snapshot())
    }

    @Test
    fun aTapOnALaunchableApplicationMovesTheMarkBeforeTheWriteLeaves() {
        roles { role -> ready(role, if (role == DefaultAppRole.MUSIC) yandexMusic else role.stockPackageName) }

        assertTrue(runtime.claimSelection(DefaultAppRole.MUSIC, vlc, setOf(yandexMusic, vlc)))

        val music = store.state.value.defaultApps.stateFor(DefaultAppRole.MUSIC)
        assertEquals(DefaultAppRoleStatus.APPLYING, music.status)
        assertEquals(vlc, music.pendingPackageName)
        assertEquals(listOf(vlc), music.choices.filter { it.selected }.map { it.packageName })
        assertEquals(yandexMusic, music.selectedPackageName)
    }

    @Test
    fun aTapOnAnApplicationTheCarNoLongerLaunchesSaysSoAndLeavesNothingToWrite() {
        roles { role -> ready(role, role.stockPackageName) }

        assertFalse(runtime.claimSelection(DefaultAppRole.VIDEO, "ru.gone", setOf(vlc)))

        val video = store.state.value.defaultApps.stateFor(DefaultAppRole.VIDEO)
        assertEquals(DefaultAppRoleStatus.ERROR, video.status)
        assertEquals(DefaultAppsWords.GONE, video.message)
        assertNull(video.pendingPackageName)
    }

    /** The switch writes every role it can and keeps off the one whose own write is in flight. */
    @Test
    fun theSwitchSkipsARoleInFlightAndOneAlreadyHoldingItsTarget() {
        roles { role ->
            when (role) {
                DefaultAppRole.NAVIGATION -> ready(role, "ru.yandex.yandexnavi")
                    .copy(status = DefaultAppRoleStatus.APPLYING, pendingPackageName = "com.waze")
                DefaultAppRole.MUSIC -> ready(role, yandexMusic)
                DefaultAppRole.VIDEO -> ready(role, role.stockPackageName)
            }
        }
        val asked = mutableListOf<DefaultAppRole>()

        val targets = runtime.claimSwitch { role ->
            asked += role
            role.stockPackageName
        }

        assertEquals(mapOf(DefaultAppRole.MUSIC to DefaultAppRole.MUSIC.stockPackageName), targets)
        assertEquals(listOf(DefaultAppRole.MUSIC, DefaultAppRole.VIDEO), asked)
        val defaults = store.state.value.defaultApps
        assertEquals("com.waze", defaults.stateFor(DefaultAppRole.NAVIGATION).pendingPackageName)
        assertEquals(DefaultAppRoleStatus.APPLYING, defaults.stateFor(DefaultAppRole.MUSIC).status)
        assertEquals(
            DefaultAppRole.MUSIC.stockPackageName,
            defaults.stateFor(DefaultAppRole.MUSIC).pendingPackageName,
        )
        assertEquals(DefaultAppRoleStatus.READY, defaults.stateFor(DefaultAppRole.VIDEO).status)
    }

    @Test
    fun aSwitchWithNothingToWriteClaimsNothing() {
        roles { role -> ready(role, role.stockPackageName) }
        val before = store.snapshot()

        assertNull(runtime.claimSwitch { role -> role.stockPackageName })
        assertSame(before, store.snapshot())
    }

    private fun roles(of: (DefaultAppRole) -> DefaultAppRoleUiState) {
        store.update { it.copy(defaultApps = DefaultAppsUiState(roles = DefaultAppRole.entries.map(of))) }
    }

    private fun ready(role: DefaultAppRole, selected: String) = DefaultAppRoleUiState(
        role = role,
        selectedPackageName = selected,
        selectedLabel = selected,
        choices = listOf(role.stockPackageName, yandexMusic, vlc).distinct().map { packageName ->
            DefaultAppChoice(
                packageName = packageName,
                label = packageName,
                selected = packageName == selected,
                known = packageName != role.stockPackageName,
                stock = packageName == role.stockPackageName,
            )
        },
        status = DefaultAppRoleStatus.READY,
        providerConfirmed = true,
    )
}
