package dev.denza.apps.feature.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitShellRouterTest {
    @Test
    fun leavesFullscreenLaunchesUntouched() {
        val commands = mutableListOf<String>()
        val router = SplitShellRouter { command ->
            commands += command
            if (command == "am stack list") fullscreenSnapshot else ""
        }

        assertFalse(router.tick())
        assertEquals(listOf("am stack list"), commands)
    }

    @Test
    fun prepositionsExistingTasksWhenStockSplitIsVisible() {
        val commands = mutableListOf<String>()
        val router = SplitShellRouter { command ->
            commands += command
            if (command == "am stack list") splitSnapshot else ""
        }

        assertTrue(router.tick())
        assertEquals(
            listOf(
                "am stack list",
                "am stack move-task 37 3 false",
                "am task resize 37 24 112 1680 1472",
                "am stack move-task 47 2 false",
                "am task resize 47 1704 112 2536 1472",
            ),
            commands,
        )
    }

    @Test
    fun keepsAnActivelyLaunchedTaskOnTopWhenMovingItToItsPane() {
        val commands = mutableListOf<String>()
        val router = SplitShellRouter { command ->
            commands += command
            if (command == "am stack list") activeMusicInLeftPane else ""
        }

        assertTrue(router.tick())
        assertEquals(
            listOf(
                "am stack list",
                "am stack move-task 47 2 true",
                "am task resize 47 1704 112 2536 1472",
            ),
            commands,
        )
    }

    @Test
    fun routesAFullscreenTargetOnlyWhenItWasLaunchedFromVisibleStockSplit() {
        val commands = mutableListOf<String>()
        var snapshot = splitSnapshot
        val router = SplitShellRouter { command ->
            commands += command
            if (command == "am stack list") snapshot else ""
        }

        assertTrue(router.tick())
        commands.clear()
        snapshot = fullscreenNavigatorLaunch

        assertTrue(router.tick())
        assertEquals(
            listOf(
                "am stack list",
                "am stack move-task 37 3 true",
                "am task resize 37 24 112 1680 1472",
            ),
            commands,
        )
    }

    private companion object {
        val fullscreenSnapshot = """
            RootTask id=4 bounds=[0,0][2560,1600] displayId=0 userId=0
              taskId=58: dev.denza.apps/dev.denza.apps.MainActivity bounds=[0,0][2560,1600] userId=0 visible=true topActivity=ComponentInfo{dev.denza.apps/dev.denza.apps.MainActivity}
              taskId=37: ru.yandex.yandexnavi/ru.yandex.yandexnavi.core.NavigatorActivity bounds=[0,0][2560,1600] userId=0 visible=false topActivity=ComponentInfo{ru.yandex.yandexnavi/ru.yandex.yandexnavi.core.NavigatorActivity}

            RootTask id=3 bounds=[24,112][1680,1472] displayId=0 userId=0
              taskId=24: com.android.launcher3/com.android.launcher3.Launcher bounds=[24,112][1680,1472] userId=0 visible=false topActivity=ComponentInfo{com.android.launcher3/com.android.launcher3.Launcher}

            RootTask id=2 bounds=[1704,112][2536,1472] displayId=0 userId=0
              taskId=20: com.byd.launchermap/com.byd.automap.activity.MainActivity bounds=[1704,112][2536,1472] userId=0 visible=false topActivity=ComponentInfo{com.byd.launchermap/com.byd.automap.activity.MainActivity}
        """.trimIndent()

        val splitSnapshot = """
            RootTask id=3 bounds=[24,112][1680,1472] displayId=0 userId=0
              taskId=24: com.android.launcher3/com.android.launcher3.Launcher bounds=[24,112][1680,1472] userId=0 visible=true topActivity=ComponentInfo{com.byd.auto_photo/com.byd.auto_photo.MainActivity}
              taskId=42: com.byd.auto_photo/com.byd.auto_photo.MainActivity bounds=[24,112][1680,1472] userId=0 visible=true topActivity=ComponentInfo{com.byd.auto_photo/com.byd.auto_photo.MainActivity}

            RootTask id=2 bounds=[1704,112][2536,1472] displayId=0 userId=0
              taskId=20: com.byd.launchermap/com.byd.automap.activity.MainActivity bounds=[1704,112][2536,1472] userId=0 visible=true topActivity=ComponentInfo{com.byd.launchermap/com.byd.automap.activity.MainActivity}

            RootTask id=4 bounds=[0,0][2560,1600] displayId=0 userId=0
              taskId=58: dev.denza.apps/dev.denza.apps.MainActivity bounds=[0,0][2560,1600] userId=0 visible=false topActivity=ComponentInfo{dev.denza.apps/dev.denza.apps.MainActivity}
              taskId=37: ru.yandex.yandexnavi/ru.yandex.yandexnavi.core.NavigatorActivity bounds=[0,0][2560,1600] userId=0 visible=false topActivity=ComponentInfo{ru.yandex.yandexnavi/ru.yandex.yandexnavi.core.NavigatorActivity}

            RootTask id=47 bounds=[0,0][2560,1600] displayId=0 userId=0
              taskId=47: ru.yandex.music/ru.yandex.music.main.MainScreenActivity bounds=[0,0][2560,1600] userId=0 visible=false topActivity=ComponentInfo{ru.yandex.music/ru.yandex.music.main.MainScreenActivity}
        """.trimIndent()

        val activeMusicInLeftPane = """
            RootTask id=3 bounds=[24,112][1680,1472] displayId=0 userId=0
              taskId=24: com.android.launcher3/com.android.launcher3.Launcher bounds=[24,112][1680,1472] userId=0 visible=true topActivity=ComponentInfo{ru.yandex.music/ru.yandex.music.main.MainScreenActivity}
              taskId=47: ru.yandex.music/ru.yandex.music.main.MainScreenActivity bounds=[24,112][1680,1472] userId=0 visible=true topActivity=ComponentInfo{ru.yandex.music/ru.yandex.music.main.MainScreenActivity}

            RootTask id=2 bounds=[1704,112][2536,1472] displayId=0 userId=0
              taskId=20: com.byd.launchermap/com.byd.automap.activity.MainActivity bounds=[1704,112][2536,1472] userId=0 visible=true topActivity=ComponentInfo{com.byd.launchermap/com.byd.automap.activity.MainActivity}
        """.trimIndent()

        val fullscreenNavigatorLaunch = """
            RootTask id=4 bounds=[0,0][2560,1600] displayId=0 userId=0
              taskId=37: ru.yandex.yandexnavi/ru.yandex.yandexnavi.core.NavigatorActivity bounds=[0,0][2560,1600] userId=0 visible=true topActivity=ComponentInfo{ru.yandex.yandexnavi/ru.yandex.yandexnavi.core.NavigatorActivity}

            RootTask id=3 bounds=[24,112][1680,1472] displayId=0 userId=0
              taskId=24: com.android.launcher3/com.android.launcher3.Launcher bounds=[24,112][1680,1472] userId=0 visible=false topActivity=ComponentInfo{com.android.launcher3/com.android.launcher3.Launcher}

            RootTask id=2 bounds=[1704,112][2536,1472] displayId=0 userId=0
              taskId=20: com.byd.launchermap/com.byd.automap.activity.MainActivity bounds=[1704,112][2536,1472] userId=0 visible=false topActivity=ComponentInfo{com.byd.launchermap/com.byd.automap.activity.MainActivity}
        """.trimIndent()
    }
}
