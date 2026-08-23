package dev.denza.apps.feature.split

/**
 * The single durable snapshot of the product (contract section 6).
 *
 * One snapshot is one write: the split of state across an automaton store and a separate "last
 * pair" is exactly what made the two diverge (section 8.1), so there is one store and one commit.
 * The SharedPreferences implementation and the one-shot migration of the old keys arrive with the
 * coordinator; this file is the seam every operation is written against.
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
 * @param leases ownership and displaced original values of the global leases we took (gate,
 * resizeability, observer). Without them a rollback would leak firmware-wide settings.
 */
internal data class SplitDurable(
    val enabled: Boolean = false,
    val slots: Map<SplitPane, SplitSlot> = CLOSED_PANES,
    val revision: Long = 0L,
    val leases: Map<String, String?> = emptyMap(),
) {
    fun slot(pane: SplitPane): SplitSlot = slots[pane] ?: SplitSlot.Closed
}

/** Atomic durable storage: one snapshot in, one snapshot out, never a partial write (K9). */
internal interface SplitStateStore {
    fun load(): SplitDurable

    /** @return `false` when the snapshot did not land; the caller must treat it as a failure. */
    fun commit(next: SplitDurable): Boolean
}
