package dev.denza.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Every chooser draws a package the way the car's home screen does.
 *
 * The projection's chooser used to draw the launcher activity's icon and the driver's-screen and
 * Shortcuts choosers the application's; once they shared one cache, which picture a package got
 * depended on which list read it first. One rule now, asked in one order.
 */
class AppIconSourceTest {

    private class FakeSource(
        private val launcher: Map<String, String>,
        private val application: Map<String, String>,
    ) : AppIconSource<String> {
        val asked = mutableListOf<String>()

        override fun launcherIcon(packageName: String): String? {
            asked += "launcher $packageName"
            return launcher[packageName]
        }

        override fun applicationIcon(packageName: String): String? {
            asked += "application $packageName"
            return application[packageName]
        }
    }

    @Test
    fun `the launcher icon wins over the application icon`() {
        val source = FakeSource(
            launcher = mapOf("com.byd.music" to "home-screen note"),
            application = mapOf("com.byd.music" to "application note"),
        )

        assertEquals("home-screen note", source.iconOf("com.byd.music"))
        assertEquals(listOf("launcher com.byd.music"), source.asked)
    }

    @Test
    fun `a package the launcher does not show gets its application icon`() {
        val source = FakeSource(
            launcher = emptyMap(),
            application = mapOf("com.byd.helper" to "application cog"),
        )

        assertEquals("application cog", source.iconOf("com.byd.helper"))
    }

    @Test
    fun `a package that is not on the car has no icon`() {
        assertNull(FakeSource(emptyMap(), emptyMap()).iconOf("gone.app"))
    }
}
