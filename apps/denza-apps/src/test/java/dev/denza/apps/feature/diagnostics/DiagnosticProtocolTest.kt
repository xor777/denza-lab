package dev.denza.apps.feature.diagnostics

import dev.denza.apps.feature.vehicle.VehicleAccess
import dev.denza.apps.feature.vehicle.VehicleSignal
import dev.denza.apps.feature.vehicle.VehicleTelemetry
import org.junit.Assert.*
import org.junit.Test

class DiagnosticProtocolTest {
    private fun response(data: String, address: String = "0728", result: Int = 1, total: Int = 1, tail: String = ""): String {
        val raw = "0A$address" + "%02X%02X01%02X".format(data.length/2,total,result) + data + tail
        return "TRANSPORT_RESULT 0\nFRAME phase=response raw=$raw\nEND WINDOW_ENDED frames=1\nUNREGISTERED\n"
    }
    private val count = response("5901FF000008")
    private val listed = response("5902FF51600628C29D8728C1218728A1480228A2500028A25B0028C2298728C22A8728")
    private fun reject(block: () -> Unit) { assertThrows(DiagnosticFailure::class.java) { block() } }
    @Test fun recordedSnapshotKeepsEightStoredIndependentFromCurrent() {
        val s=DtcProtocol.decode(count,listed,123)
        assertEquals(8,s.records.size); assertEquals(123L,s.atMillis); assertEquals(255,s.availability)
        assertEquals("C12187",s.records[2].code)
        assertTrue(s.records.all { it.confirmed && !it.active && !it.pending && !it.warning })
    }
    @Test fun duplicateCallbacksAndUnusedBufferTailDoNotCreateExtraRecords() {
        val padded=response("5902FF51600628C29D8728C1218728A1480228A2500028A25B0028C2298728C22A8728",tail="32"+"FF".repeat(197))
        assertEquals(8,DtcProtocol.decode(count+count,padded+padded,1).records.size)
    }
    @Test fun aCompleteExplicitZeroIsValid() {
        assertTrue(DtcProtocol.decode(response("5901FF000000"),response("5902FF"),1).records.isEmpty())
    }
    @Test fun lbmsExplicitZeroUsesItsOwnAddressAndLimitedStatusMask() {
        val c=response("590109000000",address="076A");val l=response("590209",address="076A")
        val s=DtcProtocol.decode(c,l,456,DtcEcu.LBMS)
        assertTrue(s.records.isEmpty());assertEquals(9,s.availability);assertEquals(456L,s.atMillis)
        reject { DtcProtocol.decode(c,l,456,DtcEcu.BODY) }
        reject { DtcProtocol.decode(count,listed,456,DtcEcu.LBMS) }
        reject { DtcProtocol.decode(c,response("590209",address="0728"),456,DtcEcu.LBMS) }
    }
    @Test fun unsupportedStatusBitNeverLeaksIntoLbmsCounts() {
        reject { DtcProtocol.decode(response("590109000001",address="076A"),response("59020912345604",address="076A"),1,DtcEcu.LBMS) }
    }
    @Test fun noAnswerIsNeverZero() { reject { DtcProtocol.decode("", "",1) } }
    @Test fun truncatedFrameIsRejected() { reject { DtcProtocol.decode(count,listed.replace("C22A8728","C22A87"),1) } }
    @Test fun otherEcuIsRejected() { reject { DtcProtocol.payload(response("5902FF",address="074F"),2) } }
    @Test fun unsupportedMultiFrameIsRejected() { reject { DtcProtocol.payload(response("5902FF",total=2),2) } }
    @Test fun ecuRejectionIsNotEmptyMemory() { reject { DtcProtocol.payload(response("7F1922",result=2),2) } }
    @Test fun timedOutHelperIsNotACompleteSnapshot() { reject { DtcProtocol.decode(count,listed.replace("END WINDOW_ENDED","HARD_TIMEOUT"),1) } }
    @Test fun failedCleanupDoesNotPublishSnapshot() { reject { DtcProtocol.decode(count,listed.replace("UNREGISTERED","UNREGISTER_ERROR"),1) } }
    @Test fun changedMemoryNeedsAnotherRead() { reject { DtcProtocol.decode(count.replace("000008","000009"),listed,1) } }
    @Test fun conflictingResponsesFailClosed() { reject { DtcProtocol.payload(listed+response("5902FF"),2) } }
    @Test fun statusBitsCanOverlap() { val r=DtcRecord("ABC123",0x8D);assertTrue(r.active&&r.pending&&r.confirmed&&r.warning) }
    @Test fun notTestedIsNotAFailure() { val r=DtcRecord("ABC123",0x50);assertFalse(r.active||r.pending||r.confirmed||r.warning) }
    private fun parcel(hex:String)="Result: Parcel(00000000 $hex   '........')"
    @Test fun lampZeroInvalidIsNotLampOff() {
        val s=DiagnosticSignals.indicators("@@0\n${parcel("00000000")}\n@@1\n${parcel("00000002")}\n@@2\n${parcel("00000001")}",10)
        assertFalse(s.values.containsKey(DiagnosticLamp.ENGINE));assertEquals("ВЫКЛ",s.label(DiagnosticLamp.ABS));assertEquals("ВКЛ",s.label(DiagnosticLamp.ESP))
    }
    @Test fun preflightNeedsExactProfileParkAndSpeed() {
        DiagnosticSignals.requireParked("170",parcel("00000001"),parcel("00000000"))
        reject { DiagnosticSignals.requireParked("168",parcel("00000001"),parcel("00000000")) }
        reject { DiagnosticSignals.requireParked("170",parcel("00000000"),parcel("00000000")) }
        reject { DiagnosticSignals.requireParked("170",parcel("00000001"),parcel("3f800000")) }
        reject { DiagnosticSignals.requireParked("170",parcel("00000001"),parcel("7fc00000")) }
        reject { DiagnosticSignals.requireParked("170",parcel("00000001"),"") }
    }
    @Test fun sameSnapshotDoesNotRefreshItsAge() {
        val c=DiagnosticTelemetryCache();val t=VehicleTelemetry(access=VehicleAccess.READY,values=mapOf(VehicleSignal.PACK_VOLT to 624.0,VehicleSignal.PACK_TEMP_AVG to 25.0))
        assertEquals(2,c.read(t,0).values.size)
        assertNull(c.read(t,2000)[VehicleSignal.PACK_VOLT]);assertEquals(25.0,c.read(t,2000)[VehicleSignal.PACK_TEMP_AVG])
        assertTrue(c.read(t,25000).values.isEmpty())
    }
    @Test fun droppedSweepCannotRejuvenateColdValue() {
        val c=DiagnosticTelemetryCache();val t=VehicleTelemetry(access=VehicleAccess.READY,values=mapOf(VehicleSignal.PACK_TEMP_AVG to 25.0))
        c.read(t,0);assertTrue(c.read(t.copy(dropped=true),25000).values.isEmpty())
    }
    @Test fun lossOfAccessRemovesValuesAtOnce() {
        val c=DiagnosticTelemetryCache();val t=VehicleTelemetry(access=VehicleAccess.READY,values=mapOf(VehicleSignal.PACK_VOLT to 624.0))
        c.read(t,0);assertTrue(c.read(t.copy(access=VehicleAccess.UNAVAILABLE),10).values.isEmpty())
    }
    @Test fun retainedDtcNeverClaimsFreshTelemetry() {
        val f=DiagnosticFrameBuilder().build(VehicleTelemetry(),DiagnosticStatus(dtc=DtcProtocol.decode(count,listed,1),message="ECU не ответил вовремя"))
        assertEquals("—",f.nodes.getValue("pack").value);assertEquals(8,f.records.size);assertTrue(f.message.contains("не ответил"));assertTrue(f.dtcRead)
    }
}
