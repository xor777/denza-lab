package dev.denza.apps.feature.split

import android.content.SharedPreferences

/**
 * The single durable snapshot of the product (contract section 6).
 *
 * One snapshot is one write: the split of state across an automaton store and a separate "last
 * pair" is exactly what made the two diverge (section 8.1), so there is one key, one commit and one
 * reader. Leases stay out of it on purpose - gate, resizeability and observer ownership keep their
 * own small stores, have no consistency relation to the slots, and folding them in here would tie
 * two independent atomicities together for nothing.
 */

private val CLOSED_PANES: Map<SplitPane, SplitSlot> =
    SplitPane.entries.associateWith { SplitSlot.Closed }

/**
 * Everything that survives the process and a reboot - and nothing else.
 *
 * Task and root ids cannot appear here by construction: a pane is a [SplitSlot], and
 * [SplitSlot.App] carries a package name only (invariant 4). Operations, overlay leases, projection
 * and hints are equally absent: they are not durable facts.
 */
internal data class SplitDurable(
    val enabled: Boolean = false,
    val slots: Map<SplitPane, SplitSlot> = CLOSED_PANES,
) {
    fun slot(pane: SplitPane): SplitSlot = slots[pane] ?: SplitSlot.Closed
}

/** Atomic durable storage: one snapshot in, one snapshot out, never a partial write (K9). */
internal interface SplitStateStore {
    fun load(): SplitDurable

    /** @return `false` when the snapshot did not land; the caller must treat it as a failure. */
    fun commit(next: SplitDurable): Boolean
}

/**
 * The preferences-backed store: one key, one editor, one `commit()` per write.
 *
 * ### Wire format
 *
 * ```
 * 2|<enabled>|<revision>|<primary>|<secondary>
 * ```
 *
 * `<enabled>` is `1` or `0`, `<revision>` a decimal `Long`, and a pane is `C` (closed), `P`
 * (picker) or `A:<package>`. The revision counted completed operations until 2026-10-09, when it
 * was found written on every commit and never read; it is written `0` now and checked as a number
 * on load, so a snapshot from either side of that change reads on the other. Inside a package `\` becomes `\\` and `|` becomes `\p`, which makes
 * the round trip total for every string a package name could ever be. A task id has no encoding at
 * all - [SplitSlot] cannot express one (invariant 4). Everything else - another version, a missing
 * field, an unknown escape - is corruption rather than a state, and corruption resolves to
 * [SAFE_DEFAULT] without an exception: an unreadable snapshot must fail towards "disabled, nothing
 * remembered", which is failing towards U4. An absent key - a fresh install - reads the same way and
 * writes nothing.
 *
 * The keys of the generations before 2026-08-23 were converted by a one-shot migration on the first
 * load of every build from then on; every release since 0.6.0-alpha carried it, and it was removed on
 * 2026-10-09. Those keys are never read.
 */
internal class PreferencesSplitStateStore(
    private val preferences: SharedPreferences,
) : SplitStateStore {

    override fun load(): SplitDurable = decode(string(KEY_STATE)) ?: SAFE_DEFAULT

    override fun commit(next: SplitDurable): Boolean =
        preferences.edit().putString(KEY_STATE, encode(next)).commit()

    /** A value of another type is corruption too, and corruption never throws out of the store. */
    private fun string(key: String): String? =
        runCatching { preferences.getString(key, null) }.getOrNull()

    internal companion object {
        const val KEY_STATE = "split_state_v2"

        private const val VERSION = "2"
        private const val FIELD_COUNT = 5
        private const val FIELD = '|'
        private const val ESCAPE = '\\'
        private const val ESCAPED_FIELD = 'p'
        private const val CLOSED = "C"
        private const val PICKER = "P"
        private const val APP_PREFIX = "A:"
        private const val TRUE = "1"
        private const val FALSE = "0"
        private const val REVISION = "0"

        /**
         * What an absent or unreadable store resolves to: the product is off and remembers no
         * selection, so the first tap simply offers two pickers (1.3.3).
         */
        val SAFE_DEFAULT = SplitDurable(
            enabled = false,
            slots = SplitPane.entries.associateWith { SplitSlot.Picker },
        )

        fun encode(snapshot: SplitDurable): String = listOf(
            VERSION,
            if (snapshot.enabled) TRUE else FALSE,
            REVISION,
            encodeSlot(snapshot.slot(SplitPane.PRIMARY)),
            encodeSlot(snapshot.slot(SplitPane.SECONDARY)),
        ).joinToString(FIELD.toString())

        fun decode(payload: String?): SplitDurable? {
            val fields = fields(payload ?: return null)
            if (fields.size != FIELD_COUNT || fields[0] != VERSION) return null
            val enabled = when (fields[1]) {
                TRUE -> true
                FALSE -> false
                else -> return null
            }
            if (fields[2].toLongOrNull() == null) return null
            val primary = decodeSlot(fields[3]) ?: return null
            val secondary = decodeSlot(fields[4]) ?: return null
            return SplitDurable(
                enabled = enabled,
                slots = mapOf(SplitPane.PRIMARY to primary, SplitPane.SECONDARY to secondary),
            )
        }

        private fun encodeSlot(slot: SplitSlot): String = when (slot) {
            SplitSlot.Closed -> CLOSED
            SplitSlot.Picker -> PICKER
            is SplitSlot.App -> APP_PREFIX + escape(slot.packageName)
        }

        private fun decodeSlot(field: String): SplitSlot? = when {
            field == CLOSED -> SplitSlot.Closed
            field == PICKER -> SplitSlot.Picker
            field.startsWith(APP_PREFIX) ->
                unescape(field.removePrefix(APP_PREFIX))?.let(SplitSlot::App)
            else -> null
        }

        private fun escape(raw: String): String {
            val escaped = StringBuilder(raw.length)
            raw.forEach { character ->
                when (character) {
                    ESCAPE -> escaped.append(ESCAPE).append(ESCAPE)
                    FIELD -> escaped.append(ESCAPE).append(ESCAPED_FIELD)
                    else -> escaped.append(character)
                }
            }
            return escaped.toString()
        }

        private fun unescape(raw: String): String? {
            val plain = StringBuilder(raw.length)
            var pending = false
            raw.forEach { character ->
                if (!pending) {
                    if (character == ESCAPE) pending = true else plain.append(character)
                    return@forEach
                }
                when (character) {
                    ESCAPE -> plain.append(ESCAPE)
                    ESCAPED_FIELD -> plain.append(FIELD)
                    // An escape we never write: the payload was not written by this format.
                    else -> return null
                }
                pending = false
            }
            return if (pending) null else plain.toString()
        }

        /** Splits on unescaped separators only, leaving each field escaped for [unescape]. */
        private fun fields(payload: String): List<String> {
            val fields = mutableListOf<String>()
            val field = StringBuilder()
            var pending = false
            payload.forEach { character ->
                when {
                    pending -> {
                        field.append(ESCAPE).append(character)
                        pending = false
                    }
                    character == ESCAPE -> pending = true
                    character == FIELD -> {
                        fields += field.toString()
                        field.clear()
                    }
                    else -> field.append(character)
                }
            }
            // A dangling escape is kept so that unescape rejects the field instead of guessing.
            if (pending) field.append(ESCAPE)
            fields += field.toString()
            return fields
        }
    }
}
