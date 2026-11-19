package dev.denza.apps.feature.weather

/**
 * Whether a refresh may still touch the car.
 *
 * Every refresh and every re-arming of the alarm holds a [Licence] issued before it read the
 * switch. Switching weather off revokes every licence issued so far ([WeatherAdapterScheduler.cancel]),
 * so a refresh that was already waiting for a location fix, the geocoder or MET when the switch
 * went off writes nothing more, notifies nothing and re-arms nothing.
 *
 * Revoking and [Licence.runIfValid] share one lock: once [revoke] returns, no licensed block is
 * running and none will run. The provider write itself is only checked before it starts, not held
 * under the lock - the stock provider is another app's process, and the switch is pressed on the
 * main thread - so a write already inside the provider finishes, and nothing announces it.
 *
 * The service and the switch share the app's process (`WeatherProcessContractTest`), so one
 * in-memory fence is the whole of it; a process that dies takes its refresh with it.
 */
internal class WeatherRefreshFence {
    private val lock = Any()
    private var generation = 0L

    fun issue(): Licence = synchronized(lock) { Licence(generation) }

    fun revoke() {
        synchronized(lock) { generation++ }
    }

    inner class Licence internal constructor(private val issuedAt: Long) {
        val valid: Boolean get() = synchronized(lock) { generation == issuedAt }

        /** Runs [block] only if the switch has not gone off since this licence was issued. */
        fun runIfValid(block: () -> Unit): Boolean = synchronized(lock) {
            if (generation != issuedAt) return false
            block()
            true
        }
    }

    companion object {
        /** The app process's one fence. */
        val process = WeatherRefreshFence()
    }
}

/** What a refresh records for the tile; [WeatherAdapterState] on the car. */
internal interface WeatherRecord {
    fun attempt()

    /** The temperature the car now shows and the time it was handed over, written together. */
    fun delivered(temperature: Int, message: String)

    fun failed(failure: Exception)
}

/** A forecast ready for the car, and where its coordinates came from. */
internal class PreparedWeather(val weather: NativeWeather, val source: String)

/**
 * One refresh, start to end, with the car-facing steps supplied by [WeatherAdapterController].
 *
 * The tile's temperature and the time of the last success are recorded only after the provider
 * kept the payload and the widget was told: the tile reports what the car was handed, never a
 * forecast that failed on its way there.
 */
internal class WeatherRefresh(
    private val licence: WeatherRefreshFence.Licence,
    private val record: WeatherRecord,
) {
    enum class Outcome { DELIVERED, FAILED, WITHDRAWN }

    fun run(
        prepare: () -> PreparedWeather,
        deliver: (payload: String) -> Unit,
        announce: () -> Unit,
    ): Outcome {
        record.attempt()
        return try {
            val prepared = prepare()
            if (!licence.valid) return Outcome.WITHDRAWN
            deliver(prepared.weather.payload)
            val announced = licence.runIfValid {
                announce()
                record.delivered(
                    prepared.weather.temperature,
                    "updated native weather (${prepared.source})",
                )
            }
            if (announced) Outcome.DELIVERED else Outcome.WITHDRAWN
        } catch (failure: Exception) {
            record.failed(failure)
            Outcome.FAILED
        }
    }
}
