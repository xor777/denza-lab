package dev.denza.apps

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

            val appPickerUpdate = submit { it.copy(appPickerVisible = true) }
            val fsePickerUpdate = submit { it.copy(fseInstallerPickerVisible = true) }

            assertTrue(bothReadInitialState.await(5, TimeUnit.SECONDS))
            releaseWrites.countDown()
            appPickerUpdate.get(5, TimeUnit.SECONDS)
            fsePickerUpdate.get(5, TimeUnit.SECONDS)

            assertTrue(store.state.value.appPickerVisible)
            assertTrue(store.state.value.fseInstallerPickerVisible)
        } finally {
            releaseWrites.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun conditionalUpdateRejectsAbaBetweenPredicateAndCommit() {
        val store = DenzaUiStateStore()
        val expectedLocale = store.snapshot().state.stockRussianLocale
        val runningLocale = expectedLocale.copy(running = true)
        val completedLocale = expectedLocale.copy()
        val predicateEntered = CountDownLatch(1)
        val releaseStaleCommit = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val staleUpdate = executor.submit<Boolean> {
                store.updateIf(
                    predicate = { current ->
                        val unchanged = current.stockRussianLocale === expectedLocale
                        predicateEntered.countDown()
                        check(releaseStaleCommit.await(5, TimeUnit.SECONDS))
                        unchanged
                    },
                    transform = { current ->
                        current.copy(
                            stockRussianLocale = expectedLocale.copy(
                                message = "Устаревшее значение",
                            ),
                        )
                    },
                )
            }

            assertTrue(predicateEntered.await(5, TimeUnit.SECONDS))
            store.update { current -> current.copy(stockRussianLocale = runningLocale) }
            store.update { current -> current.copy(stockRussianLocale = completedLocale) }
            releaseStaleCommit.countDown()

            assertFalse(staleUpdate.get(5, TimeUnit.SECONDS))
            assertEquals(expectedLocale, completedLocale)
            assertNotSame(expectedLocale, completedLocale)
            assertSame(completedLocale, store.state.value.stockRussianLocale)
        } finally {
            releaseStaleCommit.countDown()
            executor.shutdownNow()
        }
    }
}
