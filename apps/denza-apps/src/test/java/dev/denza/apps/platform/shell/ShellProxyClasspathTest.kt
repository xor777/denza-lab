package dev.denza.apps.platform.shell

import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fallback around a staged helper (split 1.13.3, and navigation's proxy): this is a speed fix,
 * so every way staging can fail has to end with the product loading the class from the APK, as it
 * did before the jar existed.
 */
class ShellProxyClasspathTest {
    private val tmp = FakeTmp()
    private val jar = "a one-class jar".toByteArray()
    private val staged = "${ShellProxyStager.DIRECTORY}/denza-split-proxy-${ShellProxyStager.sha256(jar)}.jar"

    @Test
    fun aStagedJarIsKeptForTheProcess() {
        val classpath = classpath()

        assertEquals(staged, classpath.entry(tmp::shell))
        val sent = tmp.commands.size
        assertEquals(staged, classpath.entry(tmp::shell))

        assertEquals("nothing is sent once the jar is known", sent, tmp.commands.size)
    }

    @Test
    fun aShellThatRefusesTheFileLeavesTheApkAsTheClasspath() {
        assertEquals(APK, classpath().entry { error("read-only filesystem") })
    }

    /** A flaky link on one call must not pin the slow classpath for the whole process. */
    @Test
    fun oneRefusalIsNotTheCarsFinalAnswer() {
        val classpath = classpath()

        assertEquals(APK, classpath.entry { error("adb link dropped") })

        assertEquals(staged, classpath.entry(tmp::shell))
    }

    @Test
    fun threeRefusalsAreTheCarsFinalAnswer() {
        val classpath = classpath()
        val refusing = FakeTmp().apply { refuseWrites = true }
        repeat(3) { assertEquals(APK, classpath.entry(refusing::shell)) }

        assertEquals(APK, classpath.entry(tmp::shell))
        assertTrue("no further attempt", tmp.commands.isEmpty())
    }

    /**
     * A dropped link, an authorization still pending, a reconnect window: the question did not
     * reach the car, so it is no answer about the file. Each costs that call the APK, and none
     * counts towards giving up on the jar.
     */
    @Test
    fun transportFailuresAreNotRefusals() {
        val classpath = classpath()
        repeat(5) {
            assertEquals(APK, classpath.entry { throw IOException("Persistent ADB shell is closed") })
        }

        assertEquals(staged, classpath.entry(tmp::shell))
    }

    @Test
    fun aForgottenJarIsCheckedAgainAndStagedAnewIfItIsGone() {
        val classpath = classpath()
        assertEquals(staged, classpath.entry(tmp::shell))
        tmp.files.remove(staged)

        classpath.forget()

        assertEquals(staged, classpath.entry(tmp::shell))
        assertArrayEquals(jar, tmp.files[staged])
    }

    @Test
    fun forgettingDoesNotUndoTheCarsFinalAnswer() {
        val classpath = classpath()
        val refusing = FakeTmp().apply { refuseWrites = true }
        repeat(3) { classpath.entry(refusing::shell) }

        classpath.forget()

        assertEquals(APK, classpath.entry(tmp::shell))
        assertTrue(tmp.commands.isEmpty())
    }

    @Test
    fun aDamagedCopyLeavesTheApk() {
        tmp.damage = { bytes -> bytes.copyOf(3) }

        assertEquals(APK, classpath().entry(tmp::shell))
    }

    @Test
    fun anEmptyAssetIsRefusedRatherThanStaged() {
        val classpath = ShellProxyClasspath(
            ShellProxyStager(ShellProxyJar.SPLIT_TASK, jar = { ByteArray(0) }),
            apkPath = APK,
        )

        assertEquals(APK, classpath.entry(tmp::shell))
        assertTrue(tmp.commands.isEmpty())
    }

    private fun classpath() = ShellProxyClasspath(
        ShellProxyStager(ShellProxyJar.SPLIT_TASK, jar = { jar }),
        apkPath = APK,
    )

    private companion object {
        const val APK = "/data/app/dev.denza.apps/base.apk"
    }
}
