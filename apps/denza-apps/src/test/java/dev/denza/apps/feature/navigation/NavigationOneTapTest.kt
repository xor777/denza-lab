package dev.denza.apps.feature.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One press from a navigator that is not running to that navigator on the driver's display, held
 * on what [NavigationOneTap] decides after each step [NavigationCoordinator] takes.
 *
 * Until 2026-10-09 this was `NavigationOneTapSourceContractTest`, which cut the coordinator's
 * methods out of its source and looked for lines in them: a renamed variable failed it, and a
 * request that was never cleared passed it. The decisions live in a class of their own now, and
 * the coordinator around them opens, looks and moves.
 */
class NavigationOneTapTest {

    /** A task lookup that answers [task] and remembers what it was asked. */
    private class Tasks(private val task: Int) : (String) -> Int {
        val asked = mutableListOf<String>()

        override fun invoke(packageName: String): Int {
            asked += packageName
            return task
        }
    }

    @Test
    fun aPressThatFindsNoTaskProjectsOnceTheLaunchHasOne() {
        val tap = NavigationOneTap()
        tap.open(project = true)
        val launch = tap.begin(NAVI)

        assertEquals("a launch still starting", NavigationDiscovery.NotYet, tap.discover(launch, NAVI, Tasks(-1)))
        assertEquals("the task carries on to the cluster", NavigationDiscovery.Project(17), tap.discover(launch, NAVI, Tasks(17)))
        // The request is answered once: a later look of the same launch does not project again.
        assertEquals(NavigationDiscovery.Settle(17), tap.discover(launch, NAVI, Tasks(17)))
    }

    @Test
    fun aLaunchThatWasNotAskedToProjectSettlesOnTheCentralScreen() {
        val tap = NavigationOneTap()
        tap.open(project = false)
        val launch = tap.begin(NAVI)

        assertEquals(NavigationDiscovery.Settle(17), tap.discover(launch, NAVI, Tasks(17)))
    }

    /** The look asks for the package the attempt launched, and only while it is still wanted. */
    @Test
    fun aLookAsksForTheLaunchedPackageAndAStaleOneAsksNothing() {
        val tap = NavigationOneTap()
        tap.open(project = true)
        val launch = tap.begin(NAVI)
        val tasks = Tasks(17)

        tap.discover(launch, NAVI, tasks)
        assertEquals(listOf(NAVI), tasks.asked)

        // The selection moved on: the shell is not asked about a launch nobody is waiting for.
        val moved = Tasks(17)
        assertEquals(NavigationDiscovery.Stale, tap.discover(launch, MAPS, moved))
        assertTrue(moved.asked.isEmpty())
    }

    @Test
    fun anotherChoiceVoidsTheLaunchAndWhatItCarried() {
        val tap = NavigationOneTap()
        tap.open(project = true)
        val launch = tap.begin(NAVI)

        assertTrue("a launch in flight is let go of", tap.cancel(NavigationPhase.OPENING))
        val late = Tasks(17)
        assertEquals(
            "its discovery arrives late and is dropped, the same package or not",
            NavigationDiscovery.Stale,
            tap.discover(launch, NAVI, late),
        )
        assertTrue(late.asked.isEmpty())

        // The next launch of the new choice starts with nothing carried over from the old one.
        val next = tap.begin(MAPS)
        assertEquals(NavigationDiscovery.Settle(23), tap.discover(next, MAPS, Tasks(23)))
    }

    /** Nothing in flight, nothing to let go of; the request goes all the same. */
    @Test
    fun aChoiceWithNoLaunchInFlightHasNothingToLetGo() {
        val tap = NavigationOneTap()
        tap.open(project = true)

        assertFalse(tap.cancel(NavigationPhase.PROJECTED))
        val launch = tap.begin(NAVI)
        assertEquals(NavigationDiscovery.Settle(17), tap.discover(launch, NAVI, Tasks(17)))
    }

    /** A launch that failed or never found a task carries nothing on to the next one. */
    @Test
    fun aLaunchThatEndsWithoutATaskDropsItsRequest() {
        val tap = NavigationOneTap()
        tap.open(project = true)
        tap.begin(NAVI)
        tap.abandon()

        val again = tap.begin(NAVI)
        assertEquals(NavigationDiscovery.Settle(17), tap.discover(again, NAVI, Tasks(17)))
    }

    /** An older look cannot answer for a newer launch of the same package. */
    @Test
    fun anOlderLaunchsLookIsStaleOnceANewerOneStarts() {
        val tap = NavigationOneTap()
        tap.open(project = true)
        val first = tap.begin(NAVI)
        val second = tap.begin(NAVI)

        assertEquals(NavigationDiscovery.Stale, tap.discover(first, NAVI, Tasks(17)))
        assertEquals(NavigationDiscovery.Project(17), tap.discover(second, NAVI, Tasks(17)))
    }

    private companion object {
        const val NAVI = "ru.yandex.yandexnavi"
        const val MAPS = "ru.yandex.yandexmaps"
    }
}
