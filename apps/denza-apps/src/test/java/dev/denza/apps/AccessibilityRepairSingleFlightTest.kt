package dev.denza.apps

import dev.denza.apps.feature.split.SplitNativePickerAccessLeaseStore
import dev.denza.apps.feature.split.SplitNativePickerAccessibilityAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class AccessibilityRepairSingleFlightTest {
    @Test
    fun sharedRepairRestoresAppObserverThenSplitObserverLast() {
        val system = "com.android.systemui/.custom.StatusBarAccessibilityService"
        val voice = "com.byd.autovoice/.SceneSayService"
        val simulcast = SimulcastAccessibilityAccess.COMPONENT
        val split = SplitNativePickerAccessibilityAccess.COMPONENT
        val shell = FakeAccessibilitySettings(listOf(system, voice, split, simulcast))
        val lease = FakeSplitAccessLease(owned = true, configurationVersion = 5)
        val pauses = mutableListOf<Long>()

        DenzaAccessibilityRepairController(
            shell = shell::run,
            splitLeaseStore = lease,
            pause = pauses::add,
        ).repair(ensureSplit = true)

        assertEquals(
            listOf(
                listOf(system, voice),
                listOf(system, voice, simulcast),
                listOf(system, voice, simulcast, split),
            ),
            shell.writes,
        )
        assertEquals(listOf(false, true, true), shell.accessibilityEnableWrites)
        assertEquals(listOf(1_000L, 2_000L, 1_000L), pauses)
        assertTrue(lease.isOwned())
        assertEquals(6, lease.configurationVersion())
    }

    @Test
    fun sharedRepairDoesNotRestoreSplitWhenFeatureIsDisabled() {
        val system = "com.android.systemui/.custom.StatusBarAccessibilityService"
        val simulcast = SimulcastAccessibilityAccess.COMPONENT
        val split = SplitNativePickerAccessibilityAccess.COMPONENT
        val shell = FakeAccessibilitySettings(listOf(system, split, simulcast))
        val lease = FakeSplitAccessLease(owned = true, configurationVersion = 6)
        val pauses = mutableListOf<Long>()

        DenzaAccessibilityRepairController(
            shell = shell::run,
            splitLeaseStore = lease,
            pause = pauses::add,
        ).repair(ensureSplit = false)

        assertEquals(listOf(listOf(system), listOf(system, simulcast)), shell.writes)
        assertEquals(listOf(false, true), shell.accessibilityEnableWrites)
        assertEquals(listOf(1_000L), pauses)
        assertFalse(lease.isOwned())
    }

    @Test
    fun sharedRepairPreservesServiceAddedBySystemDuringUnbind() {
        val system = "com.android.systemui/.custom.StatusBarAccessibilityService"
        val voice = "com.byd.autovoice/.SceneSayService"
        lateinit var shell: FakeAccessibilitySettings
        shell = FakeAccessibilitySettings(listOf(system)) {
            if (shell.writes.size == 1) shell.services = shell.services + voice
        }

        DenzaAccessibilityRepairController(
            shell = shell::run,
            splitLeaseStore = FakeSplitAccessLease(
                owned = false,
                configurationVersion = 0,
            ),
            pause = {},
        ).repair(ensureSplit = false)

        assertEquals(
            listOf(system, voice, SimulcastAccessibilityAccess.COMPONENT),
            shell.services,
        )
    }

    @Test
    fun settingsMutationsFromDifferentOwnersNeverOverlap() {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val first = thread(start = true) {
            AccessibilitySettingsMutationLock.withLock {
                firstEntered.countDown()
                releaseFirst.await(2, TimeUnit.SECONDS)
            }
        }
        assertTrue(firstEntered.await(1, TimeUnit.SECONDS))

        val second = thread(start = true) {
            AccessibilitySettingsMutationLock.withLock {
                secondEntered.countDown()
            }
        }
        try {
            assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseFirst.countDown()
        }
        assertTrue(secondEntered.await(1, TimeUnit.SECONDS))
        first.join(1_000L)
        second.join(1_000L)
        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
    }

    @Test
    fun concurrentOwnersJoinOneRepairAndReceiveItsResult() {
        val repair = AccessibilityRepairSingleFlight()
        val received = mutableListOf<Throwable?>()
        val failure = IllegalStateException("repair failed")

        assertTrue(repair.join { received += it })
        assertFalse(repair.join { received += it })
        assertTrue(repair.isRunning())

        repair.complete(failure)

        assertFalse(repair.isRunning())
        assertEquals(2, received.size)
        assertSame(failure, received[0])
        assertSame(failure, received[1])
        assertTrue(repair.join { received += it })
    }

    @Test
    fun failingOwnerCallbackDoesNotHideCompletionFromOtherOwners() {
        val repair = AccessibilityRepairSingleFlight()
        var completed = false

        repair.join { error("callback failed") }
        repair.join { completed = true }
        repair.complete(null)

        assertTrue(completed)
        assertFalse(repair.isRunning())
    }

    private class FakeAccessibilitySettings(
        initial: List<String>,
        private val onWrite: () -> Unit = {},
    ) {
        var services = initial
        val writes = mutableListOf<List<String>>()
        val accessibilityEnableWrites = mutableListOf<Boolean>()

        fun run(command: String): String = when {
            command == "settings get secure enabled_accessibility_services" ->
                services.joinToString(":")
            command.startsWith("settings put secure enabled_accessibility_services '") -> {
                services = command
                    .substringAfter("settings put secure enabled_accessibility_services '")
                    .substringBefore("'")
                    .split(':')
                    .filter(String::isNotBlank)
                writes += services
                accessibilityEnableWrites += command.contains(
                    "settings put secure accessibility_enabled 1",
                )
                onWrite()
                ""
            }
            else -> error("Unexpected command: $command")
        }
    }

    private class FakeSplitAccessLease(
        private var owned: Boolean,
        private var configurationVersion: Int,
    ) : SplitNativePickerAccessLeaseStore {
        override fun isOwned(): Boolean = owned

        override fun setOwned(owned: Boolean): Boolean {
            this.owned = owned
            return true
        }

        override fun configurationVersion(): Int = configurationVersion

        override fun setConfigurationVersion(version: Int): Boolean {
            configurationVersion = version
            return true
        }
    }
}
