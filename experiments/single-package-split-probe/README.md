# Single-package split probe

Disposable DiLink 5.1 experiment for deciding whether the permanent Denza Apps entry,
the toggle-controlled «Разделить экран» launcher alias, and the pane-neutral INFO picker
can safely share one Android package.

This is not product code. Its package is `dev.denza.singlepackage.probe`, and it must be
removed after the run.

The conclusion was adopted in Denza Apps `0.5.3`: the permanent control entry,
toggle-controlled split alias, and two pane-neutral picker tasks now live in the
single `dev.denza.apps` package. Product restore is app-owned and does not depend
on SmartMulti remembering the same package twice. This probe remains only as
reproducible evidence for that decision.

## Hypothesis

One package can expose a permanent launcher Activity plus a disabled-by-default launcher
alias, while `PackageManager.setComponentEnabledSetting()` makes only the alias appear or
disappear. The same package can expose one `MAIN + CATEGORY_INFO` picker Activity and run
two separate picker tasks in the exact BYD primary/secondary roots. SmartMulti must resolve
the INFO picker for package restore without moving or replacing the permanent control task.

## Success conditions

1. The app center shows only **Single-package split probe** after installation.
2. Turning the in-app switch on adds **Разделить экран — probe** without restarting
   Launcher3; turning it off removes only that entry.
3. `getLaunchIntentForPackage(dev.denza.singlepackage.probe)` resolves
   `ProbePickerActivity`, not the control Activity or split alias.
4. The split command creates two distinct `ProbePickerActivity` tasks, one in each live
   native root, with area mode `3`.
5. Opening the permanent control entry does not reuse or move either picker task.
6. After SmartMulti restore, both panes resolve the INFO picker and the control Activity
   remains a separate fullscreen task.

Any wrong component, task reuse across panes, missing launcher refresh, Home/fullscreen
intermediate, or stock-process crash falsifies the single-package product shape.

## Safety boundary

Run from a documented clean SmartMulti baseline with exactly one live-car owning session.
Capture the settings, gate, area and `am stack list` before every mutation. The host wrapper
never calls runtime transaction 125: manifest support must be sufficient, avoiding an
allowlist entry that would survive until reboot. Reset uses the firmware package-change
fallback only when the remembered pair contains this exact probe package and refuses every
unrelated pair.

Build output:

```text
experiments/single-package-split-probe/build/outputs/apk/debug/
single-package-split-probe.apk
```

## Live run — 2026-08-16

The launcher-alias half of the hypothesis passed on the target head unit:

- a fresh install exposed only `ProbeControlActivity`;
- the app's own `PackageManager.setComponentEnabledSetting()` call added
  `SplitEntryAlias` without a Launcher3 restart;
- disabling it removed only the alias, and enabling it again restored both launcher
  entries;
- the same off/on cycle was repeated by tapping the in-app switch, and the actual app
  center UI removed and restored the colored tile while keeping the control tile;
- tapping the colored tile opened `SplitEntryAlias` as task 260, displayed the expected
  probe toast, returned to the app center, and produced no crash;
- transaction 112 returned `1` for the package, and the package INFO resolver selected
  `ProbePickerActivity`.

The operator's `ru.yandex.music + com.byd.sr` split was then closed through the Denza
Apps UI. Its policy toggle became false and area mode became `0`. Temporarily disabling
and immediately re-enabling only `ru.yandex.music` let the firmware package-change
receiver replace that remembered pair with its policy baseline without deleting app
data.

The two-task and control-isolation parts also passed:

- task 269 and task 270 were simultaneously visible instances of the same
  `ProbePickerActivity`, one in each native root, at the roots' exact bounds;
- the repeatable cold-start run created task 284 and task 285 from area mode `0`, removed
  the temporary SR, LauncherMap, and stock-picker bootstrap tasks, and left only the two
  probe tasks in roots 2 and 3 with area mode `3`;
- opening the permanent control entry created a separate fullscreen task 271; both picker
  tasks stayed in their roots, and Back restored area mode `3` without recreating them;
- closing one clean picker task expanded the survivor normally (`area=2`), and a later
  process-death/cold-start run recreated the pair;
- no probe crash was recorded.

Direct shell `am start` calls using the BYD primary/secondary categories created two
independent tasks but put them in the fullscreen root. The same happened to the existing
picker-only `dev.denza.split` package, so this is not a single-package restriction. The
working cold-start fallback is: stage the exact firmware baseline tasks in roots 2/3,
call transaction 115 when collapsed geometry must be recreated, focus the baseline,
create both picker tasks, move/resize them to the live roots, remove only the exact
bootstrap tasks, and refocus the primary picker. Transaction 125 is never used.

The SmartMulti-restore condition failed as a product mechanism. Moving tasks does not
rewrite its remembered package pair, and the direct category starts that went fullscreen
also left the pair unchanged. A single-package product must therefore retain its own last
selection and rebuild the two picker tasks; it must not depend on firmware restore storing
the same package twice.

Final live state: area mode `0`, Denza split policy disabled, remembered policy pair
`com.android.launcher3 + com.byd.launchermap`, Yandex Music enabled, probe alias enabled,
and the existing SSH/ADB tunnel left running.
