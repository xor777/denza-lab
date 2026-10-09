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
}
