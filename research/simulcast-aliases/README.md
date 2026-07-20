# Simulcast aliases

> **Archived on 2026-06-29.** The Denza Apps accessibility overlay replaced this
> module, so it was removed from `settings.gradle.kts`. To revisit the experiment,
> add `include(":simulcast-aliases:launcher")` for
> `research/simulcast-aliases/launcher` back to the settings file.

Small launcher-only apps that occupy package names already whitelisted by
`com.byd.dishare` for Simulcast App Change.

The static DiShare resource `array/public_mirror_apps` contains:

- `com.huiaichang.byd.desktop`
- `com.bilibili.bilithings`
- `com.tencent.qqlive`
- `com.qiyi.video.pad`

Runtime App Change logs on the test car also showed these package names:

- `com.tencent.qqlive.audiobox`
- `com.mgtv.auto`
- `cn.cmvideo.car.play`
- `com.youku.car`

The `launcher` module builds multiple APK flavors that occupy those package names
and bridge launches to Russian video apps.

`denza-apps` now uses a touchable custom drag layer as the primary UX path. It
draws the real target app icons, maps the drop point to a DiShare receiver, and
starts the real target app directly through `dishare-bridge`.

These alias APKs remain useful as a fallback and for native Simulcast research:
drag/drop can still go to native Simulcast, the alias can copy the current
DiShare provider/receivers, and then start the real target app.

Alias activities use `MAIN` + `CATEGORY_DEFAULT` + `CATEGORY_INFO`, not
`CATEGORY_LAUNCHER`. Android can still resolve them programmatically for DiShare,
but normal launchers should not show six extra app icons.

Current verified behavior on 2026-06-28:

- the native Simulcast App Change row may still show stock Chinese/cloud
  names/icons;
- selecting the occupied slot and dragging it to a target screen starts the real
  target app through DiShare. Current visible-row mapping:
  - `com.tencent.qqlive` / `com.tencent.qqlive.audiobox` -> `com.vk.vkvideo`
  - `com.mgtv.auto` -> `ru.rutube.app`
  - `cn.cmvideo.car.play` -> `ru.yandex.yandexnavi`
  - `com.youku.car` -> `ru.yandex.music`
  - `com.qiyi.video.pad` -> `ru.rutube.app`
- the first visible Bilibili slot is the real installed `com.bilibili.bilithings`
  package on the test car. It can carry a Russian label only if that package is
  replaced with an alias APK.
- direct target shares use `1024x576`; source-only slots use `2560x1440`;
- the alias skips its normal `startActivity()` fallback inside `BYD-Mirror`
  because `MirrorContext` blocks non-whitelisted packages there.

This experiment belongs to Simulcast/DiShare and has no dependency on the two
legacy apps.

Build (only after re-adding the module to `settings.gradle.kts`):

```bash
./gradlew :simulcast-aliases:launcher:assembleDebug
```

Install all generated alias APKs:

```bash
for apk in research/simulcast-aliases/launcher/build/outputs/apk/*/debug/simulcast-alias-*.apk; do
  adb install -r "$apk"
done
```
