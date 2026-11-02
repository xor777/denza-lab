package dev.denza.apps.feature.vehicle

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The resident reader ([VehicleReadMain]) must be a drop-in for the shell batch: the same calls
 * asked, the same text back, read by the same [AutoserviceShell.parse]. Its Binder half runs only
 * on the car; the line it is sent and the text it prints are tested here.
 */
class VehicleReadersTest {

    @Test
    fun everyCallOfTheBatchReachesTheHelperAsItWouldTheShell() {
        val batch = VehicleSignal.HOT + VehicleSignal.COLD + VehicleSignal.EVENT
        val calls = VehicleReadMain.calls(VehicleReaders.request(batch))!!

        assertEquals(batch.size, calls.size)
        batch.forEachIndexed { index, signal ->
            assertArrayEquals(
                signal.name,
                intArrayOf(signal.transact.code, signal.device, signal.fid),
                calls[index],
            )
        }
    }

    @Test
    fun theHelperRefusesAnythingButARead() {
        listOf(
            "",
            "r",
            "x 5:1:2",
            "r 6:1012:339738656",
            "r 5:1012",
            "r 5:1012:339738656:1",
            "r 5:1012:0x14400000",
            "r 5:1012:99999999999",
            "r 5:1012:2147483648",
            "r 5:a:1",
            "r " + List(VehicleReadMain.MAX_CALLS + 1) { "5:1:1" }.joinToString(" "),
        ).forEach { line -> assertNull(line, VehicleReadMain.calls(line)) }
        assertTrue(VehicleReadMain.calls("r 7:1013:-1807745016") != null)
    }

    @Test
    fun theHelpersAnswerReadsAsTheShellsDid() {
        val batch = listOf(VehicleSignal.POWER_KW, VehicleSignal.CHARGE_KW, VehicleSignal.PACK_VOLT)
        val shell = """
            @@0
            Result: Parcel(00000000 0000002b   '........')
            @@1
            Result: Parcel(00000000 422c0000   '..,.....')
            @@2
            Result: Parcel(00000000 00000226   '....&...')
        """.trimIndent()
        val helper = listOf(0x2b, 0x422c0000, 0x226).withIndex().joinToString("\n") { (index, word) ->
            "@@$index\n" + VehicleReadMain.parcel(intArrayOf(0, word))
        }

        assertEquals(AutoserviceShell.parse(shell, batch), AutoserviceShell.parse(helper, batch))
        assertEquals(3, AutoserviceShell.parse(helper, batch).size)
    }

    /** Through a terminal the lines end in CR LF, and a sentinel is a sentinel whoever printed it. */
    @Test
    fun aNegativeWordASentinelAndASilentCallReadAsTheShells() {
        val batch = listOf(VehicleSignal.POWER_KW, VehicleSignal.PACK_VOLT, VehicleSignal.ODOMETER_KM)
        val helper = "@@0\r\n" + VehicleReadMain.parcel(intArrayOf(0, -12)) + "\r\n" +
            "@@1\r\n" + VehicleReadMain.parcel(intArrayOf(0, 0xFFFFD8E5.toInt())) + "\r\n" +
            "@@2\r\n"

        assertEquals("Result: Parcel(00000000 fffffff4)", VehicleReadMain.parcel(intArrayOf(0, -12)))
        assertEquals("Result: Parcel(NULL)", VehicleReadMain.parcel(IntArray(0)))
        val values = AutoserviceShell.parse(helper, batch)
        assertEquals(AutoserviceShell.decode(VehicleSignal.POWER_KW, -12), values[VehicleSignal.POWER_KW])
        assertFalse(VehicleSignal.PACK_VOLT in values)
        assertFalse(VehicleSignal.ODOMETER_KM in values)
    }

    @Test
    fun aHelperThatNeverAnsweredHandsTheNextReadToTheShell() {
        val choice = VehicleReaderChoice()
        assertTrue(choice.preferResident)

        choice.ended(resident = true, answered = false)
        assertFalse(choice.preferResident)

        // The shell's reader ends - a sleep, a lost link - and the helper is tried again.
        choice.ended(resident = false, answered = true)
        assertTrue(choice.preferResident)

        choice.ended(resident = true, answered = true)
        assertTrue(choice.preferResident)

        choice.ended(resident = true, answered = false)
        choice.ended(resident = false, answered = false)
        assertTrue(choice.preferResident)
    }

    /** The cycle the owner drove with - the shell's batch and the interval - whichever reader answers. */
    @Test
    fun aFastReaderWaitsOutTheShellsBatchSoTheCadenceStays() {
        val hot = VehicleSweepCadence.HOT_INTERVAL_MS
        val ledger = VehicleSweepCadence.LEDGER_INTERVAL_MS
        val shellBatch = VehicleSweepCadence.SHELL_BATCH_MS

        assertEquals(hot, VehicleSweepCadence.waitMs(hot, 140, resident = false))
        assertEquals(hot, VehicleSweepCadence.waitMs(hot, 400, resident = false))
        assertEquals(ledger, VehicleSweepCadence.waitMs(ledger, 150, resident = false))

        listOf(0L, 4L, 60L, 149L).forEach { readMs ->
            assertEquals(hot + shellBatch, readMs + VehicleSweepCadence.waitMs(hot, readMs, resident = true))
            assertEquals(ledger + shellBatch, readMs + VehicleSweepCadence.waitMs(ledger, readMs, resident = true))
        }
        assertEquals(hot, VehicleSweepCadence.waitMs(hot, 400, resident = true))
    }
}
