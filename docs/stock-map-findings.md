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

**AutoNavi has no street data here anyway.** Host probe of
`webrd01.is.autonavi.com` (`style=8`): Moscow at z12 and z15 returns a
179-byte 1-bit blank PNG; Beijing at the same zooms returns 19 152 and 12 379
bytes. Moscow at z8 returns 8 335 bytes — a coarse world overview layer exists,
street level does not. The car reaches the servers (`ping auto.amap.com` ≈ 290 ms
from the head unit), so this is coverage, not connectivity.

## Verdict

The stock map cannot be made to show Russia. Not by a setting, not by a
downloaded pack, not by a USB import, not by pointing it at another tile source:
the renderer consumes Amap's own vector data, the only importable data is Amap's
own signed version-matched packs, and Amap has no Russian street data to put in
them. Anything short of replacing the engine — which is a 569 MB closed
`/system` app that also feeds the cluster map card and the AR HUD — does not
reach this.

What is left is what this repository already does: keep the stock surfaces and
put a different navigator behind them. The navigation role (`PersonBean`
`DEFAULT_MAP_SWITCH`) points voice and Shortcuts at an installed navigator, the
Navigation feature projects that navigator onto the cluster, and AR HUD guidance
is written by this app rather than read from Amap. See
[shortcuts-automation-findings.md](shortcuts-automation-findings.md),
[instrument-display-findings.md](instrument-display-findings.md) and
[dishare-api-notes.md](dishare-api-notes.md).

## Not checked

- Whether the `Base package` and `Current city` downloads still succeed from
  here; both showed `To be updated` and were not pressed.
- Whether the map's online search returns anything for a Russian query. The
  catalog and the blank canvas answer the question; typing into the owner's
  search history to confirm it was not worth the litter.
