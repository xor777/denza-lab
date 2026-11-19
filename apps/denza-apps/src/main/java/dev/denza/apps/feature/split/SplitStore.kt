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
 * [SplitSlot.App] carries a package name only (invariant 4), and so does a [SplitPair] of the
 * [memory]. Operations, overlay leases, projection and hints are equally absent: they are not
 * durable facts.
 *
 * @param memory the pairs of 1.14, in the same snapshot as the slots (section 6, ред. 2026-11-11):
 * a second store beside the automaton's once already diverged from it (8.1).
 */
internal data class SplitDurable(
    val enabled: Boolean = false,
    val slots: Map<SplitPane, SplitSlot> = CLOSED_PANES,
    val memory: SplitMemory = SplitMemory(),
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
 * ### Wire format (`split_state_v3`, 2026-11-11)
 *
 * ```
 * 3|<enabled>|<primary>|<secondary>|<last>|<recent>|<pinned>|<package>|<package>|...
 * ```
 *
 * `<enabled>` is `1` or `0`, and a pane is `C` (closed), `P` (picker) or `A:<package>`. `<last>`,
 * `<recent>` and `<pinned>` count the pairs of the memory: `0` or `1` last pair, at most five
 * recent ones, any number of pins. The pairs follow in that order, last, recent (most recent first),
 * pinned (the user's order), each as two package fields, primary then secondary. Inside a package
 * `\` becomes `\\` and `|` becomes `\p`, which makes the round trip total for every string a
 * package name could ever be. A task id has no encoding at all - neither [SplitSlot] nor
 * [SplitPair] can express one (invariant 4).
 *
 * Everything else - another version, a missing or extra field, a count that does not match the
 * fields, an unknown escape, a memory that breaks its own rules ([SplitMemory.isWellFormed]) - is
 * corruption rather than a state, and corruption resolves to [SAFE_DEFAULT] without an exception:
 * an unreadable snapshot must fail towards "disabled, nothing remembered", which is failing towards
 * U4. An absent key - a fresh install - reads the same way and writes nothing.
 *
 * ### The snapshot before it
 *
 * Until 2026-11-11 the snapshot was `split_state_v2`, `2|<enabled>|<revision>|<primary>|<secondary>`
 * with a revision written `0` and checked as a number. While no `v3` exists [load] reads that key as
 * the toggle, the slots and an empty memory, and writes nothing: there is no migration step. The next
 * ordinary [commit] writes `v3` and removes the `v2` key in the same editor, so the store keeps one
 * key. A build from before 2026-11-11 installed over a `v3` snapshot finds no key it knows and starts
 * disabled with nothing remembered; what it then commits is a `v2` key beside the `v3` one. A `v2`
 * key standing beside `v3` therefore means an older build wrote later: [load] takes the toggle and
 * the slots from `v2` - the user's latest choice - and the memory from `v3`, which the older build
 * could not touch. A corrupt `v3` is still corruption ([SAFE_DEFAULT]) whatever stands beside it, and
 * an unreadable `v2` beside a good `v3` is ignored. The next commit leaves one key again.
 *
 * The keys of the generations before 2026-08-23 were converted by a one-shot migration on the first
 * load of every build from then on; every release since 0.6.0-alpha carried it, and it was removed on
 * 2026-10-09. Those keys are never read.
 *
 * ### A snapshot is published only once it is on disk
 *
 * Android's editor puts the new values into the process's map *before* it writes the file, and a
 * failed write leaves them there: the file keeps the old snapshot, and every reader in this process
 * would see the refused one - the `v3` value and the `v2` key it removed included - until the process
 * dies (ревью 2026-11-19, находка 6). So a refused [commit] puts back what both keys held before it,
 * by a second edit; whatever becomes of that edit's own write, the map is the file again, because the
 * failed write left the file alone. [load] and [commit] take one process-wide lock, so a load on
 * another thread - [SplitScreenSettings.isEnabled] reads through here - never sees the refused
 * snapshot in between.
 */
internal class PreferencesSplitStateStore(
    private val preferences: SharedPreferences,
) : SplitStateStore {

    override fun load(): SplitDurable = synchronized(PUBLICATION) { read() }

    private fun read(): SplitDurable {
        val current = string(KEY_STATE)
        val older = decodeV2(string(KEY_STATE_V2))
        if (current == null) return older ?: SAFE_DEFAULT
        val decoded = decode(current) ?: return SAFE_DEFAULT
        // An older build wrote after this one: its toggle and slots are the latest (see above).
        return older?.copy(memory = decoded.memory) ?: decoded
    }

    override fun commit(next: SplitDurable): Boolean = synchronized(PUBLICATION) {
        val before = SNAPSHOT_KEYS.associateWith(::raw)
        if (preferences.edit().putString(KEY_STATE, encode(next)).remove(KEY_STATE_V2).commit()) {
            return@synchronized true
        }
        val restore = preferences.edit()
        before.forEach { (key, value) -> restore.put(key, value) }
        // Its result is not the question: memory is put back whatever the file does, and the file
        // still holds what the failed write found there.
        restore.commit()
        false
    }

    /** Whatever the key holds, of whatever type, so that a refused commit can put it back as it was. */
    private fun raw(key: String): Any? = runCatching { preferences.all[key] }.getOrNull()

    private fun SharedPreferences.Editor.put(key: String, value: Any?) {
        when (value) {
            null -> remove(key)
            is String -> putString(key, value)
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
            else -> remove(key)
        }
    }

    /** A value of another type is corruption too, and corruption never throws out of the store. */
    private fun string(key: String): String? =
        runCatching { preferences.getString(key, null) }.getOrNull()

    internal companion object {
        const val KEY_STATE = "split_state_v3"

        /** The snapshot before the pair memory; read while no `v3` exists, removed by a commit. */
        const val KEY_STATE_V2 = "split_state_v2"

        private val SNAPSHOT_KEYS = listOf(KEY_STATE, KEY_STATE_V2)

        /** One per process: every store over the split preferences publishes through it. */
        private val PUBLICATION = Any()

        private const val VERSION = "3"
        private const val VERSION_V2 = "2"
        private const val FIELD_COUNT_V2 = 5
        private const val HEADER_FIELDS = 7
        private const val FIELD = '|'
        private const val ESCAPE = '\\'
        private const val ESCAPED_FIELD = 'p'
        private const val CLOSED = "C"
        private const val PICKER = "P"
        private const val APP_PREFIX = "A:"
        private const val TRUE = "1"
        private const val FALSE = "0"

        /**
         * What an absent or unreadable store resolves to: the product is off and remembers no
         * selection, so the first tap simply offers two pickers (1.3.3).
         */
        val SAFE_DEFAULT = SplitDurable(
            enabled = false,
            slots = SplitPane.entries.associateWith { SplitSlot.Picker },
        )

        fun encode(snapshot: SplitDurable): String {
            val memory = snapshot.memory
            val pairs = listOfNotNull(memory.last) + memory.recent + memory.pinned
            return (
                listOf(
                    VERSION,
                    if (snapshot.enabled) TRUE else FALSE,
                    encodeSlot(snapshot.slot(SplitPane.PRIMARY)),
                    encodeSlot(snapshot.slot(SplitPane.SECONDARY)),
                    (if (memory.last == null) 0 else 1).toString(),
                    memory.recent.size.toString(),
                    memory.pinned.size.toString(),
                ) + pairs.flatMap { pair -> listOf(escape(pair.primary), escape(pair.secondary)) }
                ).joinToString(FIELD.toString())
        }

        /** Reads a `v3` payload; anything else, a `v2` one included, is not one. */
        fun decode(payload: String?): SplitDurable? {
            val fields = fields(payload ?: return null)
            if (fields.size < HEADER_FIELDS || fields[0] != VERSION) return null
            val enabled = decodeEnabled(fields[1]) ?: return null
            val primary = decodeSlot(fields[2]) ?: return null
            val secondary = decodeSlot(fields[3]) ?: return null
            val lastCount = decodeCount(fields[4]) ?: return null
            val recentCount = decodeCount(fields[5]) ?: return null
            val pinnedCount = decodeCount(fields[6]) ?: return null
            if (lastCount > 1 || recentCount > SplitMemory.RECENT_LIMIT) return null
            val pairCount = lastCount.toLong() + recentCount + pinnedCount
            if (fields.size.toLong() != HEADER_FIELDS + 2 * pairCount) return null
            val pairs = fields.drop(HEADER_FIELDS).chunked(2).map { (first, second) ->
                SplitPair(unescape(first) ?: return null, unescape(second) ?: return null)
            }
            val memory = SplitMemory(
                last = pairs.firstOrNull()?.takeIf { lastCount == 1 },
                recent = pairs.drop(lastCount).take(recentCount),
                pinned = pairs.drop(lastCount + recentCount),
            )
            if (!memory.isWellFormed) return null
            return SplitDurable(
                enabled = enabled,
                slots = mapOf(SplitPane.PRIMARY to primary, SplitPane.SECONDARY to secondary),
                memory = memory,
            )
        }

        /**
         * Reads the snapshot written before 2026-11-11 as the toggle, the slots and an empty memory
         * (section 6, ред. 2026-11-11). Its rules are the old ones, word for word.
         */
        fun decodeV2(payload: String?): SplitDurable? {
            val fields = fields(payload ?: return null)
            if (fields.size != FIELD_COUNT_V2 || fields[0] != VERSION_V2) return null
            val enabled = decodeEnabled(fields[1]) ?: return null
            if (fields[2].toLongOrNull() == null) return null
            val primary = decodeSlot(fields[3]) ?: return null
            val secondary = decodeSlot(fields[4]) ?: return null
            return SplitDurable(
                enabled = enabled,
                slots = mapOf(SplitPane.PRIMARY to primary, SplitPane.SECONDARY to secondary),
            )
        }

        private fun decodeEnabled(field: String): Boolean? = when (field) {
            TRUE -> true
            FALSE -> false
            else -> null
        }

        /** A plain non-negative decimal: no sign, no padding the encoder never writes. */
        private fun decodeCount(field: String): Int? {
            if (field.isEmpty() || field.length > 4 || !field.all { it in '0'..'9' }) return null
            if (field.length > 1 && field[0] == '0') return null
            return field.toInt()
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
