package dev.denza.apps.feature.hud

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The YandexHUD that knows the host is told from the builds before it by its manifest naming this
 * app - the name `tools/yandex_hud_build/manifest.py` writes into `<queries>`.
 */
class HudInstallsTest {

    @Test
    fun theBuildToolNamesThisApp() {
        val manifest = repoFile("tools/yandex_hud_build/manifest.py").readText()
        val host = Regex("""HOST_PACKAGE\s*=\s*'([^']+)'""").find(manifest)?.groupValues?.get(1)
        assertEquals(HudInstalls.HOST_PACKAGE, host)
    }

    @Test
    fun aBinaryManifestNamesThisAppInUtf16OrUtf8() {
        val pool = byteArrayOf(3, 0) + HudInstalls.HOST_PACKAGE.toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0)
        assertTrue(HudInstalls.namesHost(pool))
        assertTrue(HudInstalls.namesHost(byteArrayOf(14, 14) + HudInstalls.HOST_PACKAGE.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun aBuildBeforeTheHostDoesNot() {
        val other = "ru.yandex.yandexnavi".toByteArray(Charsets.UTF_16LE)
        assertFalse(HudInstalls.namesHost(other))
        assertFalse(HudInstalls.namesHost(ByteArray(0)))
        // A name that only starts like it is another package.
        assertFalse(HudInstalls.namesHost("dev.denza.app".toByteArray(Charsets.UTF_16LE)))
    }

    private fun repoFile(path: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, path)
            if (f.isFile) return f
            dir = dir.parentFile
        }
        error("$path not found")
    }
}
