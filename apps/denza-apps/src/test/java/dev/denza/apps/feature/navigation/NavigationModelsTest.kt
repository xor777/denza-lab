package dev.denza.apps.feature.navigation

import dev.denza.apps.core.FeatureResolution
import dev.denza.apps.feature.cluster.ClusterMapPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationModelsTest {
    @Test
    fun primaryActionRejectsUninitializedPendingAndTransitionStates() {
        val ready = NavigationSession()

        assertNull(NavigationPrimaryActionPolicy.action(false, true, true, false, ready))
        assertNull(NavigationPrimaryActionPolicy.action(true, false, true, false, ready))
        assertNull(NavigationPrimaryActionPolicy.action(true, true, true, true, ready))
        assertNull(NavigationPrimaryActionPolicy.action(true, true, false, false, ready))
        listOf(
            NavigationPhase.OPENING,
            NavigationPhase.PROJECTING,
            NavigationPhase.RETURNING,
            NavigationPhase.RECOVERING,
        ).forEach { phase ->
            assertNull(
                NavigationPrimaryActionPolicy.action(
                    initialized = true,
                    hasContext = true,
                    selectedAppInstalled = true,
                    actionPending = false,
                    session = ready.copy(phase = phase),
                ),
            )
        }
        listOf(
            FeatureResolution.SELECT_NAVIGATION_APP,
            FeatureResolution.SELECT_CLUSTER_DISPLAY,
        ).forEach { resolution ->
            assertNull(
                NavigationPrimaryActionPolicy.action(
                    initialized = true,
                    hasContext = true,
                    selectedAppInstalled = true,
                    actionPending = false,
                    session = ready.copy(
                        phase = NavigationPhase.NEEDS_ACTION,
                        resolution = resolution,
                    ),
                ),
            )
        }
    }

    @Test
    fun primaryActionSelectsExactlyOneExecutableCommand() {
        assertEquals(
            NavigationPrimaryAction.PROJECT,
            NavigationPrimaryActionPolicy.action(
                initialized = true,
                hasContext = true,
                selectedAppInstalled = true,
                actionPending = false,
                session = NavigationSession(),
            ),
        )
        assertEquals(
            NavigationPrimaryAction.PROJECT,
            NavigationPrimaryActionPolicy.action(
                initialized = true,
                hasContext = true,
                selectedAppInstalled = true,
                actionPending = false,
                session = NavigationSession(taskId = 12),
            ),
        )
        assertEquals(
            NavigationPrimaryAction.RETURN,
            NavigationPrimaryActionPolicy.action(
                initialized = true,
                hasContext = true,
                selectedAppInstalled = false,
                actionPending = false,
                session = NavigationSession(
                    phase = NavigationPhase.PROJECTED,
                    taskId = 12,
                ),
            ),
        )
    }

    @Test
    fun missingAndExistingNavigatorTasksUseTheSameProjectionCommand() {
        listOf(null, 12).forEach { taskId ->
            assertEquals(
                NavigationPrimaryAction.PROJECT,
                NavigationPrimaryActionPolicy.action(
                    initialized = true,
                    hasContext = true,
                    selectedAppInstalled = true,
                    actionPending = false,
                    session = NavigationSession(taskId = taskId),
                ),
            )
        }
    }

    @Test
    fun navigatorUsesProjectionWordsWhileIdleAndBusy() {
        assertEquals("На приборку", NavigationSession().buttonLabel)
        assertEquals(
            "Проверяю",
            NavigationSession(phase = NavigationPhase.OPENING).buttonLabel,
        )
    }

    @Test
    fun unavailableSelectionAndDuplicateTapRemainRejected() {
        val ready = NavigationSession()

        assertNull(NavigationPrimaryActionPolicy.action(true, true, false, false, ready))
        assertNull(NavigationPrimaryActionPolicy.action(true, true, true, true, ready))
        assertNull(
            NavigationPrimaryActionPolicy.action(
                true,
                true,
                true,
                false,
                ready.copy(
                    phase = NavigationPhase.NEEDS_ACTION,
                    resolution = FeatureResolution.SELECT_NAVIGATION_APP,
                ),
            ),
        )
    }

    @Test
    fun ourOwnInstrumentsAreAddressedByThisAppsRealId() {
        val dashboard = NavigationAppPolicy.DASHBOARD_PACKAGE

        assertTrue(NavigationAppPolicy.isDashboard(dashboard))
        assertEquals("Приборы", NavigationAppPolicy.DASHBOARD_LABEL)
        // It is this app, addressed by its real id - not an application wearing our name.
        assertEquals("dev.denza.apps", dashboard)
        // And never an application: the rule both sides of the shell boundary read leaves it out,
        // so no path can hand this package to the task proxy.
        assertTrue(ProjectablePackages.isExcluded(dashboard, null))
        assertFalse(NavigationAppPolicy.isDashboard("ru.yandex.yandexnavi"))
    }

    @Test
    fun theDashboardIsPutOnThePanelAndTakenOffItRatherThanOpenedAndReturned() {
        val idle = NavigationSession(target = NavigationTarget.DASHBOARD)
        val shown = idle.copy(phase = NavigationPhase.PROJECTED)

        assertEquals("На приборку", idle.buttonLabel)
        assertEquals("Убрать", shown.buttonLabel)
        assertEquals(
            NavigationPrimaryAction.PROJECT,
            NavigationPrimaryActionPolicy.action(
                initialized = true,
                hasContext = true,
                // Nothing was installed for it and nothing needs to be: the app being asked
                // about is the one answering.
                selectedAppInstalled = false,
                actionPending = false,
                session = idle,
            ),
        )
        assertEquals(
            NavigationPrimaryAction.RETURN,
            NavigationPrimaryActionPolicy.action(
                initialized = true,
                hasContext = true,
                selectedAppInstalled = false,
                actionPending = false,
                session = shown,
            ),
        )
    }

    @Test
    fun theDashboardIsNeverLaunchedAsSomebodyElsesTask() {
        // An application with no task of its own is opened first. There is no such step here, so an
        // absent task must not turn the button into "Открыть" and send the coordinator hunting for
        // a launch intent that would only re-open this very app.
        listOf(null, 12).forEach { taskId ->
            val session = NavigationSession(
                target = NavigationTarget.DASHBOARD,
                taskId = taskId,
            )
            assertEquals("На приборку", session.buttonLabel)
            assertEquals(
                NavigationPrimaryAction.PROJECT,
                NavigationPrimaryActionPolicy.action(true, true, true, false, session),
            )
        }
    }

    @Test
    fun theDashboardWaitsWhileTheDriverPicksTheInstrumentDisplay() {
        val waiting = NavigationSession(
            target = NavigationTarget.DASHBOARD,
            phase = NavigationPhase.NEEDS_ACTION,
            message = "Выберите приборный экран",
            resolution = FeatureResolution.SELECT_CLUSTER_DISPLAY,
        )

        assertNull(NavigationPrimaryActionPolicy.action(true, true, true, false, waiting))
        // The picker is what resolves it, and the retry after it is the same command as the first
        // attempt rather than a special recovery path.
        assertTrue(NavigationRecovery.shouldRetryAfterClusterSelection(waiting))
        assertEquals(
            NavigationPrimaryAction.PROJECT,
            NavigationPrimaryActionPolicy.action(
                true,
                true,
                true,
                false,
                waiting.copy(resolution = FeatureResolution.RETRY),
            ),
        )
    }

    @Test
    fun onlyAnApplicationHasAnywhereElseToGoOnThePanel() {
        val dashboard = NavigationAppPolicy.DASHBOARD_PACKAGE

        assertEquals(
            listOf(ClusterMapPlacement.FULL),
            NavigationPlacementPolicy.offered(dashboard),
        )
        assertEquals(
            ClusterMapPlacement.entries.toList(),
            NavigationPlacementPolicy.offered("ru.yandex.yandexnavi"),
        )
        // Any application, not the navigators alone: placement is a property of a picture.
        assertEquals(
            ClusterMapPlacement.entries.toList(),
            NavigationPlacementPolicy.offered("org.videolan.vlc"),
        )
    }

    @Test
    fun aPlacementTheChoiceDoesNotOfferFallsBackToTheOneItDoes() {
        val dashboard = NavigationAppPolicy.DASHBOARD_PACKAGE

        // The applications' own "Справа" survives being chosen while the dashboard is on the panel:
        // it is stored untouched, and it is what comes back when an application is chosen again.
        assertEquals(
            ClusterMapPlacement.FULL,
            NavigationPlacementPolicy.resolve(dashboard, ClusterMapPlacement.RIGHT),
        )
        assertEquals(
            ClusterMapPlacement.RIGHT,
            NavigationPlacementPolicy.resolve("ru.yandex.yandexnavi", ClusterMapPlacement.RIGHT),
        )
    }

    @Test
    fun proxyDeathNeverCreatesAnAutostartSession() {
        val recovered = NavigationRecovery.proxyLost(NavigationSession())
        assertEquals(NavigationPhase.READY, recovered.phase)
        assertNull(recovered.virtualDisplayId)
    }

    @Test
    fun projectedTaskMovesToRecoveringWhenProxyDies() {
        val recovered = NavigationRecovery.proxyLost(
            NavigationSession(
                phase = NavigationPhase.PROJECTED,
                taskId = 12,
                virtualDisplayId = 8,
            ),
        )
        assertEquals(NavigationPhase.RECOVERING, recovered.phase)
        assertEquals(12, recovered.taskId)
    }

    @Test
    fun userResolutionDoesNotChangeTheExistingPrimaryLabel() {
        val session = NavigationSession(
            phase = NavigationPhase.NEEDS_ACTION,
            taskId = 12,
            message = "Выберите приборный экран",
            resolution = FeatureResolution.SELECT_CLUSTER_DISPLAY,
        )

        assertEquals(FeatureResolution.SELECT_CLUSTER_DISPLAY, session.resolution)
        assertEquals("На приборку", session.buttonLabel)
    }

    @Test
    fun recoveryClearsAStaleUserResolution() {
        val recovered = NavigationRecovery.proxyLost(
            NavigationSession(
                phase = NavigationPhase.PROJECTED,
                taskId = 12,
                virtualDisplayId = 8,
                resolution = FeatureResolution.RETRY,
            ),
        )

        assertEquals(NavigationPhase.RECOVERING, recovered.phase)
        assertNull(recovered.resolution)
    }

    @Test
    fun clusterSelectionRetriesOnlyTheMatchingActionableProjection() {
        val waitingForDisplay = NavigationSession(
            phase = NavigationPhase.NEEDS_ACTION,
            taskId = 12,
            resolution = FeatureResolution.SELECT_CLUSTER_DISPLAY,
        )

        assertTrue(NavigationRecovery.shouldRetryAfterClusterSelection(waitingForDisplay))
        assertFalse(
            NavigationRecovery.shouldRetryAfterClusterSelection(
                waitingForDisplay.copy(resolution = FeatureResolution.RETRY),
            ),
        )
        assertFalse(
            NavigationRecovery.shouldRetryAfterClusterSelection(
                waitingForDisplay.copy(phase = NavigationPhase.PROJECTED),
            ),
        )
    }

    @Test
    fun missingTaskObservationNeverAuthorizesProjectionTeardown() {
        val tracker = NavigationProjectionHealthTracker()

        repeat(5) {
            assertEquals(
                NavigationProjectionHealthDecision.Uncertain(-1, 0),
                tracker.observe(actualDisplayId = -1, expectedDisplayId = 16),
            )
        }
    }

    @Test
    fun displayChurnRequiresTwoMatchingPositiveDepartureObservations() {
        val tracker = NavigationProjectionHealthTracker()

        assertEquals(
            NavigationProjectionHealthDecision.Uncertain(17, 1),
            tracker.observe(actualDisplayId = 17, expectedDisplayId = 16),
        )
        assertEquals(
            NavigationProjectionHealthDecision.Healthy,
            tracker.observe(actualDisplayId = 16, expectedDisplayId = 16),
        )
        assertEquals(
            NavigationProjectionHealthDecision.Uncertain(17, 1),
            tracker.observe(actualDisplayId = 17, expectedDisplayId = 16),
        )
        assertEquals(
            NavigationProjectionHealthDecision.ConfirmedElsewhere(17),
            tracker.observe(actualDisplayId = 17, expectedDisplayId = 16),
        )
    }

    @Test
    fun differentUnexpectedDisplaysDoNotConfirmAProjectionDeparture() {
        val tracker = NavigationProjectionHealthTracker()

        assertEquals(
            NavigationProjectionHealthDecision.Uncertain(17, 1),
            tracker.observe(actualDisplayId = 17, expectedDisplayId = 16),
        )
        assertEquals(
            NavigationProjectionHealthDecision.Uncertain(3, 1),
            tracker.observe(actualDisplayId = 3, expectedDisplayId = 16),
        )
    }

    @Test
    fun projectionCleanupCanReleaseWhenOwnedDisplayIsAlreadyGone() {
        assertEquals(
            NavigationProjectionCleanupDecision.RELEASE,
            navigationProjectionCleanupDecision(
                ownedDisplayId = 16,
                ownedDisplayAlive = false,
                actualTaskDisplayId = null,
            ),
        )
    }

    @Test
    fun projectionCleanupCanReleaseWhenTaskIsPositivelyElsewhere() {
        assertEquals(
            NavigationProjectionCleanupDecision.RELEASE,
            navigationProjectionCleanupDecision(
                ownedDisplayId = 16,
                ownedDisplayAlive = true,
                actualTaskDisplayId = 0,
            ),
        )
    }

    @Test
    fun projectionCleanupReturnsTaskBeforeReleasingItsLiveDisplay() {
        assertEquals(
            NavigationProjectionCleanupDecision.RETURN_THEN_RELEASE,
            navigationProjectionCleanupDecision(
                ownedDisplayId = 16,
                ownedDisplayAlive = true,
                actualTaskDisplayId = 16,
            ),
        )
    }

    @Test
    fun projectionCleanupPreservesLiveDisplayWhenTaskLocationIsUnknown() {
        listOf(null, -1).forEach { actualDisplayId ->
            assertEquals(
                NavigationProjectionCleanupDecision.PRESERVE,
                navigationProjectionCleanupDecision(
                    ownedDisplayId = 16,
                    ownedDisplayAlive = true,
                    actualTaskDisplayId = actualDisplayId,
                ),
            )
        }
    }
}
