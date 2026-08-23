package dev.denza.apps.feature.split

import android.content.SharedPreferences

/**
 * The single durable snapshot of the product (contract section 6) and the one-shot migration that
 * retires the keys of the previous generations.
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
 *
 * @param revision revision of the last fully completed operation.
 */
internal data class SplitDurable(
    val enabled: Boolean = false,
    val slots: Map<SplitPane, SplitSlot> = CLOSED_PANES,
    val revision: Long = 0L,
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
 * (picker) or `A:<package>`. Inside a package `\` becomes `\\` and `|` becomes `\p`, which makes
 * the round trip total for every string a package name could ever be. A task id has no encoding at
 * all - [SplitSlot] cannot express one (invariant 4). Everything else - another version, a missing
 * field, an unknown escape - is corruption rather than a state, and corruption resolves to
 * [SAFE_DEFAULT] without an exception: an unreadable snapshot must fail towards "disabled, nothing
 * remembered", which is failing towards U4.
 *
 * ### One-shot migration
 *
 * Absence of the key is the trigger, presence of the key is the stop condition, so the old keys are
 * read exactly once and are then removed by the very commit that writes the new snapshot. A
 * rejected commit leaves them in place on purpose: the next load migrates again instead of losing
 * the remembered pair.
 */
internal class PreferencesSplitStateStore(
    private val preferences: SharedPreferences,
) : SplitStateStore {

    override fun load(): SplitDurable {
        if (!preferences.contains(KEY_STATE)) return migrate()
        return decode(string(KEY_STATE)) ?: SAFE_DEFAULT
    }

    override fun commit(next: SplitDurable): Boolean =
        preferences.edit().putString(KEY_STATE, encode(next)).commit()

    /**
     * Converts the keys of the previous generations into one snapshot and deletes them.
     *
     * Only packages and kinds are read. The stored task ids are ignored by construction: there is
     * nowhere in [SplitDurable] to put them (invariant 4), so a reboot cannot make the product act
     * on someone else's numbers.
     */
    private fun migrate(): SplitDurable {
        val migrated = SplitDurable(
            enabled = boolean(LEGACY_ENABLED),
            slots = SplitPane.entries.associateWith(::migratedSlot),
            revision = 0L,
        )
        val editor = preferences.edit().putString(KEY_STATE, encode(migrated))
        LEGACY_KEYS.forEach(editor::remove)
        // A rejected commit is not an error to report but a migration to repeat: the old keys are
        // still there, so the next load reads the same pair again.
        editor.commit()
        return migrated
    }

    /**
     * The remembered pair, not the old automaton store, is the durable source of a selection: the
     * automaton cleared its slots on Home while the pair survived it, and the pair is what 1.3.2
     * promises to restore.
     */
    private fun migratedSlot(pane: SplitPane): SplitSlot {
        val remembered = string(lastPackageKey(pane))?.takeIf(String::isNotBlank)
        if (remembered != null) return SplitSlot.App(remembered)
        val kind = string("${statePrefix(pane)}_$LEGACY_KIND_SUFFIX")
        return if (kind == LEGACY_CLOSED_KIND) SplitSlot.Closed else SplitSlot.Picker
    }

    /** A value of another type is corruption too, and corruption never throws out of the store. */
    private fun string(key: String): String? =
        runCatching { preferences.getString(key, null) }.getOrNull()

    private fun boolean(key: String): Boolean =
        runCatching { preferences.getBoolean(key, false) }.getOrDefault(false)

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

        private const val LEGACY_ENABLED = "policy_enabled_v2"
        private const val LEGACY_PRESENT = "picker_state_present_v1"
        private const val LEGACY_ARMED = "picker_state_armed_v1"
        private const val LEGACY_PHASE = "picker_state_phase_v1"
        private const val LEGACY_KIND_SUFFIX = "kind"
        private const val LEGACY_CLOSED_KIND = "CLOSED"

        private val LEGACY_SLOT_SUFFIXES = listOf(
            LEGACY_KIND_SUFFIX,
            // Task ids. Listed only so that the migration deletes them; never read (invariant 4).
            "host",
            "app",
            "package",
            "projected_closed",
            "attach_attempts",
        )

        /**
         * Everything the migration removes, and nothing else. The lease keys
         * (`force_resizable_original`, `picker_gate_owned_v1`, `picker_access_*`) and the notice
         * are absent on purpose: they belong to stores this one does not own, and deleting them
         * would leak firmware-wide settings the product still has to restore.
         */
        private val LEGACY_KEYS: List<String> = buildList {
            add(LEGACY_ENABLED)
            add(LEGACY_PRESENT)
            add(LEGACY_ARMED)
            add(LEGACY_PHASE)
            SplitPane.entries.forEach { pane ->
                add(lastPackageKey(pane))
                LEGACY_SLOT_SUFFIXES.forEach { suffix -> add("${statePrefix(pane)}_$suffix") }
            }
        }

        /**
         * What an unreadable store resolves to, and what a device with no old keys migrates to:
         * the product is off and remembers no selection, so the first tap simply offers two
         * pickers (1.3.3).
         */
        val SAFE_DEFAULT = SplitDurable(
            enabled = false,
            slots = SplitPane.entries.associateWith { SplitSlot.Picker },
            revision = 0L,
        )

        fun encode(snapshot: SplitDurable): String = listOf(
            VERSION,
            if (snapshot.enabled) TRUE else FALSE,
            snapshot.revision.toString(),
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
            val revision = fields[2].toLongOrNull() ?: return null
            val primary = decodeSlot(fields[3]) ?: return null
            val secondary = decodeSlot(fields[4]) ?: return null
            return SplitDurable(
                enabled = enabled,
                slots = mapOf(SplitPane.PRIMARY to primary, SplitPane.SECONDARY to secondary),
                revision = revision,
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

        private fun statePrefix(pane: SplitPane): String =
            "picker_state_${pane.name.lowercase()}_v1"

        private fun lastPackageKey(pane: SplitPane): String =
            "picker_last_${pane.name.lowercase()}_package_v1"
    }
}
