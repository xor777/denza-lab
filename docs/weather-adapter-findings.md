# Native weather adapter findings

The provider/write path was validated on 2026-08-14 against DiLink 5.1 and the
stock `com.byd.weatherdata` package version `2.9.36.260424`. Android Geocoder
city labels were added and locally build-verified on 2026-08-22; their
availability and returned fields still need a live-car check.

## Current state

Updated 2026-10-06. How Denza Apps feeds the car's own weather widget a forecast it can show in
Russia, and what the «Погода» tile reads back.

| Claim | Status | Since | Section |
|---|---|---|---|
| The stock record is one JSON row in `content://com.byd.weatherdata.utils.WeatherContentProvider/weather`; replacing it and nudging the launcher and widget refreshes them | live | 2026-08-14 | [Native contract](#native-contract) |
| MET Norway Locationforecast, mapped to the stock weather IDs, fills every native field but AQI | live | 2026-08-14 | [Product implementation](#product-implementation) |
| A run every ten minutes (`setAndAllowWhileIdle`, may be deferred while the car sleeps), plus on opening the stock weather app and on app start | code | 2026-08-14 | [Product implementation](#product-implementation) |
| The «Погода» tile switches the adapter; on by default, off cancels the alarm and nothing is fetched (`WeatherAdapterState`, `WeatherAdapterService`) | code | 2026-08-26 | [Product implementation](#product-implementation) |
| The service runs in the app's own process, and the tile follows each run (`DenzaAppRepository.refreshWeather`, `WeatherAdapterState.observe`) | code | 2026-10-06 | [The tile read a stale temperature](#the-tile-read-a-stale-temperature-2026-10-06) |
| "The adapter runs in a short-lived `:weather` process": it did until 2026-10-06, and sharing preferences with the main process left the tile on the process start's temperature | refuted | 2026-10-06 | [The tile read a stale temperature](#the-tile-read-a-stale-temperature-2026-10-06) |
| Android `Geocoder` city labels on the car | open | 2026-08-22 | [Boundaries](#boundaries) |

**Open questions**
- Does the DiLink `Geocoder` return a Russian locality? One run on the car with the label read back
  from the provider settles it.
- On the car: does the tile's temperature now change between runs, and does switching weather off
  stop the provider row from being rewritten? One evening with the tile and the widget side by side.

## Contents
- [Problem](#problem) — the stock endpoint gives nothing usable in Russia.
- [Native contract](#native-contract) — the provider row and the two refresh paths.
- [Product implementation](#product-implementation) — what a run does.
- [Live proof](#live-proof) — the in-car path and the mutation probes.
- [The tile read a stale temperature](#the-tile-read-a-stale-temperature-2026-10-06) — one process
  for the service and the dashboard.
- [Boundaries](#boundaries) — what is not guaranteed.

## Problem

The stock weather application and home-screen widgets are functional, but their
production endpoint (`data-weather-cn.denzacloud.com`) does not return usable
weather for the tested Russian location. DNS and generic proxy changes do not fix
that application-level coverage limitation.

## Native contract

The firmware exposes the stock record through the exported provider:

```text
content://com.byd.weatherdata.utils.WeatherContentProvider/weather
```

The `name` column contains a JSON object with `resultcode`, `resultinfo`,
`servertime`, and a decoded `data` object. The latter contains the city, current
condition, daily and hourly forecasts, radar placeholder, alerts, and AQI fields.
The stock app replaces the single row with `_id=0` after a successful cloud
request.

Two refresh paths consume that record:

- `com.byd.weatherdata.action.THIRD_REFRESH` makes the launcher re-query it.
- The stock `RequestService` observes the system `time_12_24` URI and sends the
  protected `APPWIDGET_UPDATE` broadcast from the stock UID.

Denza Apps starts the exported stock service, sends the public launcher action,
and calls `ContentResolver.notifyChange()` for the observed URI. No clock setting
is changed. This lets stock code update its own protected widgets without trying
to forge a protected broadcast.

## Product implementation

The «Погода» tile switches the adapter; it is on by default, because it shipped
before it had a switch. A run is a foreground service in the app's own process
(until 2026-10-06 a separate `:weather` process; see
[The tile read a stale temperature](#the-tile-read-a-stale-temperature-2026-10-06)).
`AlarmManager` schedules the next run after ten minutes. Boot, package
replacement, opening the native weather UI, and launching Denza Apps also repair
or accelerate the schedule.

Each run:

1. Selects the newest standard Android last-known location. A fresh fix is used
   immediately; otherwise the adapter asks enabled providers for a current fix.
   Coordinates already stored by native weather are the final fallback.
2. Asks the Android 13 platform `Geocoder` for a Russian localized city label.
   It prefers locality, then district, then region, and keeps `GPS` if the
   platform service is absent, times out, or returns no usable name.
3. Requests MET Norway Locationforecast using an identifying User-Agent and
   `Proxy.NO_PROXY`.
4. Honors HTTP expiry/`Last-Modified` caching and permits a stale forecast for at
   most six hours after a network failure.
5. Maps MET symbols and wind data into the stock weather IDs and complete native
   JSON shape.
6. Snapshots the existing native row, replaces it, and verifies an exact read
   back. A failed write restores the previous row.
7. Notifies the launcher and the stock widget provider.

The stock `condition.updatetime` fields contain the actual adapter refresh time.
The hourly MET forecast-point timestamp is kept separately as forecast metadata;
using it as `updatetime` would make the native “data released” label appear stuck
on an exact hour between refreshes.

The adapter intentionally leaves AQI unavailable because Locationforecast does
not provide air quality. Reverse geocoding is best-effort and independent of
the forecast: a missing city result leaves the label as `GPS` without blocking
temperature, condition, min/max, or hourly data.

MET Norway usage references:

- <https://api.met.no/doc/TermsOfService>
- <https://api.met.no/doc/License>
- <https://api.met.no/weatherapi/locationforecast/2.0/documentation>

## Live proof

The final in-car path was exercised without a host proxy or Mac process:

```text
DenzaWeatherAdapter: updated native weather (gps last-known)
WeatherHelper: onReceive action=com.byd.weatherdata.action.THIRD_REFRESH
WeatherData_RequestService: TimeFormatChangeObserver::onChange
WeatherData_RequestService: TimeFormatChangeObserver=>sendWidgetBroadcast
WeatherData_WeatherWidgetProvider: onUpdate. appWidgetId=15
WeatherData_WeatherWidgetProvider: onUpdate. appWidgetId=30
```

The provider read-back reported `resultinfo="MET Norway adapter"`, GPS
coordinates, and the current temperature. `http_proxy` remained `null` after
normal, corrupt-provider, corrupt-cache, and concurrent-start probes.

Mutation probes also established that:

- replacing the provider row with malformed JSON is repaired on the next run;
- corrupting the local MET cache causes a clean network re-fetch and atomic cache
  replacement;
- two simultaneous refresh requests result in one refresh transaction;
- non-finite wind/direction values fail safe instead of selecting an extreme
  native icon or direction;
- stale-cache acceptance is bounded on both sides of a system-clock adjustment.

## The tile read a stale temperature (2026-10-06)

Found while mapping the tiles to their code, from the code alone; not yet seen on the car.

The service ran in a `:weather` process of its own and wrote the temperature and the time of the
last success into a SharedPreferences file, `weather_adapter_runtime`, that the main process
read and wrote too: the switch, and the alarm's next time at every alarm. SharedPreferences
caches a file per process and writes the whole cached copy back, so:

- the dashboard copied the temperature into its state only when the ADB runtime started, never
  on `refresh`, and the tile showed the temperature of that moment for the life of the process;
  the panel's «Отдано виджету … назад» counted from the same moment;
- even a fresh read in the main process would have read its own cached copy, and every alarm the
  main process handled wrote that copy back over the temperature `:weather` had just recorded;
- a `:weather` process still alive after the switch went off read the switch from its own copy
  as on, fetched, wrote the provider row and re-armed the alarm.

The separate process bought nothing: the alarm's receiver has always run in the main process,
so every run woke it anyway. Since 2026-10-06 the service runs in the app's own process, the
preferences have one copy, `DenzaAppRepository.refresh` reads the record, and the runtime
observes it (`WeatherAdapterState.observe`) so the tile follows each run as it is recorded.
`WeatherProcessContractTest` holds both.

## Boundaries

- `setAndAllowWhileIdle` is intentionally used without exact-alarm permission;
  ten minutes is the requested cadence, but Android may defer it while the car is
  asleep or under idle policy.
- The native provider and refresh behavior are firmware contracts, not public
  Android APIs. Re-validate the schema and component names after a WeatherData or
  major firmware update.
- MET Norway is an external free service with attribution and caching
  requirements, not an availability SLA.
- Android `Geocoder` availability, accuracy, and localization are platform
  best-effort behavior. The adapter bounds it with a five-second timeout and a
  `GPS` fallback, but the DiLink implementation has not yet been exercised on
  the car.
- A one-time migration cleanup recognizes a proxy owned by the earlier lab spike
  and removes only that exact value. The production refresh path never installs a
  proxy.
