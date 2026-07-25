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

    // These explicit transactions are the persisted boundary read by runtime recovery.
    @SuppressLint("UseKtx")
    fun setObserved(context: Context, side: MirrorSide?, details: String) {
        prefs(context).edit().apply {
            if (side == null) remove(OBSERVED_SIDE) else putString(OBSERVED_SIDE, side.name)
            putString(STATUS_DETAILS, details)
        }.apply()
    }

    fun statusDetails(context: Context): String =
        prefs(context).getString(STATUS_DETAILS, "").orEmpty()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
