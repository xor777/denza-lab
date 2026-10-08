package dev.denza.apps.feature.cloud

/**
 * What went wrong with the link, kept apart by who asked for the thing that failed.
 *
 * A press is the driver's. When the car does not take it, the tile says so until the driver asks
 * again or the car gets where the press was going. A pass is the adapter's own - the periodic
 * reading, the stock client's status broadcast, a follow-up after «ready», the network coming or
 * going - and its failure answers itself: the next pass that reads the car and carries out what
 * it planned is the proof that the car is talking again.
 *
 * They were one field. A pass that tripped over a single ADB timeout wrote its failure into the
 * press's slot, and only a pass with something to send, or a connected client, cleared it. A car
 * on Wi-Fi waiting out its 5-60 minute backoff - the ordinary state of a car whose registration is
 * refused - therefore wore a coral tile until the next «ready», although the very next reading had
 * come back fine. Lasting trouble has its own words further down [CloudLinkStatus.snapshot] (a
 * stale reading, a stalled link, a refused registration), so nothing is hidden by letting a pass's
 * failure go with the next pass that worked.
 *
 * Immutable, and replaced whole on the controller's one thread, so the screen never pairs a press
 * from one moment with a pass from another. Both go to the service report.
 */
internal data class CloudLinkFailures(
    /** The driver's last press that the car did not take, in the tile's words. */
    val press: String? = null,
    /** The adapter's last pass of its own that did not complete, in the same words. */
    val automatic: String? = null,
) {
    /** A press on the link starts over: what the adapter tripped over before it is history. */
    fun pressStarted(): CloudLinkFailures = copy(automatic = null)

    /** The car took the press. */
    fun pressTaken(): CloudLinkFailures = copy(press = null)

    /** The car did not take the press. */
    fun pressRefused(message: String): CloudLinkFailures = copy(press = message)

    /**
     * A pass of the adapter's own read the car and did all it planned.
     *
     * That answers any earlier pass. It answers a refused press only when the car got where the
     * press was going: the adapter carried out a write itself ([operated]), or the stock client is
     * connected ([connected]). A pass with nothing to send - a backoff still running - says nothing
     * about the press.
     */
    fun passCompleted(operated: Boolean, connected: Boolean): CloudLinkFailures = CloudLinkFailures(
        press = press.takeUnless { operated || connected },
        automatic = null,
    )

    /** A pass of the adapter's own did not complete. */
    fun passFailed(message: String): CloudLinkFailures = copy(automatic = message)

    /** The car confirmed an off - a press's, or the pending one a pass resumed: nothing is owed. */
    fun disableConfirmed(): CloudLinkFailures = CloudLinkFailures()
}
