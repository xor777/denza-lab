# Stock map findings

What the car's own map is, what data it can hold, and why none of that data can
be Russia. Established 2026-09-22 corpus-first and then confirmed on the car.

## What it is

The stock map is AutoNavi's (高德 / Amap) AutoSDK, packaged by BYD.

| Fact | Value |
| --- | --- |
| Package | `com.byd.launchermap`, `/system/app/BydLaunchermap/BydLaunchermap.apk` |
| Size | 569 603 317 bytes, 26 dex files, `targetSdk` 30 |
| Version | `V3.12.837.10.810.1.202606231740.6`, `versionCode` 837 |
| Engine | `assets/GblBranchCommitInfo.txt`: `BLVersion 9.810.33727.0.2269`, every GBL component on `release/810` |
| Engine binaries | `libGbl.so` (62 MB), `libAutoDice.so` (62 MB), `libAmapFreeType.so`, `libGNet.so`, `libGEhp.so` |
| Render assets | `assets/blRes/{MapAsset,3DMapAsset,ExtraMapAsset,LayerAsset}` — Amap's own style/data format |
| Activity | `com.byd.automap.activity.MainActivity`, plus `com.byd.automap.meter.MeterTbtActivity` for the cluster card |
| Scheme | `bydautomap://` with ~45 authorities (`navi`, `route`, `offline_data`, `citySelect`, `openUsbguide`, …) |

Around it: `com.example.amapservice` (`/system/priv-app/AmapService`, system UID,
`1.5.13.3.2605201100`), `com.byd.maphelper`, and `com.baidu.mapauto.pass`
(`BydMapAccount`, account plumbing only). `settings get global byd_map_package`
is `com.byd.launchermap`; that key selects which package the firmware treats as
"the map" and is not a data path — see
[shortcuts-automation-findings.md](shortcuts-automation-findings.md).

## What it draws in Russia

Opened on the car on 2026-09-22 (parked, charging, Moscow):

- The GNSS fix is fine. The car arrow, its heading and the north rose are drawn,
  the 高德地图 watermark and the 100 m scale bar are drawn.
- Everything else is empty. No road, no label, no water, no landuse — the base
  canvas at 100 m scale is blank.

So the engine runs and positions correctly; it simply has no map of this place.

## Why there is nothing to load

**The offline catalog is China.** Settings → Personal → Offline map lists
`All cities` as Chinese provinces (`直辖市`, `安徽省`, `福建省`, `甘肃省`, …),
its search box says *"Please enter simplified Chinese, Pinyin or initials"*, and
the one global item is `Base package`, 55.53 MB, *"Necessary for cross-city
navigation"*. `Current city` is `To be updated`, `Nearby cities` is
`0 cities / 0.00 KB`. There is no region in that list that is not in China.

**The USB import is Amap's own pack, version-locked.** The screen has a U-disk
button, and the app's own help text is explicit: download the pack for *your
version* from `https://auto.amap.com/download/map_data/v3`, unpack `amapauto9`
to the root of a USB stick, insert it. A neighbouring string is
`U盘数据与导航版本不兼容，无法进行更新` — "U-disk data is incompatible with the
navigation version, cannot update". The import accepts Amap's proprietary,
version-matched data and nothing else; there is no OSM, GPX, MBTiles, tile-URL
or style hook anywhere in the app.

**The data model is keyed on Chinese administrative codes.** `libGbl.so` is full
of `GetAdcodeByLonLat`, `DriveLinkAccessorGetAdcode`,
`DrivePathEngineGetCityAdcodeList`, `WHERE adcode = %d`. Failure strings match:
`路线规划失败，所在城市未下载离线数据` and `…终点未下载离线数据` — routing is
refused per city when that city's pack is missing.

**There is no overseas mode.** `海外`, `境外`, `oversea`, `Oversea` and `OVERSEA`
occur zero times in `resources.arsc` and zero times in `libGbl.so` (control:
`amap` occurs 201 times in the same binary).

**The tile services this product family uses stop at Greater China.** Host
probe at z15 of `webrd01.is.autonavi.com` (`style=8`, roads),
`webrd02` (`style=7`, base) and `webst01` (`style=6`, satellite), bytes per
tile:

| | Beijing | Hong Kong | Taipei | Tokyo | Bangkok | Paris | Moscow | Sochi |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| roads | 12 379 | 18 939 | 9 337 | 179 | 179 | 179 | 179 | 179 |
| base | 13 538 | 20 168 | 12 765 | 124 | 124 | 124 | 124 | 124 |
| satellite | 16 299 | 13 270 | 16 575 | 4 235 | 4 235 | 4 235 | 4 235 | 4 235 |

The overseas numbers are identical placeholder images. At z8 Moscow does return
8 335 bytes — a coarse world overview with `莫斯科` and trunk roads — so a world
layer exists at overview scale and nothing below it. The car reaches the servers
(`ping auto.amap.com` ≈ 290 ms, `autoapi.amap.com` ≈ 187 ms from the head unit),
so this is coverage, not connectivity.

## Amap does have a world map — this build does not reach it

