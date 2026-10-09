package dev.denza.apps.feature.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
     * arguments in the count `ClusterProxyMain.main` requires, each quoted on its own.
     */
    @Test
    fun eachVerbStartsTheProxyWithExactlyThisCommand() {
        val navigator = "ru.yandex.yandexnavi"
        val origin = NavigationProjectionOrigin(
            sourceRootTaskId = 23,
            companionTaskId = 41,
            companionRootTaskId = 3,
        )
        val start = "CLASSPATH='$CLASSPATH' app_process /system/bin --nice-name=denza_nav_cmd " +
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
            ).map { words -> NavigationProxyClient.commandLine(CLASSPATH, words) },
        )
    }

    private companion object {
        const val CLASSPATH = "/data/app/~~Zq3x==/dev.denza.apps-AbC==/base.apk"
    }
}
