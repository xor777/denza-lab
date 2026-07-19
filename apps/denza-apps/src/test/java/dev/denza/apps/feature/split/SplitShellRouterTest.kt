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
            if (command == "am stack list") fullscreenDenzaApps else ""
        }

        assertFalse(router.tick())
        assertEquals(listOf("am stack list"), commands)
    }

    @Test
    fun doesNotPrepositionHardcodedAppsWhenEmptyStockSplitAppears() {
        val commands = mutableListOf<String>()
        val router = SplitShellRouter { command ->
            commands += command
            if (command == "am stack list") emptySplitWithStoppedApps else ""
        }

        assertTrue(router.tick())
        assertEquals(listOf("am stack list"), commands)
    }

    @Test
    fun routesFirstSelectedAppToTheFreePane() {
        val commands = mutableListOf<String>()
        var snapshot = emptySplitWithStoppedApps
        val router = SplitShellRouter { command ->
            commands += command
            if (command == "am stack list") snapshot else ""
        }

        assertTrue(router.tick())
        commands.clear()
        snapshot = fullscreenVlcLaunch

        assertTrue(router.tick())
        assertEquals(
            listOf(
                "am stack list",
                "am stack move-task 70 2 true",
                "am task resize 70 1704 112 2536 1472",
            ),
            commands,
        )
    }

    @Test
    fun explicitExternalMoveCancelsPendingStockPickerSelection() {
        val commands = mutableListOf<String>()
        var snapshot = emptySplitWithStoppedApps
        val router = SplitShellRouter { command ->
            commands += command
            if (command == "am stack list") snapshot else ""
        }

        assertTrue(router.tick())
        router.cancelPendingSelection()
        commands.clear()
        snapshot = fullscreenVlcLaunch

        assertFalse(router.tick())
        assertEquals(listOf("am stack list"), commands)
    }

    @Test
    fun routesSecondSelectedAppToThePickerPane() {
        val commands = mutableListOf<String>()
        var snapshot = emptySplitWithStoppedApps
        val router = SplitShellRouter { command ->
            commands += command
            if (command == "am stack list") snapshot else ""
        }

        assertTrue(router.tick())
        snapshot = fullscreenVlcLaunch
        assertTrue(router.tick())
        snapshot = splitWithVlcInFreePane
        assertTrue(router.tick())
        commands.clear()
        snapshot = fullscreenMapsLaunch

        assertTrue(router.tick())
        assertEquals(
            listOf(
                "am stack list",
                "am stack move-task 81 3 true",
                "am task resize 81 24 112 1680 1472",
            ),
            commands,
        )
    }

    @Test
    fun resumesWithThePickerAsSecondDestinationAfterProcessRestart() {
        val commands = mutableListOf<String>()
        var snapshot = splitWithVlcInFreePane
        val router = SplitShellRouter { command ->
            commands += command
            if (command == "am stack list") snapshot else ""
        }

        assertTrue(router.tick())
        commands.clear()
        snapshot = fullscreenMapsLaunch

        assertTrue(router.tick())
        assertEquals(
            listOf(
                "am stack list",
                "am stack move-task 81 3 true",
                "am task resize 81 24 112 1680 1472",
            ),
            commands,
        )
    }

    @Test
    fun doesNotRouteAThirdLaunchAfterBothPanesAreFilled() {
        val commands = mutableListOf<String>()
        var snapshot = emptySplitWithStoppedApps
        val router = SplitShellRouter { command ->
            commands += command
            if (command == "am stack list") snapshot else ""
        }

        assertTrue(router.tick())
        snapshot = fullscreenVlcLaunch
        assertTrue(router.tick())
        snapshot = splitWithVlcInFreePane
        assertTrue(router.tick())
        snapshot = fullscreenMapsLaunch
        assertTrue(router.tick())
        snapshot = splitWithBothApps
        assertTrue(router.tick())
        commands.clear()
        snapshot = fullscreenVideoLaunch

        assertFalse(router.tick())
        assertEquals(listOf("am stack list"), commands)
    }

    @Test
    fun disableReturnsAnyRoutedAppsAndRestoresStockAnchors() {
        val commands = mutableListOf<String>()
        val router = SplitShellRouter { command ->
            commands += command
            if (command == "am stack list") splitWithBothAppsAndDenzaRoot else ""
        }

        router.disable()

        assertEquals(
            listOf(
                "am stack list",
                "am stack move-task 81 4 false",
                "am task resize 81 0 0 2560 1600",
                "am stack move-task 70 4 false",
                "am task resize 70 0 0 2560 1600",
                "am stack move-task 24 3 true",
                "am task resize 24 24 112 1680 1472",
                "am stack move-task 20 2 true",
                "am task resize 20 1704 112 2536 1472",
            ),
            commands,
        )
    }

    private companion object {
        val fullscreenDenzaApps = """
            RootTask id=4 bounds=[0,0][2560,1600] displayId=0 userId=0
              taskId=58: dev.denza.apps/dev.denza.apps.MainActivity bounds=[0,0][2560,1600] userId=0 visible=true topActivity=ComponentInfo{dev.denza.apps/dev.denza.apps.MainActivity}

            RootTask id=3 bounds=[24,112][1680,1472] displayId=0 userId=0
              taskId=24: com.android.launcher3/com.android.launcher3.Launcher bounds=[24,112][1680,1472] userId=0 visible=false topActivity=ComponentInfo{com.android.launcher3/com.android.launcher3.Launcher}

            RootTask id=2 bounds=[1704,112][2536,1472] displayId=0 userId=0
              taskId=20: com.byd.launchermap/com.byd.automap.activity.MainActivity bounds=[1704,112][2536,1472] userId=0 visible=false topActivity=ComponentInfo{com.byd.launchermap/com.byd.automap.activity.MainActivity}
        """.trimIndent()

        val emptySplitWithStoppedApps = """
            RootTask id=3 bounds=[24,112][1680,1472] displayId=0 userId=0
              taskId=24: com.android.launcher3/com.android.launcher3.Launcher bounds=[24,112][1680,1472] userId=0 visible=true topActivity=ComponentInfo{com.byd.auto_photo/com.byd.auto_photo.MainActivity}
              taskId=42: com.byd.auto_photo/com.byd.auto_photo.MainActivity bounds=[24,112][1680,1472] userId=0 visible=true topActivity=ComponentInfo{com.byd.auto_photo/com.byd.auto_photo.MainActivity}

            RootTask id=2 bounds=[1704,112][2536,1472] displayId=0 userId=0
              taskId=20: com.byd.launchermap/com.byd.automap.activity.MainActivity bounds=[1704,112][2536,1472] userId=0 visible=true topActivity=ComponentInfo{com.byd.launchermap/com.byd.automap.activity.MainActivity}

            RootTask id=47 bounds=[0,0][2560,1600] displayId=0 userId=0
              taskId=47: ru.yandex.music/ru.yandex.music.main.MainScreenActivity bounds=[0,0][2560,1600] userId=0 visible=false topActivity=ComponentInfo{ru.yandex.music/ru.yandex.music.main.MainScreenActivity}

            RootTask id=70 bounds=[0,0][2560,1600] displayId=0 userId=0
              taskId=70: org.videolan.vlc/org.videolan.vlc.StartActivity bounds=[0,0][2560,1600] userId=0 visible=false topActivity=ComponentInfo{org.videolan.vlc/org.videolan.vlc.StartActivity}
        """.trimIndent()

        val fullscreenVlcLaunch = emptySplitWithStoppedApps
            .replace("visible=true", "visible=false")
            .replace(
                "taskId=70: org.videolan.vlc/org.videolan.vlc.StartActivity bounds=[0,0][2560,1600] userId=0 visible=false",
                "taskId=70: org.videolan.vlc/org.videolan.vlc.StartActivity bounds=[0,0][2560,1600] userId=0 visible=true",
            )

        val splitWithVlcInFreePane = """
            RootTask id=3 bounds=[24,112][1680,1472] displayId=0 userId=0
              taskId=24: com.android.launcher3/com.android.launcher3.Launcher bounds=[24,112][1680,1472] userId=0 visible=true topActivity=ComponentInfo{com.byd.auto_photo/com.byd.auto_photo.MainActivity}
              taskId=42: com.byd.auto_photo/com.byd.auto_photo.MainActivity bounds=[24,112][1680,1472] userId=0 visible=true topActivity=ComponentInfo{com.byd.auto_photo/com.byd.auto_photo.MainActivity}

            RootTask id=2 bounds=[1704,112][2536,1472] displayId=0 userId=0
              taskId=20: com.byd.launchermap/com.byd.automap.activity.MainActivity bounds=[1704,112][2536,1472] userId=0 visible=true topActivity=ComponentInfo{org.videolan.vlc/org.videolan.vlc.StartActivity}
              taskId=70: org.videolan.vlc/org.videolan.vlc.StartActivity bounds=[1704,112][2536,1472] userId=0 visible=true topActivity=ComponentInfo{org.videolan.vlc/org.videolan.vlc.StartActivity}
        """.trimIndent()

        val fullscreenMapsLaunch = """
            RootTask id=3 bounds=[24,112][1680,1472] displayId=0 userId=0
              taskId=24: com.android.launcher3/com.android.launcher3.Launcher bounds=[24,112][1680,1472] userId=0 visible=false topActivity=ComponentInfo{com.android.launcher3/com.android.launcher3.Launcher}

            RootTask id=2 bounds=[1704,112][2536,1472] displayId=0 userId=0
              taskId=20: com.byd.launchermap/com.byd.automap.activity.MainActivity bounds=[1704,112][2536,1472] userId=0 visible=false topActivity=ComponentInfo{org.videolan.vlc/org.videolan.vlc.StartActivity}
              taskId=70: org.videolan.vlc/org.videolan.vlc.StartActivity bounds=[1704,112][2536,1472] userId=0 visible=false topActivity=ComponentInfo{org.videolan.vlc/org.videolan.vlc.StartActivity}

            RootTask id=81 bounds=[0,0][2560,1600] displayId=0 userId=0
              taskId=81: ru.yandex.yandexmaps/ru.yandex.yandexmaps.MainActivity bounds=[0,0][2560,1600] userId=0 visible=true topActivity=ComponentInfo{ru.yandex.yandexmaps/ru.yandex.yandexmaps.MainActivity}
        """.trimIndent()

        val splitWithBothApps = """
            RootTask id=3 bounds=[24,112][1680,1472] displayId=0 userId=0
              taskId=24: com.android.launcher3/com.android.launcher3.Launcher bounds=[24,112][1680,1472] userId=0 visible=true topActivity=ComponentInfo{ru.yandex.yandexmaps/ru.yandex.yandexmaps.MainActivity}
              taskId=81: ru.yandex.yandexmaps/ru.yandex.yandexmaps.MainActivity bounds=[24,112][1680,1472] userId=0 visible=true topActivity=ComponentInfo{ru.yandex.yandexmaps/ru.yandex.yandexmaps.MainActivity}

            RootTask id=2 bounds=[1704,112][2536,1472] displayId=0 userId=0
              taskId=20: com.byd.launchermap/com.byd.automap.activity.MainActivity bounds=[1704,112][2536,1472] userId=0 visible=true topActivity=ComponentInfo{org.videolan.vlc/org.videolan.vlc.StartActivity}
              taskId=70: org.videolan.vlc/org.videolan.vlc.StartActivity bounds=[1704,112][2536,1472] userId=0 visible=true topActivity=ComponentInfo{org.videolan.vlc/org.videolan.vlc.StartActivity}
        """.trimIndent()

        val fullscreenVideoLaunch = """
            RootTask id=91 bounds=[0,0][2560,1600] displayId=0 userId=0
              taskId=91: com.vk.vkvideo/com.vk.video.screens.main.MainActivity bounds=[0,0][2560,1600] userId=0 visible=true topActivity=ComponentInfo{com.vk.vkvideo/com.vk.video.screens.main.MainActivity}

            RootTask id=3 bounds=[24,112][1680,1472] displayId=0 userId=0
              taskId=24: com.android.launcher3/com.android.launcher3.Launcher bounds=[24,112][1680,1472] userId=0 visible=false topActivity=ComponentInfo{ru.yandex.yandexmaps/ru.yandex.yandexmaps.MainActivity}
              taskId=81: ru.yandex.yandexmaps/ru.yandex.yandexmaps.MainActivity bounds=[24,112][1680,1472] userId=0 visible=false topActivity=ComponentInfo{ru.yandex.yandexmaps/ru.yandex.yandexmaps.MainActivity}

            RootTask id=2 bounds=[1704,112][2536,1472] displayId=0 userId=0
              taskId=20: com.byd.launchermap/com.byd.automap.activity.MainActivity bounds=[1704,112][2536,1472] userId=0 visible=false topActivity=ComponentInfo{org.videolan.vlc/org.videolan.vlc.StartActivity}
              taskId=70: org.videolan.vlc/org.videolan.vlc.StartActivity bounds=[1704,112][2536,1472] userId=0 visible=false topActivity=ComponentInfo{org.videolan.vlc/org.videolan.vlc.StartActivity}
        """.trimIndent()

        val splitWithBothAppsAndDenzaRoot = splitWithBothApps + """

            RootTask id=4 bounds=[0,0][2560,1600] displayId=0 userId=0
              taskId=58: dev.denza.apps/dev.denza.apps.MainActivity bounds=[0,0][2560,1600] userId=0 visible=false topActivity=ComponentInfo{dev.denza.apps/dev.denza.apps.MainActivity}
        """.trimIndent()
    }
}
