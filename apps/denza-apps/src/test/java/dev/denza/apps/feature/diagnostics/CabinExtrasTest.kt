package dev.denza.apps.feature.diagnostics

import dev.denza.apps.feature.diagnostics.scan.ExtrasPoller
import dev.denza.apps.feature.diagnostics.scan.ExtrasShell
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cabin's GETs (architecture 6, 7.2) against research/vehicle-diagnostics/cabin-signals-20261021.json:
 * each one an entry of the research with its device and transaction, every code it names taken
 * and nothing else, and the batch carrying them only while «Салон · Состояние» asks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CabinExtrasTest {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "research/vehicle-diagnostics").exists() }

    private val research: List<JSONObject> by lazy {
        val a = JSONArray(File(root, "research/vehicle-diagnostics/cabin-signals-20261021.json").readText())
        (0 until a.length()).map { a.getJSONObject(it) }
    }

    private fun entry(e: DiagnosticExtra): JSONObject =
        research.single { it.getString("fid").removePrefix("0x").toLong(16).toInt() == e.fid && it.getInt("device") == e.device }

    private val cabin = DiagnosticExtra.entries.filter { ExtraPage.CABIN in it.pages }

    /** The set points are the stock's control FIDs read back; every other GET is a status. */
    private val setPoints = setOf(
        DiagnosticExtra.FRIDGE_FRONT_COLD, DiagnosticExtra.FRIDGE_FRONT_HOT, DiagnosticExtra.FRIDGE_FRONT_FRESH,
        DiagnosticExtra.FRIDGE_REAR_COLD, DiagnosticExtra.FRIDGE_REAR_HOT, DiagnosticExtra.FRIDGE_REAR_FRESH,
    )

    @Test
    fun everyCabinGetIsAResearchEntryWithItsDeviceAndTransaction() {
        assertEquals(44, cabin.size)
        assertEquals("one GET a FID", cabin.size, cabin.map { it.device to it.fid }.toSet().size)
        cabin.forEach { e ->
            val r = entry(e)
            assertEquals(e.name, r.getInt("transaction"), e.transact)
            assertEquals(e.name, r.getLong("fidSigned").toInt(), e.fid)
            assertEquals(e.name, if (e in setPoints) "control" else "status", r.getString("kind"))
        }
    }

    @Test
    fun everyNamedCodeIsTakenAndTheNoSignalWordsAreNot() {
        cabin.filter { it !in setPoints }.forEach { e ->
            val named = entry(e).getJSONObject("values").keys().asSequence().mapNotNull { it.toIntOrNull() }.toSet()
            named.forEach { code ->
                // The wheel's 0 is its INVALID, no state: «н/д». A massage level's 0 (INVALID) is taken,
                // the stock's rule reads it as not massaging.
                val invalid = code == 0 && e == DiagnosticExtra.WHEEL_HEAT
                if (invalid) assertNull("${e.name} $code", e.decode(code)) else assertEquals("${e.name} $code", code.toDouble(), e.decode(code)!!, 0.0)
            }
            listOf(65535, -10011, -10013, -1).forEach { assertNull("${e.name} $it", e.decode(it)) }
        }
        // 7 is no massage mode
        listOf(DiagnosticExtra.MASSAGE_MODE_FL, DiagnosticExtra.MASSAGE_MODE_FR, DiagnosticExtra.MASSAGE_MODE_RL, DiagnosticExtra.MASSAGE_MODE_RR)
            .forEach { assertNull(it.decode(7)); assertNull(it.decode(10)); assertNull(it.decode(0)) }
        // who sits: every code but the no-signal word, 2 among them
        assertEquals(3.0, DiagnosticExtra.SEATED_RC.decode(3)!!, 0.0)
        assertEquals(0.0, DiagnosticExtra.SEATED_FL.decode(0)!!, 0.0)
        // a pad's code outside 0..3 is not «standby»
        assertNull(DiagnosticExtra.PAD_LEFT.decode(4))
    }

    @Test
    fun theSetPointsTakeTheirCelsiusRawRanges() {
        listOf(DiagnosticExtra.FRIDGE_FRONT_COLD, DiagnosticExtra.FRIDGE_REAR_COLD).forEach { e ->
            assertEquals(1.0, e.decode(1)!!, 0.0); assertEquals(34.0, e.decode(34)!!, 0.0)
            assertNull(e.decode(0)); assertNull(e.decode(35)); assertNull(e.decode(60))
        }
        listOf(DiagnosticExtra.FRIDGE_FRONT_HOT, DiagnosticExtra.FRIDGE_REAR_HOT).forEach { e ->
            assertEquals(16.0, e.decode(16)!!, 0.0); assertNull(e.decode(0)); assertNull(e.decode(17))
        }
        listOf(DiagnosticExtra.FRIDGE_FRONT_FRESH, DiagnosticExtra.FRIDGE_REAR_FRESH).forEach { e ->
            assertEquals(3.0, e.decode(3)!!, 0.0); assertNull(e.decode(0)); assertNull(e.decode(4))
        }
        setPoints.forEach { assertNull(it.decode(65535)) }
    }

    @Test
    fun theParkedSweepDecodesAsTheResearchReadIt() {
        val batch = cabin.joinToString("\n") { e ->
            "@@extra:${e.key}\nResult: Parcel(00000000 %08x   '........')".format(entry(e).getString("observedParked20261020").toInt())
        }
        val decoded = DiagnosticExtraReader.decode(batch, 1, 1).values
        // the right rear's heating and ventilation read 65535: no reading; the massage levels read 0, the INVALID taken as a code
        assertEquals(cabin.toSet() - DiagnosticExtra.SEAT_HEAT_RR - DiagnosticExtra.SEAT_VENT_RR, decoded.keys)
        assertEquals(19.0, decoded.getValue(DiagnosticExtra.FRIDGE_FRONT_COLD), 0.0)
        assertEquals(3.0, decoded.getValue(DiagnosticExtra.PAD_RIGHT), 0.0)
    }

    @Test
    fun onlyTheCabinsBatchCarriesTheCabinsGets() {
        val base = DiagnosticExtraReader.command
        val all = DiagnosticExtraReader.command(setOf(ExtraPage.CABIN))
        cabin.forEach { e ->
            val get = "echo @@extra:${e.key}; service call autoservice 5 i32 ${e.device} i32 ${e.fid}"
            assertFalse(e.name, base.contains("@@extra:${e.key};"))
            assertTrue(e.name, all.contains(get))
        }
        assertTrue(all.startsWith(base))
        assertTrue(all.contains("service call autoservice 5 i32 1009 i32 -1042284526"))
    }

    private class Shell : ExtrasShell {
        val commands = mutableListOf<String>()
        override fun shell(command: String): String {
            commands += command
            return if (command.startsWith("getprop")) "170\n" else "@@extra:hud\nResult: Parcel(00000000 00000001   '........')\n"
        }
        override fun close() {}
    }

    @Test
    fun theCabinJoinsTheBatchAtOnceAndLeavesItAtOnce() = runTest {
        val cabinBatch = DiagnosticExtraReader.command(setOf(ExtraPage.CABIN))
        val shell = Shell()
        val poller = ExtrasPoller({ shell }, {}, backgroundScope.coroutineContext, { testScheduler.currentTime }, { testScheduler.currentTime }, { 0L })
        poller.start()
        runCurrent()
        assertEquals(DiagnosticExtraReader.command, shell.commands.last())
        advanceTimeBy(1_000)
        poller.show(ExtraPage.CABIN, true)
        runCurrent()
        assertEquals("the next batch at once", cabinBatch, shell.commands.last())
        assertEquals(1.0, poller.latest.values.getValue(DiagnosticExtra.HUD), 0.0)
        val n = shell.commands.size
        advanceTimeBy(4_900)
        assertEquals("then every five seconds", n, shell.commands.size)
        advanceTimeBy(200)
        assertEquals(cabinBatch, shell.commands.last())
        poller.show(ExtraPage.CABIN, true)
        runCurrent()
        assertEquals("asked again, nothing more", n + 1, shell.commands.size)
        poller.show(ExtraPage.CABIN, false)
        runCurrent()
        assertEquals(DiagnosticExtraReader.command, shell.commands.last())
        poller.stop()
    }
}
