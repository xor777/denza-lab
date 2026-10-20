package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.bus.FakeDiagnosticBus
import dev.denza.apps.feature.diagnostics.bus.hex
import dev.denza.apps.feature.vehicle.VehicleSignal
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope

/** The hub as a test sets it: P and speed, and a sweep counter the test bumps. */
internal class FakeCar(var readings: CarReadings = parked()) : CarFeed {
    var opened = 0
    var closed = 0
    override fun open() { opened++ }
    override fun read(): CarReadings = readings
    override fun close() { closed++ }

    companion object {
        fun parked(sweep: Long = 1, extra: Map<VehicleSignal, Double> = emptyMap()) = CarReadings(
            hub = mapOf(VehicleSignal.GEARBOX_PARK to 1.0, VehicleSignal.VEHICLE_SPEED to 0.0) + extra,
            sweep = sweep,
        )
        fun moving() = CarReadings(hub = mapOf(VehicleSignal.GEARBOX_PARK to 0.0, VehicleSignal.VEHICLE_SPEED to 12.0))
    }
}

internal class MemoryStore(var scan: StoredScan? = null) : ScanStore {
    val saved = mutableListOf<StoredScan>()
    override fun load(): StoredScan? = scan
    override fun save(scan: StoredScan) {
        saved += scan
        this.scan = scan
    }
}

internal object Fixtures {
    /** 2026-10-20 13:31 UTC, the day of the research runs. */
    val EPOCH: Long = Instant.parse("2026-10-20T13:31:00Z").toEpochMilli()

    /**
     * Data bytes of the ECM's PIDs: run 2's trace where it has them (`ObdCodecTest.ecmRun2`),
     * otherwise a plain value of the right length.
     */
    val ECM_DATA: Map<Int, String> = mapOf(
        0x03 to "0000", 0x04 to "00", 0x05 to "31", 0x06 to "80", 0x07 to "80", 0x0B to "65", 0x0C to "0000",
        0x0D to "00", 0x0E to "80", 0x0F to "34", 0x11 to "2B", 0x13 to "03", 0x15 to "FFFF", 0x1C to "29", 0x1F to "0000",
        0x21 to "0000", 0x23 to "0000", 0x2C to "00", 0x2D to "80", 0x2E to "00", 0x2F to "80", 0x30 to "10",
        0x31 to "0100", 0x33 to "65", 0x34 to "80008000", 0x3C to "0190", 0x41 to "0007F5F4", 0x42 to "3714", 0x43 to "0000", 0x44 to "8000",
        0x45 to "00", 0x46 to "34", 0x47 to "00", 0x49 to "00", 0x4A to "00", 0x4C to "00", 0x54 to "0000",
        0x56 to "8080", 0x67 to "013100", 0x6B to "0134000000", 0x7A to "07000000000000",
    )

    /** The ECM answering any Mode 01 request from [data], like a module that offers every PID asked. */
    fun ecmAnswers(bus: FakeDiagnosticBus, data: () -> Map<Int, String> = { ECM_DATA }) {
        val previous = bus.unscripted
        bus.unscripted = { r ->
            val u = hex(r.uds)
            if (r.address == 0x7E0 && u.startsWith("01") && u.length > 2) {
                val values = data()
                FakeDiagnosticBus.Step.Answer("41" + u.drop(2).chunked(2).joinToString("") { p -> p + (values[p.toInt(16)] ?: "") }, 1, null)
            } else {
                previous(r)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun TestScope.session(
        bus: FakeDiagnosticBus,
        car: FakeCar = FakeCar(),
        store: ScanStore = MemoryStore(),
        sinks: StreamSinks = StreamSinks { null },
        epoch: Long = EPOCH,
    ) = DiagnosticsSession(
        buses = { bus },
        store = store,
        car = car,
        sinks = sinks,
        wallClock = { epoch + testScheduler.currentTime },
        zone = ZoneOffset.UTC,
        // Cancelled with the test, which does not wait for the session's endless clock.
        context = backgroundScope.coroutineContext,
    )

    /**
     * The bus of «машина сейчас» ([CarNow]); [before] scripts what a test changes. Steps of one request
     * are used in order, so a test's own step goes first and the recorded answer follows it.
     */
    fun TestScope.carNow(epoch: Long = EPOCH, before: FakeDiagnosticBus.() -> Unit = {}) =
        FakeDiagnosticBus(wallClock = { epoch + testScheduler.currentTime }).apply {
            before()
            CarNow.script(this)
        }

    fun DiagnosticsModel.row(request: Int): ModuleRowView = groups.flatMap { it.rows }.first { it.request == request }
    fun DiagnosticsModel.rows(): List<ModuleRowView> = groups.flatMap { it.rows }
}
