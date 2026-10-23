package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.report.ReportCodeKey
import dev.denza.apps.feature.diagnostics.report.ReportField
import dev.denza.apps.feature.diagnostics.report.ReportJournal
import dev.denza.apps.feature.diagnostics.report.ReportReads
import dev.denza.apps.feature.diagnostics.report.ReportTestRow
import dev.denza.apps.feature.diagnostics.report.ReportTestMonitor
import dev.denza.apps.feature.diagnostics.report.ReportTests
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the journal and the reads add to «Отчёт» (architecture, 7.4): the day each code of the journal's
 * latest scan was first read, the cells' spread at the last full charge, an excerpt of the journal for the
 * JSON; the VIN the scan read and ECM's freeze frame (Mode 02) where its screen read one.
 */
internal object ReportFeeds {
    fun journal(data: JournalData, clock: ScanClock): ReportJournal? {
        if (data.scans.isEmpty() && data.cells.isEmpty()) return null
        val lives = JournalFacts.lives(data)
        val full = JournalFacts.lastFullCharge(data)
        fun iso(millis: Long) = Instant.ofEpochMilli(millis).atZone(clock.zone).toOffsetDateTime().toString()
        val excerpt = JSONObject().apply {
            put("scans", data.scans.size)
            put("cellSnapshots", data.cells.size)
            data.scans.firstOrNull()?.let { put("firstScanAt", iso(it.atMillis)) }
            data.latest?.let { put("latestScanAt", iso(it.atMillis)) }
            put("codes", JSONArray().apply {
                lives.forEach { l ->
                    put(JSONObject().apply {
                        put("address", Figures.address(l.key.address))
                        put("block", l.abbreviation)
                        put("code", l.key.code)
                        put("firstAt", iso(l.firstAtMillis))
                        l.firstOdometerKm?.let { put("firstOdometerKm", it) }
                        put("seenIn", l.seenIn)
                        put("readIn", l.readIn)
                        put("new", l.isNew)
                    })
                }
            })
            full?.let { c ->
                put("fullCharge", JSONObject().apply {
                    put("at", iso(c.atMillis))
                    c.soc?.let { put("soc", it) }
                    c.spreadMv?.let { put("spreadMv", it) }
                    c.minCell?.let { put("minCell", it) }
                })
            }
        }
        return ReportJournal(
            firstSeen = lives.associate { ReportCodeKey(it.key.address, it.key.code) to clock.day(it.firstAtMillis) },
            fullChargeSpreadMv = full?.spreadMv?.toDouble(),
            excerptJson = excerpt.toString(),
        )
    }

    /** The VIN and ECM's freeze frame where they were read; null with neither. */
    fun reads(model: DiagnosticsModel): ReportReads? {
        val vin = model.scan.vin?.vin?.takeIf { it.isNotBlank() }
        val frame = model.modules[ECM]?.obd?.freezeFrame?.takeIf { it.outcome == ReadOutcome.READ }
            ?.values?.map { ReportField(it.name, it.value) }?.takeIf { it.isNotEmpty() }
        val tests = model.modules[ECM]?.tests?.let(::tests)
        if (vin == null && frame == null && tests == null) return null
        return ReportReads(vin = vin, engineFreezeFrame = frame, engineTests = tests)
    }

    /** «Тесты» as read ([TestsView]) for the report; null before a read that listed monitors. */
    fun tests(v: TestsView): ReportTests? {
        if (v.state != ReadState.READ || v.groups.isEmpty()) return null
        return ReportTests(
            passed = v.passed,
            notRun = v.notRun,
            monitors = v.groups.sortedBy { it.mid }.map { g ->
                ReportTestMonitor(
                    mid = g.mid, name = g.name, word = g.word, exchange = g.raw,
                    tests = g.rows.map { ReportTestRow(it.tid, it.uasid, it.value, it.min, it.max, it.unit, it.pass, it.raw) },
                )
            },
        )
    }

    private const val ECM = 0x7E0
}
