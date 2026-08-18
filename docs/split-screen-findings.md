# Split screen findings

> **Superseded on 2026-08-14.** The earlier verdict that native split was gone
> was wrong. The historical failed routes remain below because they explain
> what not to retry, but they are not the current conclusion.

Established on the live car (DiLink5.1, `BYD AUTO`, Android 13 / API 33) on
2026-08-14, after the firmware update that changed the app-launch button to open
a plain fullscreen picker.

## Product direction: explicit two-picker session

The contextual foreground router was replaced in product code on 2026-08-16 by
an explicit launcher entry named **«Разделить экран»**. This change is built and
unit-tested. The separate launcher entry, native split creation, saved-app
restoration, and picker geometry have since been exercised on the connected
car. A later live trace showed that embedding a picker Activity into the
unresizeable Launcher3 host produces a transient fullscreen/Home frame when a
pane is reopened. The replacement direct-root launch is proven manually and
now implemented; the complete dismissal/reopen flow still requires operator
acceptance.

The product contract is deliberately small:

1. launching «Разделить экран» opens the firmware gate when needed, resolves
   the two live BYD roots, reuses a valid Denza picker already in each root, or
   launches the pane-neutral picker as a new standalone task through the exact
   BYD primary/secondary category; it does not call the remembered-pair entry
   transaction and does not recreate Home as an intermediate scene;
2. each root keeps one Denza picker base task and at most one selected app task
   above it; a native bootstrap is removed only after its replacement is
   observably visible in the correct root;
3. a picker tap names the destination root before any shell mutation, so no
   foreground, focus, width, or vacancy guessing is used; after the exact task
   move, its bounds must equal that root's current bounds, because this firmware
   can retain the geometry of the pane from which the task came;
4. dismissing the selected app reveals the picker already underneath it;
   dismissing the picker collapses that pane normally, while pulling the native
   drag control open replaces the briefly created stock picker directly in its
   root without a fullscreen intermediate frame;
5. the last package selected in each root is persisted and restored the next
   time the launcher entry is opened;
6. the same package is rejected in the other root because ordinary Android
   task reuse cannot provide a reliable two-instance contract.

The exact framework/SystemUI corpus from this vehicle, rather than OpenBYD,
defines the design. `CustomDividerActivity`,
`CustomDividerSecondaryActivity`, `DividerUtils`, and `StageCoordinator` show
the same picker-under-app stack model and keep split alive while a picker base
remains. The public `StatusBarManager` methods are not used: the exact
`StatusBarManagerService` returns early for
`ro.build.ui_platformized=1`. The implementation instead uses the already
live-proven `activity_task` gate and live root lookup. Transaction 115 is not
part of the explicit product flow because it restores the firmware's remembered
pair before our command can establish the picker scene. The product launches two
tasks of the exact pane-neutral Denza picker component with
`byd.intent.category.START_IVI_PRIMARY` / `START_IVI_SECOND` and flags
`0x18010000`. On this DiLink 5.1 car the standalone picker task appeared in its
target root on its first frame at the root's exact bounds, with no Home frame,
fullscreen frame, reparent, or corrective resize. The earlier
`ActivityOptions.setLaunchTaskId` implementation is retired: Launcher3's base
task is unresizeable, so embedding a resizeable Activity inside it still lets
the OEM task transition go fullscreen before it is corrected. The shell-UID
helper is now limited to component-validated focus and removal operations.
The framework owns divider placement and no synthetic drag is used.
Selected app tasks still receive one bounded `am task resize` when a verified
post-move snapshot shows stale bounds; the operation is within the destination
root and its equality postcondition is mandatory.

Starting with Denza Apps v0.5.3, the launcher entry, pane-neutral picker, and
stable app host live in the single installed package `dev.denza.apps`. The
**Разделить экран** tile is a disabled-by-default `activity-alias`; the in-app
toggle changes that alias and the matching runtime state together. Tapping it starts the embedded
`Theme.NoDisplay` entry, which opens the explicit picker session directly. No
second APK, package, installation step, or cross-UID command handoff exists.
The application carries `BYD_SUPPORT_SPLIT_ACTIVITY=1`, while exact component
checks keep the permanent Denza Apps control task distinct from the two picker
tasks.

