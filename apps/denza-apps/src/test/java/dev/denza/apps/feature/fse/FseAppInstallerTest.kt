package dev.denza.apps.feature.fse

import dev.denza.apps.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FseAppInstallerTest {
    /**
     * Review round 2, finding 9: the chooser offered Denza Apps itself (minSdk 33) to the
     * passenger screen's Android 12L, which refuses it only after the copy.
     */
    @Test
    fun anApkForANewerAndroidThanThePassengerScreensIsNotOffered() {
        assertEquals(
            R.string.fse_unavailable_newer_android,
            FseAppInstaller.unavailableReason(splitCount = 0, sourceDir = BASE, readable = true, minSdk = 33),
        )
        assertNull(FseAppInstaller.unavailableReason(0, BASE, readable = true, minSdk = FseAppInstaller.FSE_SDK))
        assertNull(FseAppInstaller.unavailableReason(0, BASE, readable = true, minSdk = 26))
    }

    @Test
    fun aSplitOrMissingApkSaysSoBeforeItsAndroid() {
        assertEquals(R.string.fse_unavailable_split, FseAppInstaller.unavailableReason(2, BASE, readable = true, minSdk = 33))
        assertEquals(R.string.fse_unavailable_apk_not_found, FseAppInstaller.unavailableReason(0, "", readable = false, minSdk = 26))
        assertEquals(R.string.fse_unavailable_apk_unreadable, FseAppInstaller.unavailableReason(0, BASE, readable = false, minSdk = 26))
    }

    private companion object {
        const val BASE = "/data/app/base.apk"
    }

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

    /**
     * Every command the copy sends, letter for letter (pinned 2026-10-09, before the quoting moved
     * to the shared shell layer).
     */
    @Test
    fun theStagingCommandsAreExactlyThese() {
        val root = "/storage/FFFF-FFFC/denza-apps-install-1000123456"
        val apk = "/data/app/~~Q2x9Fh_k-3Lw==/ru.example.app-Vb7_k2==/base.apk"
        val target = "$root/wallpaper/Application.apk"

        assertEquals(
            "mkdir -p '$root/wallpaper' && echo 'eyJ3YWxscGFwZXJfdHlwZSI6MTR9' | base64 -d > " +
                "'$root/config.json'",
            FseAppInstaller.stageConfigCommand(root, "eyJ3YWxscGFwZXJfdHlwZSI6MTR9"),
        )
        assertEquals("rm -f '$target'; : > '$target'", FseAppInstaller.truncateCommand(target))
        assertEquals(
            "dd if='$apk' of='$target' bs=4194304 skip=3 seek=3 count=1 conv=notrunc " +
                ">/dev/null 2>&1; echo \$?",
            FseAppInstaller.copyBlockCommand(apk, target, 3),
        )
        assertEquals("stat -c %s '$target'", FseAppInstaller.sizeCommand(target))
        assertEquals("rm -rf '$root'", FseAppInstaller.removeStageCommand(root))
    }
}
