package dev.denza.apps.feature.mirrors

import android.annotation.SuppressLint
import android.content.Context

enum class MirrorsPosition { SIDES, CENTER }
enum class MirrorSide { LEFT, RIGHT }

data class MirrorCameraConfig(
    val side: MirrorSide,
    val position: MirrorsPosition,
    val processingEnabled: Boolean,
)

object MirrorsSettings {
    private const val PREFS = "mirrors"
    private const val ENABLED = "enabled"
    private const val POSITION = "position"
    private const val PROCESSING = "processing"
    private const val OBSERVED_SIDE = "observed_side"
    private const val STATUS_DETAILS = "status_details"
    private const val UNREADABLE = "monitor_unreadable"
    private const val STOCK_CHOICE_BEFORE = "stock_turn_camera_before"

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(ENABLED, false)

    @SuppressLint("UseKtx")
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(ENABLED, enabled).apply()
    }

    fun position(context: Context): MirrorsPosition = runCatching {
        MirrorsPosition.valueOf(
            prefs(context).getString(POSITION, MirrorsPosition.SIDES.name)
                ?: MirrorsPosition.SIDES.name,
        )
    }.getOrDefault(MirrorsPosition.SIDES)

    @SuppressLint("UseKtx")
    fun setPosition(context: Context, position: MirrorsPosition) {
        prefs(context).edit().putString(POSITION, position.name).apply()
    }

    fun processingEnabled(context: Context): Boolean = prefs(context).getBoolean(PROCESSING, true)

    @SuppressLint("UseKtx")
    fun setProcessingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(PROCESSING, enabled).apply()
    }

    fun observedSide(context: Context): MirrorSide? = prefs(context)
        .getString(OBSERVED_SIDE, null)
        ?.let { runCatching { MirrorSide.valueOf(it) }.getOrNull() }

    /**
     * The running monitor has not read the car for [MirrorObservationWatch.DEADLINE_MS]: the
     * camera display may be there, but nothing the monitor shows follows the car any more.
     */
    fun monitorUnreadable(context: Context): Boolean = prefs(context).getBoolean(UNREADABLE, false)

    // These explicit transactions are the persisted boundary read by runtime recovery.
    @SuppressLint("UseKtx")
    fun setObserved(context: Context, side: MirrorSide?, details: String, unreadable: Boolean = false) {
        prefs(context).edit().apply {
            if (side == null) remove(OBSERVED_SIDE) else putString(OBSERVED_SIDE, side.name)
            putString(STATUS_DETAILS, details)
            if (unreadable) putBoolean(UNREADABLE, true) else remove(UNREADABLE)
        }.apply()
    }

    /** The stock turn-camera choice the owner had before Mirrors changed it, if they did. */
    fun stockChoiceBefore(context: Context): AvcTurnCameraChoice? =
        AvcTurnCameraChoice.fromWire(prefs(context).getInt(STOCK_CHOICE_BEFORE, -1))

    /**
     * Written to disk before it returns (true when it got there): the monitor stores the owner's
     * choice before it asks AVC to change it, so a process that dies right after still has it.
     * Never called on the main thread.
     */
    @SuppressLint("UseKtx", "ApplySharedPref")
    fun setStockChoiceBefore(context: Context, choice: AvcTurnCameraChoice?): Boolean =
        prefs(context).edit().apply {
            if (choice == null) remove(STOCK_CHOICE_BEFORE) else putInt(STOCK_CHOICE_BEFORE, choice.wire)
        }.commit()

    fun statusDetails(context: Context): String =
        prefs(context).getString(STATUS_DETAILS, "").orEmpty()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