The embedded picker Activity has a `MAIN + CATEGORY_INFO` intent filter, not a
launcher filter. This preserves the proven firmware resolver seam without
adding another always-visible icon. Product restore does not depend on
SmartMulti remembering the same package twice: Denza Apps persists the last
selection and rebuilds two independent tasks of the same picker component. App
choices are still resolved explicitly from `CATEGORY_LAUNCHER`, so an unrelated
app's INFO Activity cannot be opened by a picker tap.

Before this one-APK migration, live experiments used separate
`dev.denza.split.launcher` and `dev.denza.split` packages. Those experiments
established that cross-UID service/provider starts can be discarded while
Denza Apps is stopped. The finding remains historical evidence, but the current
same-package entry calls the coordinator in-process and does not cross that
vendor process-start boundary.

The old 200 ms router remains compiled only as a regression/reference seam and
is not constructed in explicit-picker mode. Navigation and Simulcast therefore
have no split foreground loop to pause, and Denza Apps never moves an ordinary
app launch from the car launcher. Task cleanup also no longer calls the nonexistent
`am task remove`: an `app_process` helper validates task id and base component,
plus the current top component when the snapshot can identify it reliably,
before invoking `IActivityTaskManager.removeTask`.
The firmware gate is global, so the explicit session persists a small ownership
lease: it closes transaction 126 only if Denza Apps was the caller that opened
it. A gate already open before the session is left untouched.
Picker visibility may coincide with Navigation or Simulcast moving the selected
task elsewhere. Their existing external-move lease suppresses picker reconciliation
during that interval; the picker flow never tries to reclaim the moved app.
Selecting or restoring a package whose task is currently on another Android
display also fails closed and leaves the picker visible. While a member of the
saved pair is projected, picker selections are paused altogether so Navigation
can return the original task and companion to their recorded roots. If the
companion itself was dismissed by gesture during projection, Navigation now
treats that as a valid picker vacancy instead of failing the entire return.
SmartMulti handles the native dismissal gesture asymmetrically: a primary task
is removed, while a secondary task can merely be moved behind the next task.
When a Denza picker is observably topmost, the automaton may remove only the
exact app task id and package previously recorded for that pane. There is no
generic repair/prune pass and a hidden picker in the other pane is never touched.
This makes dismissal a real close and prevents reselecting an app from
resurrecting a previously broken task state. Native-picker attach is bounded to
three attempts per unchanged host and is retried only after an observed failure
or a fresh host id.
The Denza picker now mirrors the current Launcher3 split-list geometry: one
short centered title, two columns in the narrow pane, four in the wide pane,
`128 x 132 dp` cells, `72 dp` icons, and two-line `16 sp` labels without card
backgrounds. The exact Launcher3 Activity also proved that its apparent
gradient is not an opaque color resource: it makes the window transparent and
enables `UnionWindowManager.setUnionWindowBackgroundBlur`. Denza's picker now
uses the same window flag and edge-to-edge layout, with a dark gradient only as
a fallback if that vendor API is unavailable. Live comparison on 2026-08-16
showed matching title placement and background treatment in adjacent stock and
Denza picker panes. Its catalog follows the stock app-list visibility contract
rather than the split-compatibility list: an explicit `ShowInAppList=false`
from the app's `DynaConfigContentProvider` (or application metadata using that
exact key) hides the app, while `true` or an absent value keeps it visible. The
`BYD_SUPPORT_SPLIT_ACTIVITY` marker is deliberately not used as a presentation
filter.

### Live acceptance status

The 2026-08-16 run confirmed the Home-to-native-host transition, Launcher3 and
SR bootstrap identities, restoration of Navigator above its stopped secondary
picker, area mode `3`, and an unchanged `com.byd.avc` crash buffer. A later
isolated live launch confirmed that a standalone Denza picker using the exact
BYD primary category starts directly in the narrow root at
`[24,112][856,1472]` on its first rendered frame. The production replacement
for both panes is unit-tested. The operator then dismissed one Denza picker,
observed Music expand normally, and reopened the vacancy with the native drag
control. Launcher3's stock picker drew directly in the narrow root and remained
visible for about 1.6 seconds until its accessibility event launched the Denza
replacement. The replacement's first frame was in the same root at `832 x 1360`;
area mode stayed `3` throughout the replacement, Music stayed in the other root
with matching bounds, Home was not shown, and the crash buffer remained empty.
The brief stock picker is an accepted signal latency, not a global scene rebuild.

