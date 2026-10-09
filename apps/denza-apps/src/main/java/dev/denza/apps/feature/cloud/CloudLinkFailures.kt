package dev.denza.apps.feature.cloud

import dev.denza.apps.core.FeatureWords

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
    /** The driver's last press of the link's switch that the car did not take. */
    val press: CloudFailure? = null,
    /** The adapter's last pass of its own that did not complete. */
    val automatic: CloudFailure? = null,
    /**
     * The car's own «Wi-Fi во сне» setting not taken, for the report only: the panel's switch reads
     * the car's value back, which is the answer, and the link's tile has nothing to say about it.
     */
    val setting: String? = null,
) {
    /** A press on the link starts over: what the adapter tripped over before it is history. */
    fun pressStarted(): CloudLinkFailures = copy(automatic = null)

    /** The car took the press. */
    fun pressTaken(): CloudLinkFailures = copy(press = null)

    /** The car did not take the press. */
    fun pressRefused(failure: CloudFailure): CloudLinkFailures = copy(press = failure)

    /** The Wi-Fi setting was written and read back. */
    fun settingTaken(): CloudLinkFailures = copy(setting = null)

    /** The Wi-Fi setting was not taken. */
    fun settingRefused(detail: String): CloudLinkFailures = copy(setting = detail)

    /**
     * A pass of the adapter's own read the car and did all it planned.
     *
     * That answers any earlier pass. It answers a refused press only when the car got where the
     * press was going: the adapter carried out a write itself ([operated]), or the stock client is
     * connected ([connected]). A pass with nothing to send - a backoff still running - says nothing
     * about the press.
     */
    fun passCompleted(operated: Boolean, connected: Boolean): CloudLinkFailures = copy(
        press = press.takeUnless { operated || connected },
        automatic = null,
    )

    /** A pass of the adapter's own did not complete. */
    fun passFailed(failure: CloudFailure): CloudLinkFailures = copy(automatic = failure)

    /** The car confirmed an off - a press's, or the pending one a pass resumed: nothing is owed. */
    fun disableConfirmed(): CloudLinkFailures = copy(press = null, automatic = null)
}

/**
 * One failure the link keeps: which kind it is, which decides what the tile says, and the raw
 * reason, which only the report and the exported diagnostics carry.
 *
 * The tile used to print the reason itself - «Нет ответа: SocketTimeoutException», «Не прочитано с
 * машины: TCP, профиль, флаг APN1…», a bare «Check failed.» - while the boards and this tile's doc
 * said «Не включилось». The kinds are the words; the reason is what support reads.
 */
data class CloudFailure(val kind: Kind, val detail: String) {

    enum class Kind(val words: String) {
        /** A press to switch the link on that the car did not take. */
        ON_REFUSED(FeatureWords.refused(true)),

        /** A press to switch it off, or the off it owes since one. */
        OFF_REFUSED(FeatureWords.refused(false)),

        /** The adapter could not read the car: nothing it knows is fresh. */
        NOT_READ(CloudLinkStatus.STALE),

        /** The adapter read the car and could not carry out what it planned. */
        NOT_DONE(CloudLinkStatus.NO_LINK),
    }

    val words: String get() = kind.words

    /** The tile's words and the reason, for the report's «Отказ» row. */
    val report: String get() = "$words: $detail"

    companion object {
        fun refused(enabled: Boolean, detail: String) =
            CloudFailure(if (enabled) Kind.ON_REFUSED else Kind.OFF_REFUSED, detail)
    }
}
