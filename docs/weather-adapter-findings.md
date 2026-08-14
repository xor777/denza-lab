# Native weather adapter findings

Validated on 2026-08-14 against DiLink 5.1 and the stock
`com.byd.weatherdata` package version `2.9.36.260424`.

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

The adapter is always enabled and runs in a short-lived `:weather` foreground
service. `AlarmManager` schedules the next run after ten minutes. Boot, package
replacement, opening the native weather UI, and launching Denza Apps also repair
or accelerate the schedule.

Each run:

1. Selects the newest standard Android last-known location. A fresh fix is used
   immediately; otherwise the adapter asks enabled providers for a current fix.
   Coordinates already stored by native weather are the final fallback.
2. Requests MET Norway Locationforecast using an identifying User-Agent and
   `Proxy.NO_PROXY`.
3. Honors HTTP expiry/`Last-Modified` caching and permits a stale forecast for at
   most six hours after a network failure.
4. Maps MET symbols and wind data into the stock weather IDs and complete native
   JSON shape.
5. Snapshots the existing native row, replaces it, and verifies an exact read
   back. A failed write restores the previous row.
6. Notifies the launcher and the stock widget provider.

The adapter intentionally leaves AQI unavailable because Locationforecast does
not provide air quality. The city label is currently `GPS`; reverse geocoding is
not required to keep temperature, condition, min/max, and hourly data live.

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

## Boundaries

- `setAndAllowWhileIdle` is intentionally used without exact-alarm permission;
  ten minutes is the requested cadence, but Android may defer it while the car is
  asleep or under idle policy.
- The native provider and refresh behavior are firmware contracts, not public
  Android APIs. Re-validate the schema and component names after a WeatherData or
  major firmware update.
- MET Norway is an external free service with attribution and caching
  requirements, not an availability SLA.
- A one-time migration cleanup recognizes a proxy owned by the earlier lab spike
  and removes only that exact value. The production refresh path never installs a
  proxy.