The selected-app stable host is an optional ratchet above the previously proven
direct BYD launch, never a replacement for it. On the 2026-08-16 17:47 live
Music selection, SmartMulti created host task `#395` in the requested secondary
root and then reparented the host with Music into the primary root. Denza Apps
removed that exact failed host task, proved that the permanent secondary picker
was visible and alone again, and automatically retried the old fixed launch
(`START_IVI_SECOND`, flags `0x10200000`). Music task `#396` then appeared in the
secondary root at `[880,112][2536,1472]`; Navigator task `#394` remained in the
primary root, no host task remained, and the crash buffer stayed empty. This is
the required failure contract: a host experiment may improve apps that launch
deep Activities, but a rejected or misplaced host can degrade only to the old
direct behavior. It may not leave a transparent input window, remove a picker,
or prevent the next selection. This trace is a permanent regression fixture.
The operator then interacted with Music and confirmed that it remained usable;
a settled follow-up dump still showed the same direct task `#396` in the
secondary root with exact bounds and no Music fullscreen escape or broken
network state. A separate Navigator selection completed as task `#399` in the
primary root and its temporary host `#398` was gone after settlement.

### Technical debt: selected-app host

`SplitAppHostActivity` remains an experimental compatibility layer, not part of
the proven firmware contract. A host can keep a deep Activity in the picker's
task, but SmartMulti may also reparent the host and trigger a global split
rebalance. The current platform-level guard bypasses the host for launcher
Activities declared `singleTask` or `singleInstance`, and every failed host
attempt must fall back to the live-proven direct BYD launch after removing only
its exact artifacts. This contains the known Navigator case without introducing
an application allowlist.

The guard is not a complete launch-topology model: launcher aliases, runtime
redirects, and deep Activities with a different task contract can still escape
the host. Do not extend it with per-app patches. Before promoting the host into
the stable contract, decide from vehicle framework traces whether selected apps
can be hosted without a SmartMulti scene rebuild; otherwise remove the host and
retain the direct launch as the product baseline.

The Split Screen toggle shutdown contract failed live acceptance on 2026-08-16.
An older installed build had left the launcher alias disabled while
`policy_enabled_v2=true`, area mode `3`, and the resizeability lease were still
active. The repaired wiring correctly set the runtime policy to `false`, removed
the picker state, and restored `force_resizable_activities` to its missing
baseline. Immediately after package replacement the area temporarily read `0`,
but the next normal Navigator launch entered an expanded native split container:
area `2`, persisted divider mode `102`, a visible drag control, and a hidden
Launcher3 `SplitScreenListActivity` in the other root. Navigator's apparent
fullscreen bounds therefore did not prove a standalone fullscreen task.

The corpus explains the failure. Modes `101` and `102` only resize one SmartMulti
container to full bounds and move the other container behind it; they do not end
the native split topology. The old ownership lease also deliberately left an
already-open transaction-126 gate open. Packages previously added to the
runtime split list consequently re-enter the SmartMulti roots on an ordinary
launch even while the product toggle is off. The pure toggle synchronization
test covers only icon/runtime persistence, not this firmware teardown. A real
shutdown must close the gate for the duration of the disabled product state,
move the selected task to the area-4 full IVI root, remove only owned picker/host
artifacts, and prove that a subsequent ordinary app launch remains in area `4`
without the divider. This is not yet live-accepted.

The remaining operator run should be intentionally short and observable:

1. update-install the APK and confirm that the app center shows both
   **Denza Apps** and **Разделить экран**;
2. launch **Разделить экран** with no usable stored pair and confirm two Denza
   pickers plus the native drag control;
3. choose Navigator in one picker and Music in the other, then navigate inside
   Music far enough to start another Activity; neither task may escape or be
   moved back by a corrective loop;
4. repeat app dismissal once with Navigator/Music and confirm that only the
   recorded task is removed while the other pane remains untouched;
