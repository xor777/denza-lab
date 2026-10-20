package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.protocol.OemDtcIndex
import java.io.InputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * The public DTC index and its Russian titles as the APK carries them: the two research files
 * themselves, copied byte for byte by the build (`OemDtcAssets` task in `build.gradle.kts`) into
 * `assets/diagnostics/`. There is no second copy to keep in step: the research JSON is the one source,
 * and `OemDtcReferenceTest` reads the same files through [parse].
 */
internal object OemDtcAssets {
    const val INDEX = "oem-dtc-public-index-20261020.json"
    const val TRANSLATION = "oem-dtc-public-index-ru-20261020.json"
    const val DIRECTORY = "diagnostics"

    /** Both files from [open] (an asset path); a file that is missing or does not join is no catalog. */
    fun load(open: (String) -> InputStream, log: (String) -> Unit): OemDtcIndex = try {
        val read = { name: String -> open("$DIRECTORY/$name").use { it.readBytes().toString(Charsets.UTF_8) } }
        parse(read(INDEX), read(TRANSLATION))
    } catch (error: Exception) {
        log("dtc catalog not loaded: ${error.javaClass.simpleName} ${error.message ?: ""}")
        OemDtcIndex.EMPTY
    }

    fun parse(index: String, translation: String): OemDtcIndex =
        OemDtcIndex.join(rows(JSONObject(index)), rows(JSONObject(translation)))

    private fun rows(file: JSONObject): List<List<String>> {
        val records = file.getJSONArray("records")
        return (0 until records.length()).map { i ->
            val row: JSONArray = records.getJSONArray(i)
            (0 until row.length()).map(row::getString)
        }
    }
}
