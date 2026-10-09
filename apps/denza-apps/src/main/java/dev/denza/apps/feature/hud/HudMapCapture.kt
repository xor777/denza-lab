package dev.denza.apps.feature.hud

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The map window's frames, kept for tuning the cut, the cursor search and the key on real roads.
 *
 * Off unless a file says otherwise, as [dev.denza.apps.feature.vehicle.VehicleCapture] is: the host
 * creates the marker before a drive and deletes it after.
 *
 * ```text
 * adb shell run-as dev.denza.apps mkdir -p files/hud-map-capture
 * adb shell run-as dev.denza.apps touch files/hud-map-capture/ENABLED
 * ```
 *
 * While the marker stands, every [INTERVAL_MS] the frame thread writes the analysis image the
 * cursor was searched in and the frame the map window was sent, side by side by name, and keeps
 * the newest [MAX_FILES]. Without it nothing is written and the cost is one `File.exists()` every
 * [INTERVAL_MS]. The frames show where the owner drives: they are pulled for tuning and never
 * committed anywhere.
 *
 * Called from the frame thread alone, so nothing here is synchronised. A write that fails is
 * dropped: a recording nobody asked for must not cost the HUD a frame.
 */
internal class HudMapCapture(
    private val directory: File,
    /** A monotonic clock in milliseconds: the cadence. */
    private val nowMs: () -> Long,
    /** The wall clock in milliseconds: the files' names. */
    private val wallMs: () -> Long = System::currentTimeMillis,
) {
    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
    private var checkedAt = Long.MIN_VALUE
    private var enabled = false
    private var savedAt = Long.MIN_VALUE

    /** Whether this frame is one to keep: the marker stands and [INTERVAL_MS] passed since the last. */
    fun due(): Boolean {
        val now = nowMs()
        if (checkedAt == Long.MIN_VALUE || now - checkedAt >= INTERVAL_MS) {
            checkedAt = now
            enabled = File(directory, MARKER).isFile
        }
        return enabled && (savedAt == Long.MIN_VALUE || now - savedAt >= INTERVAL_MS)
    }

    /** Keeps one frame: [analysisPng] (null on a frame that was not searched) and [mapPng]. */
    fun save(analysisPng: ByteArray?, mapPng: ByteArray) {
        savedAt = nowMs()
        val name = "hud-map-${stamp.format(Date(wallMs()))}"
        try {
            analysisPng?.let { File(directory, "$name-analysis.png").writeBytes(it) }
            File(directory, "$name-map.png").writeBytes(mapPng)
            prune()
        } catch (_: java.io.IOException) {
            // Dropped, as the class says.
        } catch (_: SecurityException) {
        }
    }

    /** The oldest frames go first; the names sort by time. */
    private fun prune() {
        val frames = directory.listFiles { file -> file.name.startsWith("hud-map-") && file.name.endsWith(".png") }
            ?: return
        if (frames.size <= MAX_FILES) return
        frames.sortedBy(File::getName).take(frames.size - MAX_FILES).forEach(File::delete)
    }

    companion object {
        /** Under the app's files directory. */
        const val DIRECTORY = "hud-map-capture"
        const val MARKER = "ENABLED"
        const val INTERVAL_MS = 5_000L
        const val MAX_FILES = 200
    }
}
