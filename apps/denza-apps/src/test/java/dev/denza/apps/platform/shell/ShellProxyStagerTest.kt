package dev.denza.apps.platform.shell

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellProxyStagerTest {
    private val tmp = FakeTmp()
    private val jar = "a one-class jar".toByteArray()

    @Test
    fun theJarIsStagedUnderItsHashAndThenOnlyChecked() {
        val stager = stager(jar)

        val first = stager.stage(tmp::shell)
        val staging = tmp.commands.size
        val second = stager.stage(tmp::shell)

        assertEquals(pathOf(ShellProxyJar.SPLIT_TASK, jar), first)
        assertEquals(first, second)
        assertArrayEquals(jar, tmp.files[first])
        assertEquals("a check, a write, a verification", 3, staging)
        assertEquals("a staged jar costs one round trip", staging + 1, tmp.commands.size)
    }

    /**
     * The defect this replaced: the split named its copy by versionCode and checked it by size, and
     * the owner installs builds without raising the version.
     */
    @Test
    fun aChangedJarOfTheSameLengthIsStagedAgainAndTheOldOneGoes() {
        val before = "same-size-one".toByteArray()
        val after = "same-size-two".toByteArray()

        val old = stager(before).stage(tmp::shell)
        val new = stager(after).stage(tmp::shell)

        assertNotEquals(old, new)
        assertArrayEquals(after, tmp.files[new])
        assertEquals(listOf(new), tmp.named("$DIR/denza-split-proxy-"))
    }

    @Test
    fun aCopyWhoseHashDoesNotMatchIsWrittenAgain() {
        val path = pathOf(ShellProxyJar.SPLIT_TASK, jar)
        tmp.files[path] = ByteArray(jar.size) { 7 }

        assertEquals(path, stager(jar).stage(tmp::shell))
        assertArrayEquals(jar, tmp.files[path])
    }

    /**
     * Earlier builds' copies under any name, and a part file an old write left, go; other helpers'
     * copies, files that are not ours and a part file of the current jar - another caller may be
     * writing it right now - stay.
     */
    @Test
    fun staleCopiesOfTheSameHelperAreRemovedAndNothingElse() {
        val current = pathOf(ShellProxyJar.SPLIT_TASK, jar)
        val stale = listOf(
            "$DIR/denza-split-proxy-60.jar",
            "$DIR/denza-split-proxy-${"0".repeat(64)}.jar",
            "$DIR/denza-split-proxy-${"0".repeat(64)}.jar.5e1f.part",
        )
        val kept = listOf(
            pathOf(ShellProxyJar.VEHICLE_SIGNAL, "listener".toByteArray()),
            "$current.9c2a.part",
            "$DIR/denza-media-focus-${"1".repeat(64)}.jar",
            "$DIR/split-task-proxy.jar",
        )
        (stale + kept).forEach { name -> tmp.files[name] = byteArrayOf(1) }

        stager(jar).stage(tmp::shell)

        stale.forEach { name -> assertFalse(name, name in tmp.files) }
        kept.forEach { name -> assertTrue(name, name in tmp.files) }
        assertArrayEquals(jar, tmp.files[current])
    }

    /** Copies left by a build before this one are swept even when the current jar is in place. */
    @Test
    fun theSweepRunsWhenNothingHasToBeWritten() {
        val current = stager(jar).stage(tmp::shell)
        tmp.files["$DIR/denza-split-proxy-59.jar"] = byteArrayOf(1)

        stager(jar).stage(tmp::shell)

        assertEquals(listOf(current), tmp.named("$DIR/denza-split-proxy-"))
    }

    @Test
    fun noHelperNameStartsAnother() {
        ShellProxyJar.entries.forEach { one ->
            ShellProxyJar.entries.filter { it != one }.forEach { other ->
                assertFalse(
                    "${other.fileName} would be swept with ${one.fileName}",
                    other.fileName.startsWith(one.fileName + "-"),
                )
            }
        }
        assertEquals(
            ShellProxyJar.entries.size,
            ShellProxyJar.entries.map(ShellProxyJar::asset).toSet().size,
        )
    }

    // region failing closed

    @Test
    fun anEmptyAssetIsRefusedBeforeAnythingIsSent() {
        assertThrows(IllegalStateException::class.java) {
            stager(ByteArray(0)).stage(tmp::shell)
        }
        assertTrue(tmp.commands.isEmpty())
    }

    @Test
    fun aFilesystemThatWillNotTakeTheJarFailsTheStage() {
        tmp.refuseWrites = true

        assertThrows(IllegalStateException::class.java) { stager(jar).stage(tmp::shell) }
    }

    /** A truncated write is worse than none: it would be a classpath that cannot be loaded. */
    @Test
    fun aDamagedWriteFailsVerification() {
        tmp.damage = { bytes -> bytes.copyOf(bytes.size - 1) }

        assertThrows(ShellProxyRefused::class.java) { stager(jar).stage(tmp::shell) }
    }

    /** A part whose hash is not the jar's is never moved under the jar's name: names never lie. */
    @Test
    fun aDamagedPieceIsNeverPutInPlace() {
        val large = Random(7).nextBytes(3 * ShellProxyStager.CHUNK_BYTES + 11)
        var pieces = 0
        tmp.damage = { bytes -> if (++pieces == 2) bytes.copyOf(bytes.size - 3) else bytes }

        assertThrows(ShellProxyRefused::class.java) { stager(large).stage(tmp::shell) }

        assertFalse(pathOf(ShellProxyJar.SPLIT_TASK, large) in tmp.files)
        assertTrue("the part was removed", tmp.files.keys.none { it.endsWith(".part") })
    }

    @Test
    fun aShellThatAnswersNothingFailsTheStage() {
        assertThrows(IllegalStateException::class.java) { stager(jar).stage { "" } }
    }

    // endregion

    // region the line the car's shell has proven

    @Test
    fun noStagingCommandIsLongerThanTheProvenLine() {
        listOf(1, 4_607, 4_608, 4_609, 7_546, 9_574, 30_000).forEach { size ->
            val bytes = Random(size).nextBytes(size)
            val tmp = FakeTmp()

            val path = stager(bytes).stage(tmp::shell)

            assertArrayEquals("$size bytes reassembled", bytes, tmp.files[path])
            val longest = tmp.commands.maxOf(String::length)
            assertTrue("$size bytes: a line of $longest", longest <= ShellProxyStager.MAX_COMMAND_LINE)
        }
    }

    @Test
    fun theLimitStaysUnderTheLongestLineProvenOnTheCar() {
        // The 7,449-byte listener staged whole on 2026-09-04: about 10.2 KB with its frame.
        assertTrue(ShellProxyStager.MAX_COMMAND_LINE + FRAME_ALLOWANCE < 10_200)
    }

    // endregion

    // region two at once

    /** Another process writes the same jar between this one's part file and its rename. */
    @Test
    fun twoCallersStagingTheSameJarAtOnceBothGetAVerifiedCopy() {
        val other = stager(jar)
        var otherPath: String? = null
        tmp.beforeRename = { _ ->
            tmp.beforeRename = {}
            otherPath = other.stage(tmp::shell)
        }

        val path = stager(jar).stage(tmp::shell)

        assertEquals(path, otherPath)
        assertArrayEquals(jar, tmp.files[path])
        assertEquals(emptyList<String>(), tmp.errors)
        assertEquals(listOf(path), tmp.named("$DIR/denza-split-proxy-"))
    }

    /** The split and the turn-signal listener stage at the same moment; neither sweeps the other. */
    @Test
    fun twoHelpersStagingAtOnceKeepBothCopies() {
        val listener = "listener".toByteArray()
        var listenerPath: String? = null
        tmp.beforeRename = { _ ->
            tmp.beforeRename = {}
            listenerPath = stager(listener, ShellProxyJar.VEHICLE_SIGNAL).stage(tmp::shell)
        }

        val splitPath = stager(jar).stage(tmp::shell)

        assertArrayEquals(jar, tmp.files[splitPath])
        assertArrayEquals(listener, tmp.files[listenerPath])
        assertEquals(emptyList<String>(), tmp.errors)
    }

    @Test
    fun manyCallersOnManyThreadsAllGetTheSameVerifiedCopy() {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val calls = List(16) { index ->
                val helper = if (index % 4 == 0) ShellProxyJar.VEHICLE_SIGNAL else ShellProxyJar.SPLIT_TASK
                val bytes = if (helper == ShellProxyJar.SPLIT_TASK) jar else "listener".toByteArray()
                pool.submit(Callable { start.await(); stager(bytes, helper).stage(tmp::shell) })
            }
            start.countDown()
            val paths = calls.map { it.get(10, TimeUnit.SECONDS) }.toSet()

            assertEquals(2, paths.size)
            assertArrayEquals(jar, tmp.files[pathOf(ShellProxyJar.SPLIT_TASK, jar)])
            assertEquals(emptyList<String>(), tmp.errors)
            assertTrue(tmp.files.keys.none { it.endsWith(".part") })
        } finally {
            pool.shutdownNow()
        }
    }

    // endregion

    private fun stager(bytes: ByteArray, helper: ShellProxyJar = ShellProxyJar.SPLIT_TASK) =
        ShellProxyStager(helper = helper, jar = { bytes })

    private fun pathOf(helper: ShellProxyJar, bytes: ByteArray): String =
        "$DIR/${helper.fileName}-${ShellProxyStager.sha256(bytes)}.jar"

    private companion object {
        const val DIR = ShellProxyStager.DIRECTORY

        /** What the transport wraps a command in: its emitter prelude, markers and `eval` quoting. */
        const val FRAME_ALLOWANCE = 512
    }
}
