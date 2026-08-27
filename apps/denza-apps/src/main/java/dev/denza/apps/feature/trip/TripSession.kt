package dev.denza.apps.feature.trip

import android.content.Context

/**
 * Process-scoped owner of the trip sensor hub (and therefore the [TripEngine]).
 *
 * First live drive finding: the trip used to reset every time the panel/activity
 * was recreated because [TripPanelView] owned the hub. The hub now lives for the
 * whole process; the view only attaches (start) and detaches (stop). Sensors and
 * GNSS still stop whenever the panel is not visible — battery discipline — so
 * gaps in the data are possible and honest, but the accumulated trip state
 * survives relaunches and elapsed time is wall-clock from the trip start.
 * Nothing is persisted to disk, as before: process death is the honest end of a
 * trip.
 *
 * Main-thread only, like the panel and all sensor callbacks.
 */
object TripSession {
    private var hub: TripSensorHub? = null

    fun hub(context: Context): TripSensorHub =
        hub ?: TripSensorHub(context.applicationContext).also { hub = it }

    /**
     * Хаб, если он уже есть. Сервисный отчёт спрашивает, а не создаёт: собрать хаб ради строчки
     * диагностики значило бы построить датчики, о которых отчёт как раз и докладывает.
     */
    fun existingHub(): TripSensorHub? = hub
}
