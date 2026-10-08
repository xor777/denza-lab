package dev.denza.apps.feature.navigation

import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The executor order that stranded an application on the cluster.
 *
 * `NavigationCoordinator.projectToCluster` marks the session PROJECTING and asks the scene for a
 * surface; the surface callback queues the task that moves the application. A choice from the
 * chooser page (open before the steering-wheel key was pressed) could reach the single executor in
 * between. Each step below makes the decision the coordinator makes at that point, with the same
 * policy, fence and session, run on a queue in that order.
 */
class NavigationChoiceOrderTest {
    private val executor = QueueExecutor()
    private val projectionFence = NavigationLaunchFence()
    private var selected = YANDEX
    private var session = NavigationSession(taskId = YANDEX_TASK)

    @Test
    fun aChoiceQueuedBetweenTheProjectionAndItsSurfaceWaitsForTheProjectionToLand() {
        lateinit var projection: NavigationLaunchAttempt
        executor.execute { projection = beginProjection() }
        executor.execute { choose(MAPS) }
        executor.execute { surfaceArrives(projection) }

        executor.drain()

        assertEquals(YANDEX, selected)
        assertEquals(NavigationPhase.PROJECTED, session.phase)
        assertEquals(YANDEX, session.projectedPackage)
        assertEquals(YANDEX, session.returnPackage(selected))
    }

    @Test
    fun theReturnNamesTheProjectedApplicationAndTheChoiceChangesOnlyOnceItIsOff() {
        lateinit var projection: NavigationLaunchAttempt
        executor.execute { projection = beginProjection() }
        executor.execute { surfaceArrives(projection) }
        executor.drain()

        val returned = mutableListOf<String>()
        executor.execute {
            choose(MAPS) { packageName ->
                returned += packageName
                session = NavigationSession(taskId = YANDEX_TASK)
            }
        }
        executor.drain()

        assertEquals(listOf(YANDEX), returned)
        assertEquals(MAPS, selected)
        assertFalse(NavigationChoicePolicy.outgoingOnCluster(session))
    }

    @Test
    fun aReturnThatFailsKeepsTheChoiceWithTheApplicationStillOnTheCluster() {
        lateinit var projection: NavigationLaunchAttempt
        executor.execute { projection = beginProjection() }
        executor.execute { surfaceArrives(projection) }
        executor.execute {
            choose(MAPS) {
                // returnToCentralDisplay's failure: the session is copied, display and all.
                session = session.copy(
                    phase = NavigationPhase.NEEDS_ACTION,
                    message = "Повторите возврат приложения",
                )
            }
        }

        executor.drain()

        assertEquals(YANDEX, selected)
        assertTrue(NavigationChoicePolicy.outgoingOnCluster(session))
        assertEquals(YANDEX, session.returnPackage(selected))
    }

    @Test
    fun aSurfaceForASupersededProjectionMovesNothingAndGivesTheSceneBack() {
        val stale = projectionFence.begin(YANDEX)
        // The choice moved on before the surface task ran (the fence is invalidated on a change).
        projectionFence.invalidate()
        selected = MAPS
        session = NavigationSession(taskId = MAPS_TASK)

        assertFalse(projectionFence.accepts(stale, selected))
        assertTrue(NavigationChoicePolicy.abandonedSurfaceReleasesScene(session))

        // A newer projection in flight holds the scene; the stale surface leaves it alone.
        val newer = projectionFence.begin(MAPS)
        session = session.copy(phase = NavigationPhase.PROJECTING)
        assertFalse(projectionFence.accepts(stale, selected))
        assertTrue(projectionFence.accepts(newer, selected))
        assertFalse(NavigationChoicePolicy.abandonedSurfaceReleasesScene(session))
    }

    @Test
    fun aChoiceWaitsOnlyWhileAProjectionOrAReturnIsInFlight() {
        val inFlight = setOf(
            NavigationPhase.PROJECTING,
            NavigationPhase.RETURNING,
        )
        NavigationPhase.entries.forEach { phase ->
            assertEquals(
                phase.name,
                phase !in inFlight,
                NavigationChoicePolicy.admits(NavigationSession(phase = phase)),
            )
        }
    }

    /** `projectToCluster`, from the fence to PROJECTING. */
    private fun beginProjection(): NavigationLaunchAttempt {
        val projection = projectionFence.begin(selected)
        session = session.copy(phase = NavigationPhase.PROJECTING)
        return projection
    }

    /** The surface task: it moves the task only for its own projection of the chosen app. */
    private fun surfaceArrives(projection: NavigationLaunchAttempt) {
        if (!projectionFence.accepts(projection, selected)) return
        session = NavigationSession(
            phase = NavigationPhase.PROJECTED,
            taskId = YANDEX_TASK,
            virtualDisplayId = PROJECTION_DISPLAY,
            projectedPackage = projection.packageName,
        )
    }

    /** `selectPackage`; [returnOutgoing] stands for `returnToCentralDisplay`. */
    private fun choose(packageName: String, returnOutgoing: (String) -> Unit = {}) {
        if (selected == packageName) return
        if (!NavigationChoicePolicy.admits(session)) return
        if (NavigationChoicePolicy.outgoingOnCluster(session)) {
            returnOutgoing(session.returnPackage(selected))
            if (NavigationChoicePolicy.outgoingOnCluster(session)) return
        }
        projectionFence.invalidate()
        selected = packageName
        session = NavigationSession(taskId = MAPS_TASK)
    }

    private class QueueExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun drain() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    private companion object {
        const val YANDEX = "ru.yandex.yandexnavi"
        const val MAPS = "ru.yandex.yandexmaps"
        const val YANDEX_TASK = 41
        const val MAPS_TASK = 77
        const val PROJECTION_DISPLAY = 9
    }
}
