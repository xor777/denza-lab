# Split screen findings

The factory split screen is gone in this firmware. This page records what was
removed, what merely looks removed, and which of the remaining routes are worth
anyone's time.

Established on the live car (DiLink5.1, `BYD AUTO`, Android 13 / API 33) on
2026-08-14, after the firmware update that changed the app-launch button to open
a plain fullscreen picker.

## Verdict

**The official split is dead and cannot be revived from user space.** The
picker survived; the placement behind it did not. Reviving it means modifying
the framework, not flipping a setting.

## One rule explains everything

BYD's framework forces every **visible** task in `byd-freeform` back to
fullscreen. The name is misleading: `byd-freeform` is the ordinary mode every
app runs in, and it is always fullscreen.

Measured directly by resizing two tasks and reading the bounds back:

```
task 204  (File Manager, backgrounded)   mBounds=Rect(860, 96 - 2560, 1480)   kept
task 173  (adbbridge, on screen)         mBounds=Rect(0, 0 - 2560, 1600)      snapped back
```

`am task resize` does apply, and the bounds persist — but only while the task is
invisible. The moment a task is shown it is stretched to the full display. That
single rule kills both the factory split and any home-grown attempt to place two
ordinary apps side by side.

A real floating window exists in exactly one place: `mode=freeform` (without the
`byd-` prefix), which the framework grants only to apps on the AE whitelist.

## What survived, and what it is worth

| Piece | State | Use |
| --- | --- | --- |
| `com.android.launcher3/.SplitScreenListActivity` | **Launches.** Shows "Please select an app for 2/3 screen" with the curated app list | None — selecting an app opens it fullscreen |
| Empty pane task #2, `byd-freeform`, `Rect(1704,112–2536,1472)` | Present, `sz=0` | The slot the old `SplitShellRouter` filled; nothing fills it now |
| AOSP split root #9 (`mCreatedByOrganizer`) with two `multi-window` children | Present, empty | Untouched by the vendor path |
| `aewindow` service, `IAeWindowManager`, `mAeFeatureState=1` | **Works** | See below |

## AE Window: works, and is useless here

Launching a whitelisted app puts it in a genuine floating window over the
fullscreen app underneath — two apps on screen at once:

```
is_ae_windows:  0 → 1
mode=freeform                          (not byd-freeform)
mBounds=Rect(1782, 176 - 2488, 1432)
```

The catch is the whitelist: `mAeWindowListMap` holds **109 packages, all
Chinese**, and the only one installed on this car is `com.quark.browser`. The
list is not in `settings` and not in any config under `/system/etc`,
`/product/etc`, `/vendor/etc` or `/data/system`; the related key
`byd_smart_multi_split_window_mode` resolves inside **`services.jar`**. It is
compiled into the framework and cannot be extended from user space.

## Settings: state, not controls

Four keys look like a control surface and are not:

```
system: byd_smart_multi_primary_activity  = com.android.launcher3
system: byd_smart_multi_primary_position  = 2
system: byd_smart_multi_second_activity   = com.byd.sr
system: byd_smart_multi_split_window_mode = 102
system: ivi_freeform_window_show          = 0
global: enable_freeform_support           = 1
```

Writing the pane activities changed nothing and the framework overwrote them —
they record what a split *was*, they do not command one. Setting
`ivi_freeform_window_show=1` changed nothing either, including through a full
run of the stock picker. All values were restored afterwards.

## Dead ends, so they are not re-run

- **`am start --windowingMode`** — tried 5, 6, 100, 101, 102. Every value is
  coerced to `byd-freeform` fullscreen.
- **`cmd aewindow`** — "No shell command implementation".
- **`am task resize`** — applies, then is undone the moment the task is visible.
- **Writing the `byd_smart_multi_*` settings** — no effect, overwritten.

## The one route that does work: a secondary display

Proven by spike. A simulated secondary display was created with
`settings put global overlay_display_devices "1200x1400/320"`, and a **third
party** app (`com.wings.translator`) was launched onto it with
`am start --display 24`:

- It **renders** — the app drew normally in the secondary display.
- Input **reaches it**: `dumpsys input` reported an active
  `Viewport VIRTUAL: displayId=24 ... isActive=[1]` and, while it was up,
  `FocusedDisplayId: 24`. A tap injected with `input -d 24 tap` landed.

This is the only path that does not need the framework changed, because we are
not asking it to place someone else's window — we host the display ourselves.

**The catch is input, and it decides the architecture.** An ordinary app cannot
inject events into another app's window; `INJECT_EVENTS` is signature-level.
Shell holds it, which is why `input -d 24` works. So a product version needs a
shell-side helper reached over the app's existing `LocalAdbClient` channel — the
pattern scrcpy uses — rather than per-touch `input` invocations, which are far
too slow for a map or a player.

