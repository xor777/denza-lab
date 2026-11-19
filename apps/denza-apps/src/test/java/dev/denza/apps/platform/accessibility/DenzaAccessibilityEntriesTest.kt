package dev.denza.apps.platform.accessibility

import dev.denza.apps.feature.split.SplitNativePickerAccessLeaseStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a repair leaves in `enabled_accessibility_services`, over the shell and in the process alike
 * (2026-11-19: the ADB restore lists the service in-process before the network dialog).
 */
class DenzaAccessibilityEntriesTest {
    @Test
    fun `other applications' entries are kept as they stood and in their order`() {
        val setting = listOf(SYSTEM, SHARED, VOICE, SPLIT, OTHER)

        assertEquals(listOf(SYSTEM, VOICE, OTHER), DenzaAccessibilityEntries.foreign(setting))
        assertEquals(listOf(SYSTEM, VOICE, OTHER, SHARED), DenzaAccessibilityEntries.repaired(setting, split = false))
        assertEquals(
            listOf(SYSTEM, VOICE, OTHER, SHARED, SPLIT),
            DenzaAccessibilityEntries.repaired(setting, split = true),
        )
    }

    @Test
    fun `ours are added when missing and listed once`() {
        assertEquals(listOf(SHARED), DenzaAccessibilityEntries.repaired(emptyList(), split = false))
        assertEquals(listOf(OTHER, SHARED), DenzaAccessibilityEntries.repaired(listOf(OTHER, OTHER), split = false))
        assertEquals(
            listOf(OTHER, SHARED, SPLIT),
            DenzaAccessibilityEntries.repaired(listOf(SHARED, OTHER, SHARED, SPLIT, SPLIT), split = true),
        )
        // The short and retired aliases go; the full name comes back once.
        assertEquals(
            listOf(OTHER, SHARED),
            DenzaAccessibilityEntries.repaired(listOf(SHARED_SHORT, OTHER, RETIRED, SPLIT_SHORT), split = false),
        )
    }

    @Test
    fun `the split's service is listed only while the split is on, after the shared one`() {
        val listedBefore = listOf(SPLIT, OTHER)

        assertEquals(listOf(OTHER, SHARED), DenzaAccessibilityEntries.repaired(listedBefore, split = false))
        assertEquals(listOf(OTHER, SHARED, SPLIT), DenzaAccessibilityEntries.repaired(listedBefore, split = true))
        assertEquals(listOf(OTHER, SHARED), DenzaAccessibilityEntries.sharedOnly(listedBefore))
    }

    @Test
    fun `the setting's text reads as entries the way the shell read it`() {
        assertEquals(listOf("a/.A", "b/.B"), AccessibilityServiceList.parse(" a/.A:b/.B: :a/.A\n"))
        assertEquals(emptyList<String>(), AccessibilityServiceList.parse(null))
        assertEquals(emptyList<String>(), AccessibilityServiceList.parse("null"))
    }

    @Test
    fun `the transaction over a list written in the process ends where the shell's ends`() {
        val list = MemoryList(listOf(SYSTEM, SPLIT, OTHER))
        val lease = Lease()

        val ran = DenzaAccessibilityRepairController(list, lease, pause = {}).repair(ensureSplit = true)

        assertTrue(ran)
        assertEquals(
            listOf(listOf(SYSTEM, OTHER), listOf(SYSTEM, OTHER, SHARED), listOf(SYSTEM, OTHER, SHARED, SPLIT)),
            list.writes,
        )
        assertEquals(listOf(false, true, true), list.switchedOn)
        assertTrue(lease.isOwned())
    }

    @Test
    fun `a transaction no longer needed once it holds the lock writes nothing`() {
        val list = MemoryList(listOf(OTHER))

        val ran = DenzaAccessibilityRepairController(list, Lease(), pause = {})
            .repair(ensureSplit = false, needed = { false })

        assertFalse(ran)
        assertTrue(list.writes.isEmpty())
    }

    private class MemoryList(var entries: List<String>) : AccessibilityServiceList {
        val writes = mutableListOf<List<String>>()
        val switchedOn = mutableListOf<Boolean>()
        override fun read(): List<String> = entries
        override fun write(entries: List<String>, ensureAccessibilityEnabled: Boolean) {
            this.entries = entries.distinct()
            writes += this.entries
            switchedOn += ensureAccessibilityEnabled
        }
    }

    private class Lease : SplitNativePickerAccessLeaseStore {
        private var owned = false
        private var version = 0
        override fun isOwned(): Boolean = owned
        override fun setOwned(owned: Boolean): Boolean { this.owned = owned; return true }
        override fun configurationVersion(): Int = version
        override fun setConfigurationVersion(version: Int): Boolean { this.version = version; return true }
    }

    private companion object {
        const val SHARED = "dev.denza.apps/dev.denza.apps.SimulcastAccessibilityService"
        const val SHARED_SHORT = "dev.denza.apps/.SimulcastAccessibilityService"
        const val RETIRED = "dev.denza.apps/.feature.mirrors.MirrorGuardAccessibilityService"
        const val SPLIT = "dev.denza.apps/dev.denza.apps.feature.split.SplitNativePickerAccessibilityService"
        const val SPLIT_SHORT = "dev.denza.apps/.feature.split.SplitNativePickerAccessibilityService"
        const val SYSTEM = "com.android.systemui/.custom.StatusBarAccessibilityService"
        const val VOICE = "com.byd.autovoice/.SceneSayService"
        const val OTHER = "com.other/.Observer"
    }
}
