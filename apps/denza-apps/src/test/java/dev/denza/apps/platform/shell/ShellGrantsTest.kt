package dev.denza.apps.platform.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ShellGrantsTest {

    @Test
    fun anAppOpIsSetForThePackage() {
        assertEquals(
            "cmd appops set dev.denza.apps SYSTEM_ALERT_WINDOW allow",
            ShellGrants.appop("dev.denza.apps", "SYSTEM_ALERT_WINDOW", "allow"),
        )
    }

    @Test
    fun aPermissionIsGrantedToThePackage() {
        assertEquals(
            "pm grant dev.denza.apps android.permission.WRITE_SECURE_SETTINGS",
            ShellGrants.permission("dev.denza.apps", "android.permission.WRITE_SECURE_SETTINGS"),
        )
    }

    @Test
    fun anIntegerSettingGoesBareAndAnythingElseQuoted() {
        assertEquals(
            "settings put global force_resizable_activities 1",
            ShellGrants.settingsPut("global", "force_resizable_activities", "1"),
        )
        assertEquals(
            "settings put global byd_off_wifi_switch -1",
            ShellGrants.settingsPut("global", "byd_off_wifi_switch", "-1"),
        )
        assertEquals(
            "settings put secure enabled_accessibility_services " +
                "'dev.denza.apps/dev.denza.apps.SimulcastAccessibilityService:a'\\''b'",
            ShellGrants.settingsPut(
                "secure",
                "enabled_accessibility_services",
                "dev.denza.apps/dev.denza.apps.SimulcastAccessibilityService:a'b",
            ),
        )
        assertEquals("settings put system k ''", ShellGrants.settingsPut("system", "k", ""))
    }

    @Test
    fun aSettingIsDeleted() {
        assertEquals(
            "settings delete global http_proxy",
            ShellGrants.settingsDelete("global", "http_proxy"),
        )
    }

    /** A name that needs quoting is a caller's mistake, refused before anything is sent. */
    @Test
    fun aNameThatIsNotAPlatformNameIsRefused() {
        listOf(
            { ShellGrants.appop("dev.denza.apps; reboot", "SYSTEM_ALERT_WINDOW", "allow") },
            { ShellGrants.appop("dev.denza.apps", "SYSTEM ALERT", "allow") },
            { ShellGrants.permission("", "android.permission.RECORD_AUDIO") },
            { ShellGrants.permission("dev.denza.apps", "\$(id)") },
            { ShellGrants.settingsPut("global", "a b", "1") },
            { ShellGrants.settingsPut("vendor", "key", "1") },
            { ShellGrants.settingsDelete("global", "key'") },
        ).forEach { command ->
            assertThrows(IllegalArgumentException::class.java) { command() }
        }
    }
}
