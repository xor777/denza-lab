# Native weather adapter findings

The provider/write path was validated on 2026-08-14 against DiLink 5.1 and the
stock `com.byd.weatherdata` package version `2.9.36.260424`. Android Geocoder
city labels were added and locally build-verified on 2026-08-22; their
availability and returned fields still need a live-car check.

## Current state

Updated 2026-11-19. How Denza Apps feeds the car's own weather widget a forecast it can show in
Russia, and what the «Погода» tile reads back.

| Claim | Status | Since | Section |
|---|---|---|---|
| The stock record is one JSON row in `content://com.byd.weatherdata.utils.WeatherContentProvider/weather`; replacing it and nudging the launcher and widget refreshes them | live | 2026-08-14 | [Native contract](#native-contract) |
| MET Norway Locationforecast, mapped to the stock weather IDs, fills every native field but AQI | live | 2026-08-14 | [Product implementation](#product-implementation) |
| A run every ten minutes (`setAndAllowWhileIdle`, may be deferred while the car sleeps), plus on opening the stock weather app and on app start | code | 2026-08-14 | [Product implementation](#product-implementation) |
| The «Погода» tile switches the adapter; on by default, off cancels the alarm and nothing is fetched (`WeatherAdapterState`, `WeatherAdapterService`) | code | 2026-08-26 | [Product implementation](#product-implementation) |
| Switching off also stops a run already under way: it writes, announces, records and re-arms nothing more (`WeatherRefreshFence`, `WeatherRefresh`) | code | 2026-11-19 | [Four holes in the run](#four-holes-in-the-run-2026-11-19) |
| The tile's temperature is the one in the widget's record, recorded with the success time only after the provider kept it and the widget was told (`NativeWeather`, `WeatherAdapterState.recordDelivery`) | code | 2026-11-19 | [Four holes in the run](#four-holes-in-the-run-2026-11-19) |
| Every app start registers an alarm, so a reboot that discarded it is repaired (`WeatherAlarmPlan`); since the second review "every start" includes one whose local ADB is not trusted: the weather start runs ahead of the ADB gate (`DenzaAppRepository.startWithoutAdb`) | code | 2026-11-19 | [The second review](#the-second-review-2026-11-19) |
| Known limits: a run already past its last switch check can still write once after switch-off; a process killed between the provider's delete and insert leaves the widget empty; a 0.7.1 record can show a temperature the widget never got until the first delivery of the new build | code | 2026-11-19 | [The second review](#the-second-review-2026-11-19) |
| The cached forecast skips MET only between its save and its expiry; a clock set back sends the run to MET (`WeatherForecastCachePolicy.canServeWithoutRequest`) | code | 2026-11-19 | [Four holes in the run](#four-holes-in-the-run-2026-11-19) |
| The service runs in the app's own process, and the tile follows each run (`WeatherFeature.refresh`, `WeatherAdapterState.observe`; `DenzaAppRepository.refreshWeather` until 2026-10-09) | code | 2026-10-06 | [The tile read a stale temperature](#the-tile-read-a-stale-temperature-2026-10-06) |
| "The adapter runs in a short-lived `:weather` process": it did until 2026-10-06, and sharing preferences with the main process left the tile on the process start's temperature | refuted | 2026-10-06 | [The tile read a stale temperature](#the-tile-read-a-stale-temperature-2026-10-06) |
| The city label and the words written to the row follow the app's language, Russian or English (`AppText`; [The app's own language](system-language.md#the-apps-own-language)) | code | 2026-11-06 | [Product implementation](#product-implementation) |
| Android `Geocoder` city labels on the car | open | 2026-08-22 | [Boundaries](#boundaries) |

**Open questions**
- Does the DiLink `Geocoder` return a Russian locality? One run on the car with the label read back
  from the provider settles it.
- On the car: does the tile's temperature now change between runs, and does switching weather off
  stop the provider row from being rewritten? One evening with the tile and the widget side by side.

**Car acceptance** (from the 2026-11-19 review)
- After QuickBoot, after a full reboot within ten minutes of a run, and after an APK replacement:
  does the runtime start come up, is the weather alarm registered
  (`dumpsys alarm | grep -A3 WEATHER_REFRESH_ALARM`), and when does the first run land? The
  foreground start of `WeatherAdapterService` from the alarm must be admitted each time.
  Repeat the full reboot with the app's ADB key revoked: the alarm must still stand. A registered
  alarm is not a delivery; the provider row's `servertime` and the widget redrawing are.
- Switch weather off while a run waits on MET (airplane-slow network): the log says
  `refresh withdrawn`, the provider row keeps its previous `servertime`, and no
  `WEATHER_REFRESH_ALARM` is left in `dumpsys alarm`.
- Set the head unit's date back a day: the next run asks MET (a request in the log, not the
  cache), and the widget's released time is the run's.

## Contents
- [Problem](#problem) — the stock endpoint gives nothing usable in Russia.
- [Native contract](#native-contract) — the provider row and the two refresh paths.
- [Product implementation](#product-implementation) — what a run does.
- [Live proof](#live-proof) — the in-car path and the mutation probes.
- [The tile read a stale temperature](#the-tile-read-a-stale-temperature-2026-10-06) — one process
  for the service and the dashboard.
- [Four holes in the run](#four-holes-in-the-run-2026-11-19) — switch-off mid-run, the tile's
  temperature, a quick reboot, a clock set back.
- [The second review](#the-second-review-2026-11-19) — the alarm without ADB, and the known limits.
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

> **Superseded 2026-11-19:** a boot did not always repair it. A stored next time from the boot
> before that fell inside the new boot's window was taken as a standing alarm, and nothing was
> registered; every start now registers one. See
> [Four holes in the run](#four-holes-in-the-run-2026-11-19).

Each run:

1. Selects the newest standard Android last-known location. A fresh fix is used
   immediately; otherwise the adapter asks enabled providers for a current fix.
   Coordinates already stored by native weather are the final fallback.
2. Asks the Android 13 platform `Geocoder` for a city label in the app's language.
   It prefers locality, then district, then region, and keeps `GPS` if the
   platform service is absent, times out, or returns no usable name.
3. Requests MET Norway Locationforecast using an identifying User-Agent and
   `Proxy.NO_PROXY`.
4. Honors HTTP expiry/`Last-Modified` caching and permits a stale forecast for at
   most six hours after a network failure. The cache is used without a request
   only between its save time and its expiry (since 2026-11-19).
5. Maps MET symbols and wind data into the stock weather IDs and complete native
   JSON shape, and takes the tile's temperature from that same record.
6. Snapshots the existing native row, replaces it, and verifies an exact read
   back. A failed write restores the previous row.
7. Notifies the launcher and the stock widget provider, then records the
   temperature and the success time together for the tile.

A run holds a licence issued before it read the switch; switching off revokes it,
and the run stops before step 6 and before step 7, and does not re-arm the alarm.

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

> **Superseded 2026-11-19:** only after a network failure. The path that skips MET while the
> cache has not expired compared the expiry alone, so a clock set back served a cache of any age;
> see [Four holes in the run](#four-holes-in-the-run-2026-11-19).

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
preferences have one copy, every `DenzaAppRepository.refresh` reads the record (since
2026-10-08 as the weather slice of the state, `StateSlice.WEATHER`), and the runtime observes it
(`WeatherAdapterState.observe`) and marks that slice, so the tile follows each run as it is
recorded.
`WeatherProcessContractTest` holds both.

> **Superseded 2026-11-19:** one process fixed the copy of the switch, not the run already under
> way: it had read the switch once, at its start, and wrote, announced and re-armed whatever the
> switch did after. See [Four holes in the run](#four-holes-in-the-run-2026-11-19).

## Four holes in the run (2026-11-19)

Found by an outside review of the code, confirmed by reading it; none seen on the car yet.

**Switching off during a run.** The service read the switch once, before it fetched. Switching
off cancelled the alarm, but a run waiting for a location fix, the geocoder or MET still
replaced the provider row, told the widget, and in its `finally` armed the next alarm the switch
had just cancelled. Now the service takes a licence from `WeatherRefreshFence` before it reads
the switch, and `WeatherAdapterScheduler.cancel` revokes every licence before it cancels the
alarm. `WeatherRefresh` checks the licence before the provider write and does the announcement,
the record and the re-arm only under it; revoking waits for a licensed step already running. The
provider write itself is not held under the fence: the provider is another app's process and the
switch is pressed on the main thread, so a write already inside the provider finishes, and
nothing announces it. `WeatherRefreshTest` holds this.

**The tile's temperature.** The temperature was recorded before the provider write, from a second
pass over the forecast with its own reading of the clock. A refused write left the tile on the new
temperature under the previous success's time while the widget kept the old one; around the
midpoint between two hourly points the two passes could pick different points. Now
`NativeWeatherPayload.build` returns the record and its `condition.temperature` together
(`NativeWeather`), and `WeatherAdapterState.recordDelivery` writes the temperature and the success
time in one edit after the provider kept the record and the widget was told.
`NativeWeatherPayloadTest.theTilesTemperatureIsTheOneInTheWidgetsRecord` and
`WeatherRefreshTest.aRefusedProviderWriteLeavesTheTileOnWhatTheWidgetShows` hold it.

**A quick reboot.** The next run is stored as an elapsed time, which restarts at boot. A success at
three minutes of uptime stores thirteen; a reboot two minutes later finds thirteen ahead of its
thirty seconds and inside the accepted window, and the success too recent for a prompt run, so the
start registered nothing, and Android had dropped the alarm at the reboot. Now every start
registers the alarm `WeatherAlarmPlan.onStart` names - the stored time if it is inside the window,
otherwise a full interval, or a prompt run if the last success is stale or lies in the future.
Registering the same pending intent again replaces it, so this costs nothing when the alarm did
stand. `WeatherAlarmPlanTest` holds it.

> **Superseded 2026-11-19 (second review):** "every start" above meant every run of the ADB-gated
> startup pass. A reboot whose local ADB never became trusted ran no such pass and registered no
> alarm. The weather start now runs ahead of the gate; see
> [The second review](#the-second-review-2026-11-19).

**A clock set back.** The cache was used without a request while `now < expires`. With the clock
set back, every expiry is in the future, and a forecast of any age went out as this run's. Now the
cache is used without a request only while `saved <= now < expires`
(`WeatherForecastCachePolicy.canServeWithoutRequest`); a save in the future sends the run to MET
with `If-Modified-Since`, and a 304 moves the save time to now.
`WeatherForecastCachePolicyTest.aClockSetBackSendsTheRunToMetInsteadOfTheCache` holds it.

## The second review (2026-11-19)

Found by a second outside review of the code; one fixed, three kept as known limits by the owner's
rule that narrow races and rare failure combinations are documented, not fixed.

**The alarm without ADB (fixed).** `weather.start` - the alarm, the record's observer, the tile's
read - ran only in the startup pass that waits for local ADB to be trusted. Nothing in it needs
ADB: the alarm is the app's own, and the forecast goes to the stock provider under the app's UID.
A reboot with the app's key no longer trusted left Weather with no alarm until the driver opened
the stock weather app. Now `DenzaAppRepository.startWithoutAdb` starts it on every entry - a
process start and each attempt of the autoload cycle - before the gate is asked
(`WeatherProcessContractTest.weatherStartsAheadOfTheAdbGate`). The one step that does need ADB,
removing the lab spike's proxy, no longer fails a run: without ADB it waits for the next one.

**Known limits.**

| Scenario | What the driver may see | Car check |
|---|---|---|
| Weather switched off in the 350 ms a run waits for the stock service's observer, after the run's last switch check (finding 1) | The widget gets one forecast written just after the switch went off; nothing announces it, the tile says off, no alarm follows | Switch off right after `refresh started` in the log; read the row's `servertime` against the press |
| The process killed between the provider's delete and its insert - the language switch in «Сервис» restarts the app (finding 7) | The stock widget shows no weather until the next run writes the row; a head unit with no location permission whose only coordinates were that row cannot resolve a location until another source gives one | Switch the language right after `refresh started`; query the provider for `_id=0` after the restart |
| An install over 0.7.1 whose last run fetched a temperature the provider then refused (finding 8) | The tile shows that temperature while the widget shows the older one, until the first delivery of the new build (ten minutes, or longer while the provider keeps refusing) | Install over 0.7.1; compare the tile and the widget before the first run of the new build |

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
