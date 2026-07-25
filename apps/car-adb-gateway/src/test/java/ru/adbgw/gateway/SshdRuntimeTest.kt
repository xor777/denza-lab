package ru.adbgw.gateway

import org.apache.sshd.common.util.io.PathUtils
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class SshdRuntimeTest {
    @Test
    fun `Android app home is installed before SSH client initialization`() {
        val appHome = Files.createTempDirectory("cag-sshd-home")

        SshdRuntime.initialize(appHome)

        assertEquals(appHome, PathUtils.getUserHomeFolder())
    }
}
