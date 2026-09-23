package dev.denza.apps.feature.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shell protocol against what the car printed.
 *
 * Every answer below is copied from the owner-approved live run of 2026-09-23
 * (`captures/telematics-20260923/stock-client-wifi/live-hold-1/`): the stock TCP getter before and
 * after the link came up, the profile broadcast, and the native Binder's reply to `notify_nw(4)`.
 */
class CloudLinkProtocolTest {

    @Test
    fun aReadOfTheConnectedCarIsTheCarTheOwnerLeftRunning() {
        // host-ended-car-still-active.json, 15:15:23, as the tagged read prints it.
        val output = """
            @@profile
            double_apn
            @@build
            triple_apn
            @@apn1
            1
            @@pid
            113
            @@tcp
            Result: Parcel(00000000 00000001   '........')
            @@wifi
            1
        """.trimIndent()

        val car = CloudLinkProtocol.parseRead(output)
        assertEquals("double_apn", car.profile)
        assertEquals("triple_apn", car.buildProfile)
        assertEquals(true, car.apn1Disabled)
        assertEquals("113", car.cloudPid)
        assertEquals(true, car.connected)
        assertEquals(true, car.wifiRetained)
        assertTrue(car.wifiProfile)
        assertFalse(car.onStockProfile)
        assertEquals("triple_apn", car.stockProfile)
    }

    @Test
    fun aReadOfTheUntouchedCarIsTheStockBaseline() {
        // before.json, 14:39:57: the stock profile, no connection, and the retention key absent -
        // which `settings get` prints as the word `null`.
        val output = """
            @@profile
            triple_apn
            @@build
            triple_apn
            @@apn1
            0
            @@pid
            113
            @@tcp
            Result: Parcel(00000000 00000000   '........')
            @@wifi
            null
        """.trimIndent()

        val car = CloudLinkProtocol.parseRead(output)
        assertEquals(false, car.connected)
        assertEquals(false, car.wifiRetained)
        assertFalse(car.wifiProfile)
        assertTrue(car.onStockProfile)
    }

    @Test
    fun aFieldThatPrintedNothingIsUnknownAndNeverOffline() {
        // `service call` into a missing service prints to stderr, which the shell does not return,
        // and `pidof` prints nothing for a process that is not there.
        val output = """
            @@profile
            double_apn
            @@build
            triple_apn
            @@apn1
            1
            @@pid
            @@tcp
            @@wifi
        """.trimIndent()

        val car = CloudLinkProtocol.parseRead(output)
        assertNull(car.cloudPid)
        assertNull("a getter that did not answer is not «disconnected»", car.connected)
        assertNull(car.wifiRetained)
        // Everything else still read.
        assertTrue(car.wifiProfile)
    }

    @Test
    fun theTcpGetterIsItsSecondWordAndOnlyWithoutAnException() {
        assertEquals(true, CloudLinkProtocol.tcpConnected("Result: Parcel(00000000 00000001   '........')"))
        assertEquals(false, CloudLinkProtocol.tcpConnected("Result: Parcel(00000000 00000000   '........')"))
        // An exception code in the first word is not a reading.
        assertNull(CloudLinkProtocol.tcpConnected("Result: Parcel(ffffffb5 00000001   '........')"))
        assertNull(CloudLinkProtocol.tcpConnected("Result: Parcel(NULL)"))
        assertNull(CloudLinkProtocol.tcpConnected("service: Service cloudmanager does not exist"))
    }

    @Test
    fun theWritesAreTheOnesTheLiveRunSent() {
        assertEquals(
            "am broadcast --user 0 -a com.byd.action.RADIO_CONFIG -p com.android.phone " +
                "-f 0x01000000 --es opt_name set_default_data --es apn_type double_apn",
            CloudLinkProtocol.profileCommand(CloudLinkProtocol.WIFI_PROFILE),
        )
        assertEquals("service call cloudmanager 1 i32 4", CloudLinkProtocol.notifyCommand(CloudLinkProtocol.READY))
        assertEquals("service call cloudmanager 1 i32 -5", CloudLinkProtocol.notifyCommand(CloudLinkProtocol.GONE))

        // switch-double.json
        assertTrue(
            CloudLinkProtocol.profileAccepted(
                "Broadcasting: Intent { act=com.byd.action.RADIO_CONFIG flg=0x1400000 " +
                    "pkg=com.android.phone (has extras) }\nBroadcast completed: result=0",
            ),
        )
        assertFalse(CloudLinkProtocol.profileAccepted("Error: Activity not started"))
        // notify-native-ready.json
        assertTrue(CloudLinkProtocol.notifyAccepted("Result: Parcel(NULL)"))
        assertFalse(CloudLinkProtocol.notifyAccepted("service: Service cloudmanager does not exist"))
    }

    /** Off gives the key back to the car rather than writing a zero the car never had. */
    @Test
    fun keepingWifiOffDeletesTheKey() {
        assertEquals(
            "settings put global byd_off_wifi_switch 1",
            CloudLinkProtocol.wifiRetentionCommand(retain = true),
        )
        assertEquals(
            "settings delete global byd_off_wifi_switch",
            CloudLinkProtocol.wifiRetentionCommand(retain = false),
        )
    }

    @Test
    fun oneReadAsksEverythingOnceAndTagsEachAnswer() {
        val command = CloudLinkProtocol.readCommand()
        listOf("@@profile", "@@build", "@@apn1", "@@pid", "@@tcp", "@@wifi").forEach { tag ->
            assertEquals("$tag once", 1, Regex(Regex.escape("echo $tag;")).findAll(command).count())
        }
        // Reads only: the one round trip the tile makes changes nothing on the car.
        assertFalse(command.contains(" put "))
        assertFalse(command.contains("i32"))
        assertFalse(command.contains("am broadcast"))
    }

    @Test
    fun aCarBuiltOnTheWifiProfileHasNothingToRestore() {
        val car = CloudCarState(profile = "double_apn", buildProfile = "double_apn", apn1Disabled = true)
        assertEquals("double_apn", car.stockProfile)
        assertTrue(car.onStockProfile)
        // And a build that names something else falls back to this car's.
        assertEquals("triple_apn", CloudCarState(buildProfile = "single_apn").stockProfile)
    }
}
