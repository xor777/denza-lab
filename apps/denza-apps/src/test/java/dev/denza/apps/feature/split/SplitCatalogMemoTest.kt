package dev.denza.apps.feature.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Contract 1.4.2 and 1.5.6: the catalog is kept current by package events, not by rescanning.
 *
 * These are the two halves of that promise and they pull against each other, so both are asserted
 * here: the open path may not pay for a scan it does not need, and the user may never be offered an
 * app that is gone.
 */
class SplitCatalogMemoTest {

    private var observable = true
    private var scans = 0

    private val memo = SplitCatalogMemo { observable }

    @Test
    fun repeatedReadsOfOneCatalogScanOnlyOnce() {
        assertEquals(listOf("a"), read())
        assertEquals(listOf("a"), read())
        assertEquals(listOf("a"), read())

        assertEquals(1, scans)
    }

    @Test
    fun aPackageEventMakesTheNextReadScanAgain() {
        read()
        memo.invalidate()

        read()

        assertEquals(2, scans)
    }

    @Test
    fun everyCatalogIsRebuiltAfterOnePackageEvent() {
        memo.read("targets") { "targets-$scans".also { scans += 1 } }
        memo.read("apps") { "apps-$scans".also { scans += 1 } }

        memo.invalidate()

        assertEquals("targets-2", memo.read("targets") { "targets-$scans".also { scans += 1 } })
        assertEquals("apps-3", memo.read("apps") { "apps-$scans".also { scans += 1 } })
    }

    /** A cache nobody can invalidate is a stale catalog, so it is never a cache at all (1.5.6). */
    @Test
    fun anUnobservableCatalogIsNeverCached() {
        observable = false

        read()
        read()

        assertEquals(2, scans)
        assertNull(memo.peek<List<String>>("catalog"))
    }

    @Test
    fun peekOffersOnlyWhatWasAlreadyScanned() {
        assertNull(memo.peek<List<String>>("catalog"))

        read()

        assertEquals(listOf("a"), memo.peek<List<String>>("catalog"))
    }

    /** The scan runs outside the lock, so a package event during it must not be papered over. */
    @Test
    fun anEventDuringAScanDiscardsThatScan() {
        memo.read("catalog") {
            memo.invalidate()
            listOf("stale")
        }

        assertEquals(listOf("a"), read())
        assertEquals(1, scans)
    }

    private fun read(): List<String> = memo.read("catalog") {
        scans += 1
        listOf("a")
    }
}
