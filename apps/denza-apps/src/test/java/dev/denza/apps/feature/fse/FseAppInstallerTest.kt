package dev.denza.apps.feature.fse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FseAppInstallerTest {
    @Test
    fun parsesMatchingSuccessResponse() {
        assertEquals(
            true,
            FseInstallResponse.result(
                "cross theme data = {\"action\":\"android.intent.action.using_wallpaper_result\",\"result\":1,\"res_id\":123}",
                123,
            ),
        )
    }

    @Test
    fun parsesMatchingFailureResponse() {
        assertEquals(
            false,
            FseInstallResponse.result(
                "cross theme data = {\"action\":\"android.intent.action.using_wallpaper_result\",\"result\":0,\"res_id\":123}",
                123,
            ),
        )
    }

    @Test
    fun parsesNewFirmwareInstalledResponse() {
        val response =
            "{\"action\":\"android.intent.action.using_wallpaper_result\",\"result\":-7,\"res_id\":123}"
        assertEquals(
            true,
            FseInstallResponse.result(response, 123),
        )
        assertEquals(-7, FseInstallResponse.code(response, 123))
    }

    @Test
    fun ignoresAnotherRequest() {
        assertNull(
            FseInstallResponse.result(
                "cross theme data = {\"action\":\"android.intent.action.using_wallpaper_result\",\"result\":1,\"res_id\":124}",
                123,
            ),
        )
    }

    @Test
    fun directCallbackCompletesMatchingResponse() {
        val waiter = FseInstallResponseWaiter(123)

        waiter.onPayload(
            "{\"action\":\"android.intent.action.using_wallpaper_result\",\"result\":1,\"res_id\":123}"
                .toByteArray(),
        )

        assertEquals(1, waiter.await(0))
    }

    @Test
    fun directCallbackIgnoresAnotherRequest() {
        val waiter = FseInstallResponseWaiter(123)

        waiter.onPayload(
            "{\"action\":\"android.intent.action.using_wallpaper_result\",\"result\":1,\"res_id\":124}"
                .toByteArray(),
        )

        assertNull(waiter.await(0))
        assertFalse(waiter.isComplete())
    }

    @Test
    fun abandonedStageCleanupIsScopedToDenzaAppsResources() {
        val command = FseAppInstaller.abandonedStageCleanupCommand()

        assertTrue(command.contains("/storage/FFFF-FFFC/denza-apps-install-*"))
        assertFalse(command.contains("/storage/FFFF-FFFC/*"))
        assertFalse(command.contains("/storage/FFFF-FFFC/denza-install-*"))
    }

    @Test
    fun passiveDiagnosticsExposeSplitNamesFilesAndFileState() {
        val lines = FseApkLayoutDiagnostics.render(
            candidateCount = 3,
            layouts = listOf(
                FseApkLayoutDiagnostic(
                    packageName = "ru.kinopoisk",
                    label = "Кинопоиск",
                    versionName = "8.4.2",
                    launcherSplitName = null,
                    baseFileName = "base.apk",
                    baseIsFile = true,
                    baseReadable = true,
                    baseSizeBytes = 120_000_000,
                    declaredSplitNames = listOf("config.arm64_v8a"),
                    splitFiles = listOf(
                        FseSplitFileDiagnostic(
                            declaredName = "config.arm64_v8a",
                            fileName = "split_config.arm64_v8a.apk",
                            isFile = true,
                            readable = true,
                            sizeBytes = 34_000_000,
                        ),
                        FseSplitFileDiagnostic(
                            declaredName = "index-2",
                            fileName = "split_config.xxhdpi.apk",
                            isFile = false,
                            readable = false,
                            sizeBytes = 0,
                        ),
                    ),
                ),
                FseApkLayoutDiagnostic(
                    packageName = "ru.rutube.app",
                    label = "RUTUBE",
                    versionName = "1",
                    launcherSplitName = null,
                    baseFileName = "base.apk",
                    baseIsFile = true,
                    baseReadable = true,
                    baseSizeBytes = 10,
                    declaredSplitNames = emptyList(),
                    splitFiles = emptyList(),
                ),
            ),
        )
        val report = lines.joinToString("\n")

        assertTrue(report.contains("FSE APK layouts=candidates=3; split=1; monolithic=2"))
        assertTrue(report.contains("FSE split ru.kinopoisk=label=Кинопоиск"))
        assertTrue(report.contains("launcher=base; base=base.apk:120000000B; files=2; names=1"))
        assertTrue(report.contains("FSE split names ru.kinopoisk=config.arm64_v8a"))
        assertTrue(
            report.contains(
                "1:config.arm64_v8a:split_config.arm64_v8a.apk:34000000B",
            ),
        )
        assertTrue(report.contains("2:index-2:split_config.xxhdpi.apk:missing"))
        assertFalse(report.contains("ru.rutube.app=label="))
    }

    @Test
    fun passiveDiagnosticsSayWhenAllCandidatesAreMonolithic() {
        assertEquals(
            listOf(
                "FSE APK layouts=candidates=2; split=0; monolithic=2",
                "FSE split packages=none",
            ),
            FseApkLayoutDiagnostics.render(candidateCount = 2, layouts = emptyList()),
        )
    }
}