5. dismiss it again, choose a third app in that picker, and confirm the root
   contains only its standalone Denza picker task plus the selected app task;
6. move the divider without swapping and confirm no application changes pane;
7. disable the Split screen toggle and confirm the focused app expands, both
   picker tasks disappear, and the drag control is gone;
8. reopen **Разделить экран** and confirm the last successful pair is restored;
9. only after the basic flow passes, repeat navigation projection/return and
   Simulcast checks to prove that removing the foreground router eliminated
   cross-feature task mutations.

The primary diagnostic tag is `DenzaSplitScreen`. Every failed shell mutation
also leaves a user-facing message in the picker or the Denza Apps split card.

### SmartMulti persistence contract (exact vehicle corpus, 2026-08-16)

The picker re-entry incident is no longer an open package-keying hypothesis.
It is explained by the exact framework pulled from this vehicle:

| State | Storage and lifetime | Writer / restore behavior |
| --- | --- | --- |
| selected primary package | `Settings.System` key `byd_smart_multi_primary_activity`; survives APK updates and reboot | `changePrimaryActivityForPkg()` posts `updateIviSettings()`; `retriveIviSettings()` accepts the stored package if the application is installed and enabled |
| selected secondary package | `Settings.System` key `byd_smart_multi_second_activity`; survives APK updates and reboot | same package-level validation and restore |
| primary side | `Settings.System` key `byd_smart_multi_primary_position` | `1` means primary left, `2` means primary right; a missing value is initialized to `1` |
| divider mode | `Settings.System` key `byd_smart_multi_split_window_mode` | default/balanced `100`, expanded primary `101`, expanded secondary `102` |
| runtime split allowlist | in-memory `mPrimaryActivityList` | transaction 125 only appends a package; there is no remove API and the addition disappears with `system_server`/reboot |
| manifest split support | package `ApplicationInfo.metaData` | `BYD_SUPPORT_SPLIT_ACTIVITY=1` makes the installed package split-capable; the check is not Activity-specific |
| split gate | static in-memory `mIsEnterSplit` | transaction 126 changes it; on this non-Huawei branch the framework default is `true` |

`startSpecificSplitPair()` restores the two remembered packages. For an
ordinary package, SmartMulti calls `makeLaunchIntent(package)`, which delegates
to `PackageManager.getLaunchIntentForPackage()`, adds the exact primary or
secondary BYD category, and starts the result. A stored installed package with
no INFO or LAUNCHER Activity passes SmartMulti's package-enabled validation but
produces a null launch intent later; `startPrimaryActivity()` or
`startSecondActivity()` then fails and can leave a vacant container. That was
the structural defect after `dev.denza.split` was made picker-only without an
INFO restore entry.

The controller also persists changes made by normal split starts, stock-picker
placement and swap. Removing tasks, removing the visible picker, or calling
`removeBydAllIviTask()` does not clear the remembered package pair. Package
removal/change replaces the pair with policy defaults only when either stored
application is missing or disabled.

### Single-package launcher-alias spike (2026-08-16)

An isolated `dev.denza.singlepackage.probe` APK tested whether one installed
package can expose a permanent control entry and a second launcher entry governed
by a Denza Apps-style toggle. A fresh install exposed only the control Activity.
Calling `PackageManager.setComponentEnabledSetting()` from the package added the
disabled-by-default `SplitEntryAlias` immediately; disabling it returned the
launcher query to the single control entry, and enabling it again restored both.
No Launcher3 restart was required. The full cycle was also repeated through the
real head-unit UI: the in-app switch removed and restored the colored tile in the
app center while the permanent control tile remained. Tapping the colored tile
opened `SplitEntryAlias` as task 260, displayed the expected probe toast, and
produced no crash. Transaction 112 returned `1`, and the package INFO resolver
selected the pane-neutral `ProbePickerActivity` as designed.

After the operator's split was closed through the Denza Apps toggle, the probe
created two simultaneous tasks of the same `ProbePickerActivity` and placed them
at the exact bounds of roots 2 and 3. They were independently addressable and
visible with area mode `3`. Opening the permanent control entry created a
separate fullscreen task while both picker tasks remained in place; Back restored
the two-pane scene. Closing one clean picker expanded the survivor normally, and
a process-death/cold-start run recreated both tasks. No probe crash was recorded.

