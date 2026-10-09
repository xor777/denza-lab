package dev.denza.apps.platform.shell

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The stager's commands run by a real `/bin/sh` in a temporary directory: the fake in
 * [ShellProxyStagerTest] is only as good as its reading of them, and this is what reads them for
 * real. Skipped on a host without `sha256sum` and `base64`, which the car's toybox has.
 */
class ShellProxyStagerShellTest {
    private lateinit var directory: File
    private val jar = Random(20_261_009).nextBytes(12_000)

    @Before
    fun hostHasTheTools() {
        assumeTrue(File("/bin/sh").canExecute())
        assumeTrue(sh("command -v sha256sum >/dev/null && command -v base64 >/dev/null; echo \$?").trim() == "0")
        directory = Files.createTempDirectory("denza-stager").toFile()
    }

    @After
    fun removeTheDirectory() {
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    @Test
    fun aRealShellStagesVerifiesSweepsAndRestages() {
        File(directory, "denza-split-proxy-60.jar").writeText("old build")
        File(directory, "denza-vehicle-signal-${"a".repeat(64)}.jar").writeText("other helper")

        val path = stager().stage(::sh)

        assertArrayEquals(jar, File(path).readBytes())
        assertEquals(
            listOf("denza-split-proxy-${ShellProxyStager.sha256(jar)}.jar", "denza-vehicle-signal-${"a".repeat(64)}.jar"),
            directory.list()!!.sorted(),
        )

        File(path).writeBytes(ByteArray(jar.size))
        assertEquals(path, stager().stage(::sh))
        assertArrayEquals(jar, File(path).readBytes())
    }

    @Test
    fun manyShellsStagingAtOnceLeaveOneVerifiedCopy() {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(6)
        try {
            val calls = List(12) {
                pool.submit(Callable { start.await(); stager().stage(::sh) })
            }
            start.countDown()
            val paths = calls.map { it.get(30, TimeUnit.SECONDS) }.toSet()

            assertEquals(1, paths.size)
            assertArrayEquals(jar, File(paths.single()).readBytes())
            assertEquals(listOf(File(paths.single()).name), directory.list()!!.toList())
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * A part file of the current jar is another caller's write in flight: the sweep must leave it,
     * or that caller's `mv` finds nothing. A part of an earlier jar goes.
     */
    @Test
    fun theSweepSparesAPartFileOfTheCurrentJarOnly() {
        val current = "denza-split-proxy-${ShellProxyStager.sha256(jar)}.jar"
        val inFlight = File(directory, "$current.4f2a9c.part").apply { writeText("half a jar") }
        val stale = File(directory, "denza-split-proxy-${"0".repeat(64)}.jar.77aa.part")
            .apply { writeText("an old half") }

        stager().stage(::sh)

        assertTrue("a write in flight keeps its part file", inFlight.isFile)
        assertFalse("an earlier jar's part goes", stale.exists())
    }

    /**
     * The jar is deleted while the process lives and still holds its path: the command line
     * names the APK in its place, so the next command loads the class instead of failing.
     */
    @Test
    fun aJarRemovedMidLifeLeavesTheNextCommandOnTheApk() {
        val apk = File(directory, "base.apk").apply { writeText("the whole app") }.absolutePath
        val jarPath = stager().stage(::sh)
        val command = "${classpathAssignment(jarPath, apk)} sh -c 'printf %s \"\$CLASSPATH\"'"

        assertEquals(jarPath, sh(command))
        File(jarPath).delete()
        assertEquals(apk, sh(command))
        assertEquals(apk, sh("${classpathAssignment(apk, apk)} sh -c 'printf %s \"\$CLASSPATH\"'"))
    }

    private fun stager() = ShellProxyStager(
        helper = ShellProxyJar.SPLIT_TASK,
        jar = { jar },
        directory = directory.absolutePath,
    )

    /** One command, stdout and stderr merged, the way the ADB transport answers. */
    private fun sh(command: String): String {
        val process = ProcessBuilder("/bin/sh", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(StandardCharsets.UTF_8)
        check(process.waitFor(20, TimeUnit.SECONDS)) { "the shell did not finish: $command" }
        return output
    }
}
