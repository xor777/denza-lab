package dev.denza.apps.debug

import dev.denza.apps.feature.diagnostics.DiagnosticDtcPanel
import dev.denza.apps.feature.diagnostics.DiagnosticFrame
import dev.denza.apps.feature.diagnostics.DiagnosticNode
import dev.denza.apps.feature.diagnostics.DtcRecord
import org.json.JSONObject

internal object DiagnosticFixtures {
    fun frame(f: JSONObject): DiagnosticFrame {
        val nodes = f.getJSONObject("nodes")
        return DiagnosticFrame(f.getString("link"), f.getString("profile"), f.getString("park"),
            nodes.keys().asSequence().associateWith { key ->
                val n = nodes.getJSONObject(key)
                val rows = n.optJSONArray("rows")
                val notes = n.optJSONArray("notes")
                DiagnosticNode(n.getString("value"), n.getString("caption"), n.getString("tone"),
                    (0 until (rows?.length() ?: 0)).map { rows!!.getJSONArray(it).let { row -> row.getString(0) to row.getString(1) } },
                    (0 until (notes?.length() ?: 0)).map { notes!!.getString(it) })
            },
            (0 until f.getJSONArray("records").length()).map { f.getJSONArray("records").getJSONObject(it).let { r -> DtcRecord(r.getString("code"),r.getInt("status")) } },
            f.getBoolean("dtcRead"), f.getString("dtcTime"), f.getString("lampTime"), f.getString("message"), f.getBoolean("running"), f.optInt("availability", if(f.getBoolean("dtcRead"))255 else 0), 0,
            lbms = f.optJSONObject("lbms")?.let { b -> DiagnosticDtcPanel(
                (0 until b.getJSONArray("records").length()).map { b.getJSONArray("records").getJSONObject(it).let { r -> DtcRecord(r.getString("code"),r.getInt("status")) } },
                b.getString("dtcTime"), b.getInt("availability"), 0) })
    }
}