Direct shell launches with `START_IVI_PRIMARY` / `START_IVI_SECOND` created two
independent tasks but placed them in the fullscreen root. The same control test
failed for the separate picker-only `dev.denza.split`, so this is not caused by
sharing a package with the permanent launcher Activity. The repeatable cold-start
path stages the exact SR/LauncherMap baseline tasks in roots 2/3, invokes
transaction 115 only when collapsed native geometry must be recreated, focuses
the baseline, creates the two probe tasks, moves and resizes them to the live
roots, removes the component-validated baseline and stock-picker tasks, and
refocuses the primary probe. Runtime allowlist transaction 125 is not used.

Firmware persistence is not the restore mechanism for this shape. Explicit task
movement did not rewrite the remembered pair, and the fullscreen category starts
also left it unchanged. A one-APK implementation is therefore technically viable,
but Denza Apps must persist its own last selection and rebuild the picker scene;
it cannot rely on SmartMulti to remember the same package for both panes.

The corpus used for this contract is `/system/framework/services.jar` SHA-256
`23a58a4e3c98c50541785390f1234e8c4d7138b5dd170c4f5843bae15b93c019`
and `/system/framework/framework.jar` SHA-256
`aa3acd7738e1fa22c4ec68f69b271fd6938cf7756239c1ac6b30051c0d4fda8c`.
OpenBYD remains reference material only.

### Acceptance reset

Every product acceptance run records the four settings above, gate state, area
mode, root/task snapshot, Denza split preferences, and the current
`force_resizable_activities` value before mutation. On this car the policy
fallback pair is `com.byd.sr` plus `com.byd.launchermap`, with primary position
`1`, divider mode `100`, and gate open. When the remembered pair contains the
product picker, the baseline is established by temporarily disabling only
`dev.denza.apps`: SmartMulti's package-change receiver observes that its
remembered member is unavailable, replaces both in-memory package fields with
policy defaults, and posts the same values to Settings. The picker is
immediately re-enabled and the result is verified. A direct primary/secondary
category launch was explicitly rejected as a reset mechanism: an existing task
on another display can be reused and the category need not rewrite the selected
package. Writing Settings alone is also insufficient because it leaves the
controller's in-memory fields stale until a reload.

A second clean Home baseline was observed after normally closing the two picker
tasks: `com.android.launcher3 + com.byd.launchermap`, primary position `1`, mode
`102`, area `0`. This is a firmware-written state, not a Denza Settings rewrite.
The reset helper accepts and verifies either exact clean tuple, but still
refuses every other non-Denza remembered pair.

The Denza session is reset separately: restore the exact leased
`force_resizable_activities` value, clear only `denza_split_screen.xml`, stop
the single Denza Apps package, and return Home. A fully clean runtime
allowlist additionally requires a controlled head-unit reboot; reinstalling an
APK does not clear it. One session owns all installation and car mutations for
the whole run.

Method rules for this class of work live in [governance.md](governance.md),
"Firmware Behavior Method".

### Navigation projection interaction

The split picker is the permanent base task below a selected navigator. When
that navigator is projected, the navigation proxy records its native source
root and the top task in the companion root, creates an empty organizer root on
the app-owned `Denza Navigation` display, and reparents only the navigator task.
The picker is revealed in the vacated IVI pane, the other split app stays in
place, and the native divider remains owned by SmartMulti.

The picker automaton marks that exact task as `PROJECTED` before releasing the
external-move lease. Selection remains fail-closed while a saved pair member is
on another Android display; the user must return navigation before replacing
its pane. On return, the proxy restores the companion if it still exists and
returns navigation to its recorded root above the same picker. A companion
dismissed during projection is a valid vacancy. If the user dismisses the
navigator's picker pane while navigation is projected, return expands the
navigator fullscreen instead of guessing a new split destination. These rules
preserve the previously live-proven projection topology; the complete flow
with the standalone INFO-restorable picker still requires the post-split live
acceptance run.

## Live-proven substrate (2026-08-14)

Native BYD split is reachable from user space without `/system` changes. The
working route is:

1. when the first launchable app appears, add it and the Denza Apps split
   placeholder to the firmware's runtime split list through `activity_task`
   transaction 125;
