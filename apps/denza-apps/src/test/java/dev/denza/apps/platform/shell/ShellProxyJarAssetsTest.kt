package dev.denza.apps.platform.shell

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The build packs each helper jar under a name, and [ShellProxyJar] opens the asset by a name; the
 * two are written in two languages and nothing else ties them. A jar packed under any other name
 * would leave the split and navigation on the slow APK and the turn-signal listener off, with every
 * other test green.
 */
class ShellProxyJarAssetsTest {

    @Test
    fun theBuildPacksExactlyTheJarsTheAppStages() {
        val script = File("build.gradle.kts").readText()
        val packed = JAR_LITERAL.findAll(script).map { it.groupValues[1] }.toSet()

        assertEquals(ShellProxyJar.entries.map(ShellProxyJar::asset).toSet(), packed)
    }

    private companion object {
        val JAR_LITERAL = Regex(""""([\w-]+\.jar)"""")
    }
}
