package dev.denza.apps.feature.diagnostics

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class DiagnosticBoardContractTest {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }.first { File(it,"tools/design-canvas/luminofor/diagnostics.json").exists() }
    @Test fun productGeometryIsTheNormativeBoard() {
        val board=File(root,"tools/design-canvas/luminofor/diagnostics.json").readText()
        assertEquals(board,File(root,"apps/denza-apps/src/main/assets/diagnostics/layout.json").readText())
        val layout=JSONObject(board);val tabs=layout.getJSONArray("tabs")
        assertEquals(8,tabs.length())
        for(i in 0 until tabs.length()) {
            val tab=tabs.getJSONObject(i);val nodes=tab.getJSONArray("nodes")
            assertTrue((0 until nodes.length()).any { nodes.getJSONObject(it).getString("id")==tab.getString("default") })
            for(j in 0 until nodes.length()) assertTrue(layout.getJSONObject("nodes").has(nodes.getJSONObject(j).getString("id")))
        }
    }
    @Test fun frozenVehicleValuesStayInDebugOnly() {
        val production=File(root,"apps/denza-apps/src/main/java/dev/denza/apps/feature/diagnostics").walkTopDown().filter { it.isFile }.joinToString("\n"){it.readText()}
        assertFalse(production.contains("516006"))
        assertFalse(production.contains("WebView"))
    }
    @Test fun evidenceBoardAndNativeModelRetainTheSameQualificationsAndNamespaces() {
        val fixtures = JSONObject(File(root,"apps/denza-apps/src/debug/assets/luminofor/fixtures.json").readText())
        for ((id, model) in mapOf("ecu_identity" to DiagnosticEvidence.identity,
            "ecu_inventory" to DiagnosticEvidence.inventory,"ecu_routes" to DiagnosticEvidence.routes)) {
            val n = fixtures.getJSONArray("main-diagnostics-routes").getJSONObject(1).getJSONObject("nodes").getJSONObject(id)
            assertEquals(model.value,n.getString("value"))
            assertEquals(model.caption,n.getString("caption"))
            val rows = n.getJSONArray("rows")
            assertEquals(model.rows,(0 until rows.length()).map { rows.getJSONArray(it).let { it.getString(0) to it.getString(1) } })
            val notes = n.getJSONArray("notes")
            assertEquals(model.notes,(0 until notes.length()).map { notes.getString(it) })
        }
    }
}