2. open the split gate through transaction 126 when needed;
3. obtain the live primary and secondary root ids through transaction 118 and
   move the neutral placeholder into the narrow primary root and the app into
   the wide secondary root with `am stack move-task`;
4. if the native divider is still collapsed, drag the real
   `multi-divider-shadow` input window to a balanced position;
5. read the resulting root bounds and apply them to the two tasks with
   `am task resize` so both apps receive a configuration change and rebuild
   their layouts for the panes.

The recovered DiLink 6/OpenBYD category route was originally rejected for
arbitrary third-party app launches because this firmware ignored it or produced
duplicate fullscreen tasks. That result still applies to the former router; it
does not apply to the exact resizeable Denza picker components, whose direct
`START_IVI_PRIMARY` / `START_IVI_SECOND` placement was later proven live. The
working historical MVP did not relaunch either target app. A live run placed
Яндекс Навигатор in root 2 at
`[24,112][856,1472]` and Яндекс Музыка in root 3 at
`[880,112][2536,1472]`; transaction 30 returned area mode `3`. Moving the tasks
alone left their old fullscreen bounds and visibly clipped both UIs. Explicitly
resizing each task to its root bounds made both apps immediately relayout.

A live single-app run then proved that the firmware cannot keep native split
with an actually empty root: removing the last task from one side immediately
changed area mode `3` to fullscreen mode `4`. A dedicated, non-interactive
`SplitPlaceholderActivity` therefore holds the narrow root. On 2026-08-14 the
installed MVP produced:

```
root 2  [24,112][856,1472]    SplitPlaceholderActivity  visible=true
root 3  [880,112][2536,1472]  Yandex Music             visible=true
area mode = 3
```

The placeholder intentionally contains only “Откройте второе приложение”. It
is the stable seam where the custom all-app picker can be added later without
changing the native split routing.

The former UI used one global toggle rather than an app whitelist: while it was
on, the first launchable app was moved into native split beside the placeholder.
A later distinct launchable app replaced the placeholder through the existing
native pair route. This behavior is retained below as live evidence but is no
longer the product interaction.
`dev.denza.apps` itself is explicitly launched with
`byd.intent.category.START_IVI_FULL`, so the control UI stays fullscreen even
when a native pair was already open.

The former coordinator observed ordinary launcher starts after they occurred; it could not
intercept the launcher before the second app draws. Polling is therefore 200 ms
and a short fullscreen frame could still be visible. Explicit-picker mode does
not run this loop.

### Internal Activity transitions

The remaining fullscreen escape was not app-specific. On the Huawei/UI7 path,
`BydSmartMultiIviController.isSupportSplit(Task)` rejects a task before checking
the runtime split list when `Task.isResizeable()` is false. A rejected task is
reparented to the full IVI root. Moving it back afterwards caused two rapid
configuration changes; Yandex Music reproduced this as a false "No internet
connection" state even though the validated Wi-Fi network and playback remained
available.

Android's global `force_resizable_activities` setting is the system-level gate
for that check. This ROM's `WindowManagerService.SettingsObserver` watches it at
runtime and updates `ActivityTaskManagerService.mForceResizableActivities`, so
neither a reboot nor a `/system` change is required. With the value set to `1`,
the same Yandex Music artist transition stayed in its native split root without
an intermediate fullscreen reparent or a corrective task move.

The split toggle now leases this global setting: it records the exact original
state (`missing`, `0`, or `1`) before enabling the override, reasserts it after
process recovery, and restores the original state when split is disabled. An
external setting change made while split is active is preserved. The earlier
reactive "move the escaped task back" workaround was removed.

The native swap gesture is supported. Static SystemUI inspection and live tests
show that it does not move the child tasks between roots: it reverses the two
StageCoordinator positions, so root 2 and root 3 keep their identities while
their bounds exchange. The router therefore stores a task for each native root
and observes the roots' physical order instead of forcing the apps back to a
hard-coded side. Both forms were proven live on 2026-08-14:

```
placeholder + Yandex Music  -> swap -> both tasks remain visible
VLC + Yandex Music          -> swap -> both tasks remain visible
```

