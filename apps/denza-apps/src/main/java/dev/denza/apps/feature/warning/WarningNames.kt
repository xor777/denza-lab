package dev.denza.apps.feature.warning

import org.json.JSONObject

/**
 * What the car calls each warning id, in Russian where the translation has its text.
 *
 * The names are the car's own: `/system/bin/config/popup_nads.json`, the dictionary the cluster
 * draws its popups from, read from the car the app runs on rather than bundled, because the N9's
 * copy has not been matched to the Z9GT's. The bundled `warnings/popup_ru.tsv` translates its
 * texts, keyed by the Chinese text itself, so an id that means something else on another car
 * still gets its own text. A text the translation lacks stays Chinese; an id the dictionary lacks
 * has no name, and the page shows the bare code rather than a guess.
 */
internal class WarningNames(private val names: Map<Int, String>) {

    fun of(code: Int): String? = names[code]

    val size: Int get() = names.size

    companion object {
        val EMPTY = WarningNames(emptyMap())

        fun of(dictionaryJson: String?, translationTsv: String?): WarningNames {
            if (dictionaryJson == null) return EMPTY
            val russian = translations(translationTsv)
            val names = LinkedHashMap<Int, String>()
            runCatching {
                val entries = JSONObject(dictionaryJson).getJSONArray("dictionary")
                for (i in 0 until entries.length()) {
                    val entry = entries.optJSONObject(i) ?: continue
                    // `23-E` sits in the dictionary beside the hex ids and is no ten-bit value.
                    val code = entry.optString("id").toIntOrNull(16) ?: continue
                    val text = entry.optString("text").trim().takeIf { it.isNotEmpty() } ?: continue
                    names.putIfAbsent(code, russian[text] ?: text)
                }
            }
            return WarningNames(names)
        }

        /** `zh<TAB>ru` a line under a header of the same two words. */
        fun translations(tsv: String?): Map<String, String> {
            if (tsv == null) return emptyMap()
            val map = HashMap<String, String>()
            tsv.lineSequence().drop(1).forEach { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) return@forEach
                val ru = line.substring(tab + 1).trim()
                if (ru.isNotEmpty()) map[line.substring(0, tab)] = ru
            }
            return map
        }
    }
}
