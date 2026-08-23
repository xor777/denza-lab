package dev.denza.apps.feature.split

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.13.3: where `app_process` loads the task proxy from.
 *
 * The classpath used to be the 62 MB application APK, and ART opened and verified all of it for
 * every one-shot removal - 1.36 s each on the car. What is asserted here is the staging contract
 * around the 3.7 KB replacement, and above all its fallback: this is a speed fix, so every way it
 * can fail has to end with the product doing exactly what it did before.
 */
class SplitStagedProxyDexTest {

    private val jar = "a one-class jar".toByteArray()
    private val commands = mutableListOf<String>()
    private var staged: ByteArray? = null

    @Test
    fun theProxyIsStagedOnceAndThenReused() {
        val proxy = proxy()

        val first = proxy.entry(::shell)
        val staging = commands.size
        val second = proxy.entry(::shell)

        assertEquals("/data/local/tmp/denza-split-proxy-17.jar", first)
        assertEquals(first, second)
        assertEquals("a staged proxy is staged once per process", staging, commands.size)
        assertTrue(jar.contentEquals(staged))
    }

    /** An update must not keep running the previous build's proxy, and must sweep it away. */
    @Test
    fun aNewVersionStagesItsOwnCopyAndClearsTheOldOnes() {
        val entry = proxy(version = "18").entry(::shell)

        assertEquals("/data/local/tmp/denza-split-proxy-18.jar", entry)
        assertTrue(
            commands.any { it.contains("rm -f '/data/local/tmp/denza-split-proxy-'*.jar") },
        )
    }

    @Test
    fun anAlreadyStagedProxyIsNotWrittenAgain() {
        staged = jar

        val entry = proxy().entry(::shell)

        assertEquals("/data/local/tmp/denza-split-proxy-17.jar", entry)
        assertTrue("nothing was written", commands.none { it.contains("base64 -d") })
    }

    @Test
    fun aShellThatRefusesTheFileLeavesTheApkAsTheClasspath() {
        val proxy = SplitStagedProxyDex(
            apkPath = SPLIT_APK_PATH,
            versionTag = "17",
            jar = { jar },
        )

        assertEquals(SPLIT_APK_PATH, proxy.entry { error("read-only filesystem") })
    }

    /** A flaky link on one call must not pin the slow classpath for the whole process. */
    @Test
    fun oneRefusalIsNotTheCarsFinalAnswer() {
        val proxy = proxy()

        assertEquals(SPLIT_APK_PATH, proxy.entry { error("adb link dropped") })

        assertEquals("/data/local/tmp/denza-split-proxy-17.jar", proxy.entry(::shell))
    }

    /** A truncated write is worse than none: it would be a classpath that cannot be loaded. */
    @Test
    fun aPartiallyWrittenProxyIsRefused() {
        val proxy = proxy()

        val entry = proxy.entry { command ->
            if (command.startsWith("wc -c ")) "3" else shell(command)
        }

        assertEquals(SPLIT_APK_PATH, entry)
    }

    @Test
    fun anEmptyAssetIsRefusedRatherThanStaged() {
        val proxy = SplitStagedProxyDex(
            apkPath = SPLIT_APK_PATH,
            versionTag = "17",
            jar = { ByteArray(0) },
        )

        assertEquals(SPLIT_APK_PATH, proxy.entry(::shell))
        assertTrue(commands.isEmpty())
    }

    private fun proxy(version: String = "17") = SplitStagedProxyDex(
        apkPath = SPLIT_APK_PATH,
        versionTag = version,
        jar = { jar },
    )

    /** A fake `/data/local/tmp`: it decodes what was written and answers `wc -c` from it. */
    private fun shell(command: String): String {
        commands += command
        if (command.startsWith("wc -c ")) return (staged?.size ?: -1).toString()
        Regex("printf '%s' '([^']*)'").find(command)?.let { match ->
            staged = Base64.getDecoder().decode(match.groupValues[1])
        }
        return ""
    }
}
