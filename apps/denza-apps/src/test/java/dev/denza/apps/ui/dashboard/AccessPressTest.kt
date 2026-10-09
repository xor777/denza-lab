package dev.denza.apps.ui.dashboard

import dev.denza.apps.DenzaUiState
import dev.denza.apps.feature.simulcast.SimulcastCoordinator
import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureReducer
import dev.denza.apps.feature.fse.FseInstallFailure
import dev.denza.apps.feature.fse.FseInstallResult
import dev.denza.apps.feature.fse.FseInstallStatus
import dev.denza.apps.feature.navigation.NavigationPhase
import dev.denza.apps.feature.navigation.NavigationSession
import dev.denza.apps.feature.navigation.NavigationStep
import dev.denza.apps.feature.navigation.NavigationWords
import dev.denza.disharebridge.AdbFailures
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A press on «Нет доступа» goes and looks at the car's access before trying anything.
 *
 * It used to retry straight away: over a car that no longer trusts the app the retry fails the
 * same way and puts the same words back, a press that does nothing. The check either finds the
 * access still there - and the feature tries again - or brings up the startup gate, which is the
 * choice the driver actually has.
 */
class AccessPressTest {

    private val calls = mutableListOf<String>()
    private var trusted = true

    @Test
    fun theDriversScreenChecksAccessAndThenTriesAgain() {
        val session = NavigationWords.failed(NavigationStep.PROJECT, AdbFailures.authorizationRequired()).let {
            NavigationSession(phase = NavigationPhase.NEEDS_ACTION, message = it.message, resolution = it.resolution)
        }
        press(TileId.CLUSTER, DenzaUiState(navigation = session.snapshot()))
        assertEquals(listOf("check", "navigation"), calls)
    }

    @Test
    fun theProjectionAndTheHudCheckFirst() {
        val problem = SimulcastCoordinator.setupProblem(SocketTimeoutException("Read timed out"))
        val simulcast = FeatureReducer.needsAction(FeatureReducer.starting(FeatureId.SIMULCAST), problem.message, resolution = problem.resolution)
        press(TileId.SIMULCAST, DenzaUiState(simulcast = simulcast))
        val hud = FeatureReducer.needsAction(FeatureReducer.starting(FeatureId.HUD_GUIDANCE), problem.message, resolution = problem.resolution)
        press(TileId.HUD, DenzaUiState(hudGuidance = hud))
        assertEquals(listOf("check", "repair simulcast", "check", "hud on"), calls)
    }

    @Test
    fun thePassengerScreenChecksBeforeTheChooserOpensAgain() {
        val failed = FseInstallStatus.of(FseInstallResult.Failed(FseInstallFailure.NO_ACCESS))
        press(TileId.PASSENGER, DenzaUiState(fseInstaller = failed))
        assertEquals(listOf("check", "chooser"), calls)

        // Any other ending is the install's own, and the press is the chooser as before.
        calls.clear()
        press(TileId.PASSENGER, DenzaUiState(fseInstaller = FseInstallStatus.of(FseInstallResult.Failed(FseInstallFailure.NO_ANSWER))))
        assertEquals(listOf("chooser"), calls)
    }

    /** When the car no longer trusts the app, the gate comes up and nothing is retried under it. */
    @Test
    fun aCarThatNoLongerTrustsTheAppIsLeftToTheGate() {
        trusted = false
        val session = NavigationWords.failed(NavigationStep.OPEN, AdbFailures.authorizationRequired()).let {
            NavigationSession(phase = NavigationPhase.NEEDS_ACTION, message = it.message, resolution = it.resolution)
        }
        press(TileId.CLUSTER, DenzaUiState(navigation = session.snapshot()))
        assertEquals(listOf("check"), calls)
    }

    private fun press(id: TileId, state: DenzaUiState) {
        DashboardPress.perform(DashboardTiles.of(state).first { it.id == id }, state, actions)
    }

    private val actions = DashboardActions(
        app = object : IdleActions() {
            override val onRepairSimulcast: () -> Unit = { calls += "repair simulcast" }
            override val onNavigationAction: () -> Unit = { calls += "navigation" }
            override val onToggleHudGuidance: (Boolean) -> Unit =
                { calls += if (it) "hud on" else "hud off" }
            override val onCheckAdbAccessThen: (() -> Unit) -> Unit = { onTrusted ->
                calls += "check"
                if (trusted) onTrusted()
            }
        },
        onChooseApps = {},
        onChooseNavigationApp = {},
        onChooseFseApp = { calls += "chooser" },
        onOpenClusterPicker = {},
        onOpenService = {},
        onOpenSettings = {},
    )
}
