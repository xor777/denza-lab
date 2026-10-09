package dev.denza.apps.feature.weather

import android.content.Context
import dev.denza.apps.core.SliceFeature
import dev.denza.apps.core.SliceHandle

/** What the «Погода» tile and its panel show: the switch, and what the car was last handed. */
data class WeatherSnapshot(
    val enabled: Boolean,
    /** The temperature of the last forecast written to the car, or null before the first. */
    val temperature: Int?,
    /** When that forecast was written ([System.currentTimeMillis]), or 0 before the first. */
    val updatedMillis: Long,
)

/**
 * The «Погода» tile's feature: the switch, and the adapter's own record read back for the tile.
 *
 * The forecast is fetched every ten minutes by [WeatherAdapterService], which writes the
 * temperature and the time of the last success as it goes. The tile reads them here, as its slice:
 * with every full read of the state, and - through [WeatherAdapterState.observe], which [start]
 * registers - after every run as it records. Until 2026-10-06 only the runtime start read them, so
 * the tile kept the temperature of the moment the process came up.
 *
 * Its code lived in `DenzaAppRepository` until 2026-10-09; it moved as it was.
 */
class WeatherFeature(
    private val slice: SliceHandle<WeatherSnapshot>,
    private val context: () -> Context?,
) : SliceFeature<WeatherSnapshot> {

    override fun read(context: Context): WeatherSnapshot = WeatherSnapshot(
        enabled = WeatherAdapterState.enabled(context),
        temperature = WeatherAdapterState.lastTemperature(context),
        updatedMillis = WeatherAdapterState.lastSuccessMillis(context),
    )

    /** The alarm stands, the record is watched, and the tile is read once now. */
    override fun start(context: Context) {
        WeatherAdapterScheduler.ensureScheduled(context)
        WeatherAdapterState.observe(context) { refresh() }
        refresh()
    }

    /**
     * Whether the car is fed weather at all.
     *
     * There is no coordinator behind this and no handshake to wait for: the adapter either has a
     * standing alarm or it does not, so the press is the whole of the operation and the state can
     * be reported the moment it is written.
     */
    fun setEnabled(enabled: Boolean) {
        val context = context() ?: return
        WeatherAdapterState.setEnabled(context, enabled)
        if (enabled) WeatherAdapterScheduler.ensureScheduled(context)
        else WeatherAdapterScheduler.cancel(context)
        slice.publish("weather switch") { weather -> weather.copy(enabled = enabled) }
    }

    /** What the car was last handed, read again from the adapter's record. */
    fun refresh() {
        slice.mark("weather")
    }
}
