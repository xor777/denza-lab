package dev.denza.apps

import dev.denza.apps.core.Decision
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DenzaUiStateStoreTest {
    @Test
    fun concurrentUpdatesPreserveBothIndependentChanges() {
        val store = DenzaUiStateStore()
        val bothReadInitialState = CountDownLatch(2)
        val releaseWrites = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            fun submit(change: (DenzaUiState) -> DenzaUiState) = executor.submit {
                store.update { current ->
                    bothReadInitialState.countDown()
                    check(releaseWrites.await(5, TimeUnit.SECONDS))
                    change(current)
                }
            }

            val weatherUpdate = submit { it.copy(weatherEnabled = false) }
            val cloudUpdate = submit { it.copy(cloudLinkBusy = true) }

            assertTrue(bothReadInitialState.await(5, TimeUnit.SECONDS))
            releaseWrites.countDown()
            weatherUpdate.get(5, TimeUnit.SECONDS)
            cloudUpdate.get(5, TimeUnit.SECONDS)

            assertFalse(store.state.value.weatherEnabled)
            assertTrue(store.state.value.cloudLinkBusy)
        } finally {
            releaseWrites.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun conditionalUpdateRejectsAbaBetweenPredicateAndCommit() {
        val store = DenzaUiStateStore()
        val expectedLocale = store.snapshot().state.systemLanguage
        val runningLocale = expectedLocale.copy(name = "Türkçe")
        val completedLocale = expectedLocale.copy()
        val predicateEntered = CountDownLatch(1)
        val releaseStaleCommit = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val staleUpdate = executor.submit<Boolean> {
                store.updateIf(
                    predicate = { current ->
                        val unchanged = current.systemLanguage === expectedLocale
                        predicateEntered.countDown()
                        check(releaseStaleCommit.await(5, TimeUnit.SECONDS))
                        unchanged
                    },
                    transform = { current ->
                        current.copy(
                            systemLanguage = expectedLocale.copy(
                                name = "Устаревшее значение",
                            ),
                        )
                    },
                )
            }

            assertTrue(predicateEntered.await(5, TimeUnit.SECONDS))
            store.update { current -> current.copy(systemLanguage = runningLocale) }
            store.update { current -> current.copy(systemLanguage = completedLocale) }
            releaseStaleCommit.countDown()

            assertFalse(staleUpdate.get(5, TimeUnit.SECONDS))
            assertEquals(expectedLocale, completedLocale)
            assertNotSame(expectedLocale, completedLocale)
            assertSame(completedLocale, store.state.value.systemLanguage)
        } finally {
            releaseStaleCommit.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun aCellWritesItsOwnFieldsAndLeavesTheRestAsTheyStand() {
        val store = DenzaUiStateStore()
        val weather = store.cell(
            get = { state: DenzaUiState -> state.weatherEnabled },
            set = { state, enabled -> state.copy(weatherEnabled = enabled) },
        )
        store.update { it.copy(cloudLinkBusy = true) }

        weather.update { !it }

        assertFalse(weather.value)
        assertFalse(store.state.value.weatherEnabled)
        assertTrue("a cell's write kept another field", store.state.value.cloudLinkBusy)
        assertFalse(weather.updateIf(predicate = { it }, transform = { true }))
        assertFalse(store.state.value.weatherEnabled)
    }

    /**
     * A claim decided over a state somebody has since written is decided again over the new one:
     * the default applications' «APPLYING» and the passenger install's «busy» depend on it.
     */
    @Test
    fun aCellDecidesAgainWhenAWriteLandsBetweenItsDecisionAndItsCommit() {
        val store = DenzaUiStateStore()
        val busy = store.cell(
            get = { state: DenzaUiState -> state.cloudLinkBusy },
            set = { state, value -> state.copy(cloudLinkBusy = value) },
        )
        var decisions = 0

        val claimed = busy.decide { current ->
            decisions += 1
            // Another writer takes the cell after this decision is made, the first time only.
            if (decisions == 1) store.update { it.copy(cloudLinkBusy = true) }
            if (current) Decision.none(false) else Decision(true, true)
        }

        assertFalse("the claim was granted over a value it never saw", claimed)
        assertEquals(2, decisions)
        assertTrue(store.state.value.cloudLinkBusy)
    }

    @Test
    fun aDecisionWithNothingToWriteWritesNothing() {
        val store = DenzaUiStateStore()
        val before = store.snapshot()
        val cell = store.cell(
            get = { state: DenzaUiState -> state.weatherEnabled },
            set = { state, value -> state.copy(weatherEnabled = value) },
        )

        assertEquals("answer", cell.decide { Decision.none("answer") })
        assertSame(before, store.snapshot())
    }
}