The coordinator logged `native pair swapped` in both runs and left all task
movement to the native shell. It also refreshes a pane member from the visible
top task, while treating `SplitScreenListActivity` as a temporary overlay. This
keeps the other half of the pair stable when the stock selector is opened and
provides the state model the custom picker can reuse.

The same rule applies after one pane is closed and the survivor expands. A
manual divider pull can put the stock selector into either native root, and its
physical side or width may no longer match the firmware's primary/secondary
names. The router now retains the survivor's native-root affinity, observes the
selector root as the explicit vacancy, and moves only the newly launched task
into that vacancy. It never reparents the surviving app merely to recreate a
hard-coded primary/secondary order.

### Former routing state machine

The earlier `armed` / `entering` / `active` implementation was still too eager:
it treated area mode `3` as success even when the expected app was below
`SplitScreenListActivity`. A live failure left RUTUBE in the correct native root
while the picker remained on top, so the screen showed Navigator plus the
picker and RUTUBE appeared to be missing.

Routing is now a pure reducer with three pieces of durable intent:

- `anchor`: the app the user kept;
- `vacancy`: the native root explicitly freed by a picker or collapse, plus
  the task ids that were already below the picker;
- `target`: the exact expected `root -> task` pair while a transition is in
  progress.

Every 200 ms the reducer compares this intent with a fresh `am stack list`
snapshot. A target is complete only when area mode is `3`, both expected tasks
are the top tasks of their assigned roots, no picker covers either app, and the
task bounds equal the current root bounds. Correction is limited to the
specific mismatch: move a task from the wrong root, promote it within the same
root, balance the divider, or resize stale bounds. Identical observations do
not repeat a successful shell mutation.

The initial `app + placeholder` target is the one intentional exception: the
stock picker is the visible UI owned by the placeholder pane, so a picker above
that placeholder confirms the pane instead of triggering an endless promotion.
If another eligible app launches fullscreen before that target settles, it
atomically supersedes the placeholder and inherits the placeholder's root.
Mutation retries are keyed by the target and semantic scene progress rather
than raw `am stack list` text. Root/task identity, placement, top ownership,
area mode, and bounds count as progress; visibility-bit flapping alone does
not. The router waits one second between retries, permits at most three
mutations without progress, then abandons the target and accepts the current
scene as a fail-closed baseline. The abandoned scene remains suppressed until
an actual semantic change occurs.

A visible stock picker is also an explicit destination even after an earlier
pair target exists. If the firmware launches the user's next foreground app in
the opposite, usually wider root, that launch supersedes the stale picker
target and only the new foreground task is moved into the picker's root. Apps
merely revealed underneath the moved task are not interpreted as additional
launches.

Once two eligible apps form a confirmed native pair, the reducer persists both
root assignments and the last focused root as a stable pair. A third app uses a
visible or just-observed picker root first. Without an explicit picker, it
replaces the pane opposite the last focus; if focus is unknown, it replaces the
wide pane. Reopening an app already in the pair returns it to its remembered
root. The original stable pair remains the rollback intent until the replacement
is fully placed, promoted, balanced, and resized, so a disappearing app or
bounded-retry failure never turns the launch into a new `app + placeholder`
pair.

For an unfinished target that has no stable pair, a fresh eligible foreground
app is still authoritative. It replaces the target only when a visible picker
or placeholder identifies one unambiguous vacancy; otherwise the stale target
is cancelled and the fresh foreground becomes the baseline without moving
either old member. Home cancels an unfinished target without promoting hidden
tasks. If a non-placeholder target member disappears, the surviving member
becomes the anchor for the known vacancy; if neither remains, the intent is
cleared.

The intent is persisted before a mutation, so an APK replacement or ADB shell
restart can continue an unfinished target. Unknown picker state after restart
is only observed; it is never guessed into a pair. Navigation and Simulcast
task moves clear pending intent and the first snapshot after their quiet period
becomes the new baseline. Navigation holds routing only for the physical
projection/return transition; while it remains on the instrument display,
ordinary IVI launches can still form their own native pair. A supervisor
outside the reducer reconnects the persistent ADB shell without changing the
state-machine rules.

## Historical verdict (incorrect)

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

## Historical conclusion (incorrect)

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