Related: the vendor already runs virtual displays of its own
(`virtual:com.xdja.containerservice,...,shared_fission_bg_XDJAScreenProjection_*`),
which is the machinery behind simulcast/DiShare in this repository.

## Spike on the real target apps: both pass

Run on 2026-08-14 against a simulated display (`overlay_display_devices`,
1280x1440/320, display id 25). The two failures that would have sunk the whole
idea — a secure-surface blackout, or unusable input latency — did not happen.

| App | Result |
| --- | --- |
| **Yandex Music** (`ru.yandex.music`) | Renders in full: "My Vibe", now-playing track, playlist tiles, bottom navigation |
| **Yandex Navigator** (`ru.yandex.yandexnavi`) | Renders in full, including the live GPU map: streets, house numbers, the car arrow and route options with ETAs |
| Input | `FocusedDisplayId` follows the secondary display, its `Viewport VIRTUAL` is active, injected taps land |
| Injection latency | **25 ms** per `input -d 25 tap`, averaged over ten calls |

The 25 ms figure is the crude path — a whole `input` process per event — and it
still only buys discrete taps. It cannot follow a finger, so map panning and
pinching are out of reach this way; a list-and-buttons app like Yandex Music
would already be usable, a map would not.

That is an argument about the injector, not about feasibility: a persistent
shell-side helper holding an `InputManager` connection (the scrcpy pattern,
reached over the app's existing `LocalAdbClient` channel) removes the per-event
process spawn entirely and can deliver real motion streams.

## Second spike: an app-owned display, which is the product shape

The first spike used `overlay_display_devices` — a display owned by the
*system*. That proved rendering but not the product, because a display an app
creates is private to its own UID and other apps cannot be launched onto it.
`:display-probe` closes that gap: it takes a MediaProjection token (consent
auto-granted with `appops set dev.denza.display.probe PROJECT_MEDIA allow`) and
creates the display with `VIRTUAL_DISPLAY_FLAG_PUBLIC`, drawing into an
`ImageReader` so the probe needs no UI and can dump exactly what arrived.

Everything the architecture needs is proven:

| Step | Result |
| --- | --- |
| App creates a public display | `displayId=26`, `flags=0x8` — `FLAG_PRESENTATION` and, crucially, **no** `FLAG_PRIVATE` |
| Third-party activity launches onto it | Yandex Music and Yandex Navigator both land on display 26 via `am start --display 26` from the shell channel |
| Content reaches our own surface | Frames pulled from the probe show both apps at full fidelity — Navigator including its live GPU map, street names, house numbers and route ETAs |
| Touch reaches the hosted app | Three taps on Navigator's zoom button moved the scale from **30 м to 5 м** |

### And then it dies: the hosted app escapes on its own navigation

Hosting survives exactly as long as the app stays on its first screen. The
moment the app itself starts another activity — tapping search in Yandex Music
was enough — the system moves the **whole task** to the main display:

```
taskId=218 ru.yandex.music  bounds=[0,0][1280,1440]   before the tap
taskId=218 ru.yandex.music  bounds=[0,0][2560,1600]   after it
```

The framework says so outright:

```
D ActivityTaskManager: changeOptionsIfNeed mPreferredTaskDisplayArea=DefaultTaskDisplayArea@...
D ActivityTaskManager: activity pkgname=ru.yandex.music launch displayId=27
W ActivityTaskManager: Failed to put Task{... A=10132:ru.yandex.music ...} on display 27
```

Our initial launch worked because **shell** started it, and shell is allowed to
place activities anywhere. When the hosted app is the caller it is not the
display's owner, the placement is refused, and the task falls back to the
default display area. `changeOptionsIfNeed` is not an AOSP method name, so at
least part of this is a vendor rule on top of the standard untrusted-display
restriction.

The zoom test passed earlier for the same reason it is not a counter-example:
pinching and zooming happen inside one activity and start nothing.

That is the end of the road for this approach. An app-owned display can host a
third-party app's *first screen* and nothing beyond it, which is not a product.
Making it work needs `VIRTUAL_DISPLAY_FLAG_TRUSTED`, and that needs a signature
permission — the same wall as everything else here.

The IME question that prompted this test never got a clean answer, and no longer
matters: the keyboard did come up, but on the main display, because the app had
already escaped there.

## Where this leaves it

Every route out of user space is now closed:

- The factory split — placement logic gone from the framework.
- AE Window — works, whitelist compiled into `services.jar`.
- Ordinary windows — any visible `byd-freeform` task is forced fullscreen.
- An app-owned display — hosts one screen, then the app escapes to the main
  display.

All four end at the same wall: this needs a modified system image, not an app.
If split screen is genuinely wanted, that is the decision to weigh — with the
usual consequences of a modified `/system` on a `user`-build vehicle — and not
another week of looking for a command that does not exist.
