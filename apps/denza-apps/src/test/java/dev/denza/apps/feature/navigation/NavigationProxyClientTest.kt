package dev.denza.apps.feature.navigation

import dev.denza.apps.platform.shell.FakeTmp
import dev.denza.apps.platform.shell.ShellProxyJar
import dev.denza.apps.platform.shell.ShellProxyStager
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class NavigationProxyClientTest {
    @Test
    fun extractsLastMarkedCommandResult() {
        assertEquals(
            "37",
            NavigationProxyClient.resultValue(
                "runtime prelude\nDENZA_RESULT:12\nDENZA_RESULT:37\n",
            ),
        )
    }

    @Test
    fun rejectsOutputWithoutMarkedResult() {
        assertThrows(IllegalStateException::class.java) {
            NavigationProxyClient.resultValue("permission denied")
        }
    }

    @Test
    fun parsesStandaloneProjectionOriginWithoutNegativeShellArguments() {
        assertEquals(
            NavigationProjectionOrigin(
                sourceRootTaskId = 23,
                companionTaskId = 0,
                companionRootTaskId = 0,
            ),
            NavigationProxyClient.projectionOriginValue("DENZA_RESULT:23,0,0"),
        )
    }

    /**
     * Every verb, word for word, as the one-shot proxy is started for it: the operation and its
     * arguments in the count `ClusterProxyMain.main` requires, each quoted on its own. The words
     * were pinned against the APK classpath before the proxy got a jar of its own: from the APK the
     * line is that one letter for letter, and from the staged jar only the classpath part differs,
     * naming the APK behind the jar.
     */
    @Test
    fun eachVerbStartsTheProxyWithExactlyThisCommand() {
        assertVerbs(APK, "CLASSPATH='$APK'")
        assertVerbs(STAGED_JAR, "c='$STAGED_JAR'; [ -r \"\$c\" ] || c='$APK'; CLASSPATH=\"\$c\"")
    }

    // region where the proxy loads from

    /** The jar comes from the stager, under navigation's own asset, and the command names it. */
    @Test
    fun navigationLoadsTheProxyFromTheJarItStaged() {
        val tmp = FakeTmp()
        val requested = mutableListOf<String>()
        val proxy = NavigationProxyClient.stagedProxy(
            asset = { name -> requested += name; JAR_BYTES },
            apkPath = APK,
            log = {},
        )
        val car = ProxyCar(tmp)

        val output = NavigationProxyClient.runProxy(
            proxy,
            car::shell,
            NavigationProxyClient.findTaskWords("ru.yandex.yandexnavi"),
        )

        assertEquals(listOf(ShellProxyJar.NAVIGATION.asset), requested)
        assertEquals("57", NavigationProxyClient.resultValue(output))
        assertEquals(listOf(stagedPath()), car.loadedFrom)
    }

    /** A reply that carries no result drops the kept jar: the next command asks the car again. */
    @Test
    fun aReplyWithoutAResultMakesTheNextCommandCheckTheJarAgain() {
        val tmp = FakeTmp()
        val proxy = NavigationProxyClient.stagedProxy({ JAR_BYTES }, APK, log = {})
        val car = ProxyCar(tmp)
        NavigationProxyClient.runProxy(proxy, car::shell, FIND)
        car.reply = { "Killed" }

        NavigationProxyClient.runProxy(proxy, car::shell, FIND)
        car.reply = { "DENZA_RESULT:57" }
        val sweeps = tmp.commands.count { it.startsWith("for f in ") }
        NavigationProxyClient.runProxy(proxy, car::shell, FIND)

        assertEquals("one check of the jar after the reply without a result", sweeps + 1,
            tmp.commands.count { it.startsWith("for f in ") })
        assertEquals("only the one failed command was sent for it", 3, car.loadedFrom.size)
    }

    /**
     * The class did not load at all: nothing of the proxy ran, so the command goes once more, from
     * a jar the car has just been asked about again - here put back after someone damaged it.
     */
    @Test
    fun aProxyThatDidNotLoadIsTriedOnceMore() {
        val tmp = FakeTmp()
        val proxy = NavigationProxyClient.stagedProxy({ JAR_BYTES }, APK, log = {})
        val car = ProxyCar(tmp)
        NavigationProxyClient.runProxy(proxy, car::shell, FIND)
        tmp.files[stagedPath()] = ByteArray(JAR_BYTES.size)

        val output = NavigationProxyClient.runProxy(proxy, car::shell, FIND)

        assertEquals("57", NavigationProxyClient.resultValue(output))
        assertEquals(3, car.loadedFrom.size)
        assertTrue(JAR_BYTES.contentEquals(tmp.files[stagedPath()]))
    }

    /** Any other reply without a result is the proxy's own failure: it is not sent again. */
    @Test
    fun aProxyThatFailedOnItsOwnIsNotSentAgain() {
        val tmp = FakeTmp()
        val proxy = NavigationProxyClient.stagedProxy({ JAR_BYTES }, APK, log = {})
        val car = ProxyCar(tmp).apply {
            reply = { "java.lang.IllegalStateException: task 57 is not projectable" }
        }

        NavigationProxyClient.runProxy(
            proxy,
            car::shell,
            NavigationProxyClient.projectTaskWords("ru.yandex.yandexnavi", 57, 61, 12, 2560, 720),
        )

        assertEquals(1, car.loadedFrom.size)
    }

    /**
     * The whole path through a real `/bin/sh`: the jar is staged into a directory, an `app_process`
     * stand-in on the PATH says what it was loaded from, the jar is deleted while the process keeps
     * its path - and the next command still answers, from the APK.
     */
    @Test
    fun aJarDeletedMidLifeLeavesTheNextCommandAnsweringFromTheApk() {
        assumeTrue(File("/bin/sh").canExecute())
        assumeTrue(sh("command -v sha256sum >/dev/null && command -v base64 >/dev/null; echo \$?", null).trim() == "0")
        val root = Files.createTempDirectory("denza-nav").toFile()
        try {
            val bin = File(root, "bin").apply { mkdirs() }
            File(bin, "app_process").apply {
                writeText(
                    "#!/bin/sh\n" +
                        "[ -r \"\$CLASSPATH\" ] || { echo \"java.lang.ClassNotFoundException: \$CLASSPATH\"; exit 1; }\n" +
                        "echo \"DENZA_RESULT:\$CLASSPATH\"\n",
                )
                setExecutable(true)
            }
            val apk = File(root, "base.apk").apply { writeText("the whole app") }.absolutePath
            val proxy = NavigationProxyClient.stagedProxy(
                asset = { JAR_BYTES },
                apkPath = apk,
                log = {},
                directory = File(root, "tmp").apply { mkdirs() }.absolutePath,
            )
            val shell = { command: String -> sh(command, bin) }

            val jar = NavigationProxyClient.resultValue(NavigationProxyClient.runProxy(proxy, shell, FIND))
            assertTrue(jar, jar.endsWith(".jar"))
            File(jar).delete()

            assertEquals(apk, NavigationProxyClient.resultValue(NavigationProxyClient.runProxy(proxy, shell, FIND)))
        } finally {
            root.deleteRecursively()
        }
    }

    // endregion

    private fun assertVerbs(classpath: String, assignment: String) {
        val navigator = "ru.yandex.yandexnavi"
        val origin = NavigationProjectionOrigin(
            sourceRootTaskId = 23,
            companionTaskId = 41,
            companionRootTaskId = 3,
        )
        val start = "$assignment app_process /system/bin --nice-name=denza_nav_cmd " +
            "dev.denza.apps.feature.navigation.ClusterProxyMain"

        assertEquals(
            listOf(
                "$start 'find-task' 'ru.yandex.yandexnavi'",
                "$start 'project-task' 'ru.yandex.yandexnavi' '57' '61' '12' '2560' '720'",
                "$start 'return-task' 'ru.yandex.yandexnavi' '57' '23' '41' '3'",
                "$start 'restore-task' 'ru.yandex.yandexnavi' '57' '23' '41' '3'",
                "$start 'projection-origin' 'ru.yandex.yandexnavi' '57'",
                "$start 'create-root' '12'",
                "$start 'task-display' 'ru.yandex.yandexnavi' '57'",
            ),
            listOf(
                NavigationProxyClient.findTaskWords(navigator),
                NavigationProxyClient.projectTaskWords(navigator, 57, 61, 12, 2560, 720),
                NavigationProxyClient.returnTaskWords(navigator, 57, origin, focusNavigation = true),
                NavigationProxyClient.returnTaskWords(navigator, 57, origin, focusNavigation = false),
                NavigationProxyClient.projectionOriginWords(navigator, 57),
                NavigationProxyClient.createRootWords(12),
                NavigationProxyClient.taskDisplayWords(navigator, 57),
            ).map { words -> NavigationProxyClient.commandLine(classpath, APK, words) },
        )
    }

    private fun stagedPath(): String =
        "${ShellProxyStager.DIRECTORY}/denza-nav-proxy-${ShellProxyStager.sha256(JAR_BYTES)}.jar"

    /**
     * The car as the proxy sees it: staging commands go to [tmp], and an `app_process` line loads
     * the class from the jar if [tmp] holds it intact, from the APK if the line falls back to it.
     */
    private class ProxyCar(private val tmp: FakeTmp) {
        val loadedFrom = mutableListOf<String>()
        var reply: (String) -> String = { "DENZA_RESULT:57" }

        fun shell(command: String): String {
            if (" app_process " !in command) return tmp.shell(command)
            val jar = JAR_IN_LINE.find(command)?.groupValues?.get(1)
            val classpath = when {
                jar == null -> APK
                jar in tmp.files -> jar
                else -> APK
            }
            loadedFrom += classpath
            if (classpath != APK && !JAR_BYTES.contentEquals(tmp.files[classpath])) {
                return "java.lang.ClassNotFoundException: " +
                    "dev.denza.apps.feature.navigation.ClusterProxyMain"
            }
            return reply(command)
        }
    }

    private fun sh(command: String, bin: File?): String {
        val builder = ProcessBuilder("/bin/sh", "-c", command).redirectErrorStream(true)
        if (bin != null) {
            builder.environment()["PATH"] = bin.absolutePath + File.pathSeparator +
                builder.environment()["PATH"]
        }
        val process = builder.start()
        val output = process.inputStream.readBytes().toString(StandardCharsets.UTF_8)
        check(process.waitFor(20, TimeUnit.SECONDS)) { "the shell did not finish: $command" }
        return output
    }

    private companion object {
        val STAGED_JAR = "/data/local/tmp/denza-nav-proxy-${"5e".repeat(32)}.jar"
        const val APK = "/data/app/~~Zq3x==/dev.denza.apps-AbC==/base.apk"
        val JAR_BYTES = "a cluster proxy jar".toByteArray()
        val FIND = NavigationProxyClient.findTaskWords("ru.yandex.yandexnavi")
        val JAR_IN_LINE = Regex("""^c='([^']*)'; \[ -r""")
    }
}