Corrected on 2026-09-22 after the owner opened Amap on a phone and saw Moscow
and their own position. Amap's consumer product does carry a world map: the
company announced coverage of 200+ countries and regions with driving, cycling,
walking and transit routing in 78 languages, and the open platform sells a world
map service. So "Amap has no data here" is wrong as a statement about Amap.

It is right as a statement about **this head unit**. Searching `Moscow` in the
car's own map (2026-09-22, live) returns Chinese restaurants: tabs `北京`,
`宁波`, `大连`, `其他城市`, first hit `莫斯科餐厅` in Beijing's 西城区, 5 834 km
away, cuisine `俄国菜`; the `其他城市` tab offers `莫斯科西餐厅` in 满洲里市,
5 171 km. The backend answered a Latin query by scanning Chinese cities. Nothing
in the result space is outside China, and the map's canvas stays empty at the
car's own position while the same servers answer within 190 ms.

The owner then opened `amap.com/@37.6173,55.7558,14z` in a browser: Moscow is
drawn in full detail — the ring roads, `M11`, `M12`, `E105`, the airports, all
labelled in Chinese. So the data exists and Amap serves it to a browser from
here. The head unit's client asks a different backend and gets China.

So the world map lives in the phone app, the web map and the open platform, not
in the AutoSDK build BYD ships here.

## The pipeline works; only the data is missing

The strongest test, run last (2026-09-22). In the same session, with `Current
city` still `To be updated` and `Nearby cities` still `0 cities / 0.00 KB` —
that is, with **no offline pack downloaded at all** — a Beijing search result was
opened. The map drew Beijing in full: streets and their names, buildings, water,
the zoo and the planetarium, metro entrances, traffic-coloured roads, POI icons,
a photo card with opening hours and two phone numbers.

So the client streams vector map data online and renders it correctly, from this
car, on this network, with an empty offline store. Nothing about the engine, the
build, the connection or the missing packs explains the empty canvas at home.
The one thing that differs between the two places is whether the server has data
for them.

That also retires the last workaround worth naming: downloading the 55.53 MB
`Base package` cannot help. Rendering never needed it, and its content is China.

## The native levers, and where they stop

What the firmware itself offers, and what it does not:

| Lever | State | Reach |
| --- | --- | --- |
| `PersonBean.DEFAULT_MAP_SWITCH` | already `ru.yandex.yandexnavi` (read 2026-09-22) | the car's map role: voice, Shortcuts `102000` |
| `Settings.Global.byd_map_package` | `com.byd.launchermap` | CustomKey action 7 only; this car uses action 1 (APA), so it is inert here |
| Map Settings → Navigate / Acoustic / Interconnect / Personal | inspected live | route preferences, voice, phone→car destination handoff (BYD app, WeChat, Amap phone, Dianping, Meituan), account, offline map. **No region, country or data-source control anywhere** |

The one switch in that table never pulled is the Amap account: the map reports
`Not logged in yet`, and signing in is the owner's to do, not this session's.
Nothing in the app ties an account to a region — the account page offers
favourites, offline map and Team — so the expectation is that it changes
nothing, but it is the last untried control that exists in the UI.

Beyond it there is no property, setting or account switch that points the stock
client at the world map. Reaching it would mean patching a signed `/system` app and then
persuading a backend that answers only about China — not a native path, and not
a reachable one.

## Verdict

The stock map cannot be made to show Russia. Not by a setting, not by a
downloaded pack, not by a USB import, not by pointing it at another tile source:
the renderer consumes Amap's own vector data, the only importable data is Amap's
own China packs — Amap's install page offers exactly `全量地图数据` (full) or
`分省地图数据` (per-province), and nothing else — and the online services this
build talks to answer only about China. Amap's world map does exist, but it is
not sold as an `amapauto9` pack and there is no build here that subscribes to
it. Anything short of replacing the engine — a 569 MB closed `/system` app that
also feeds the cluster map card and the AR HUD — does not reach this.

What is left is what this repository already does: keep the stock surfaces and
put a different navigator behind them. The navigation role (`PersonBean`
`DEFAULT_MAP_SWITCH`) points voice and Shortcuts at an installed navigator, the
Navigation feature projects that navigator onto the cluster, and AR HUD guidance
is written by this app rather than read from Amap. See
[shortcuts-automation-findings.md](shortcuts-automation-findings.md),
[instrument-display-findings.md](instrument-display-findings.md) and
[dishare-api-notes.md](dishare-api-notes.md).

One untested option is worth naming, because it is ordinary: Amap's **phone**
app (`com.autonavi.minimap`) is an ordinary Android app and is not installed on
this head unit. If it runs here it would carry the world map the owner just saw
on their phone, and it would be projectable to the cluster the same way Yandex
Navigator is. That is not the stock map — it is another navigator behind the
same surfaces.

## Not checked

- Whether the `Base package` and `Current city` downloads still succeed from
  here; both showed `To be updated` and were not pressed.
- Whether the engine would draw the coarse world overview layer (the one behind
  the z8 tile) if the map were zoomed out to country scale. It could not be
  tested from a shell: the map exposes no zoom control in its view tree,
  `KEYCODE_ZOOM_OUT` is ignored, and `sendevent` to `/dev/input/event4`
  (`himax-touchscreen`) is refused for the shell UID, so no pinch can be
  injected. It would not change the answer — an overview layer carries no
  routing and no search.
