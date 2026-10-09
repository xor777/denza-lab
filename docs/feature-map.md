# Feature map

Where each dashboard tile, and each surface that is not a tile, lives in the code: the tile's
builder, what its press does, its panel, the runtime behind it, its settings, its docs, its
Luminofor fixtures and its tests. Start here when a task names something the driver sees
(«Экран водителя», «Облако») rather than a class.

Paths without a leading `apps/`, `docs/`, `tools/` or `libraries/` are under
`apps/denza-apps/src/main/java/dev/denza/apps/`. Code is named by symbol, never by line.
`FeatureMapContractTest` resolves every backticked name on this page and fails when one goes
stale, and when a tile has no section here under the name the dashboard shows. Change this page
in the same commit as the rename.

## Shared plumbing

Every tile goes through the same six places, named once here; each section below follows one
tile through them.

- `TileId` (`ui/dashboard/TileId.kt`) — the tile's identity and `TileId.feature`, the
  `FeatureId` (`core/FeatureModels.kt`) of the runtime behind it, if any.
- `DashboardTiles` (`ui/dashboard/DashboardTiles.kt`) — one private builder per tile, listed in
  `DashboardTiles.of`; the Russian name, the state line, the tone, the action. Every tile with a
  feature behind it writes its state line through `DashboardTiles.caption`: waiting, refused
  (`ERROR`) or absent (`UNAVAILABLE`) shows the feature's own message, or «Не переключилось» /
  «Недоступно» when it has none, and never the words of the settled switch.
- `DashboardPress` (`ui/dashboard/DashboardActions.kt`) — what a press does
  (`DashboardPress.perform`) and which `FeatureSnapshot` a tile reads
  (`DashboardPress.snapshotOf`); the callbacks themselves are the fields of `DashboardActions`.
  A tile waiting on `FeatureResolution.CHECK_ACCESS` - a local ADB failure, read by `AdbProblem`,
  which every feature tile says as «Нет доступа» (`FeatureWords`) - runs
  `DashboardActions.onCheckAdbAccess` (`DenzaAppRepository.checkAdbAccessThen`, the passive
  check) and retries only if the car still trusts the app; otherwise the startup gate comes up.
  `TileCaptionContractTest` holds every caption a feature can write when it did not settle to the
  tile's 17 characters.
- `FeatureSheet` (`ui/dashboard/FeatureSheets.kt`) — the panel a long press opens: one private
  `…Sheet` function per tile, the paragraph in `helpOf`, the button in `panelAction`.
- `DenzaAppRepository` and its `DenzaUiState` (`DenzaAppRepository.kt`) — the state every tile
  reads and the setters every callback ends in; `MainActivity` binds the callbacks to it and
  `DenzaAppsRoot` (`ui/DenzaAppsScreen.kt`) threads them to the dashboard and decides which panel
  a long press opens.
- `DenzaStatePublisher` (`DenzaStatePublisher.kt`) — the one writer of the features' part of
  `DenzaUiState`. The state is read slice by slice (`StateSlice`, one `SliceReading` each, in
  `StateSlices.kt`, read by `DenzaAppRepository.readSlice`) into plain values: an application is
  named by its package and drawn from `AppIcons` (`rememberAppIcon`), so two reads of the same car
  are equal and publish nothing. A feature whose state changed marks its slice and returns at once:
  `StateMarks.mark` from a feature, `DenzaAppRepository.invalidate` inside the repository, with the
  `StateSlice` (or the `FeatureId` behind the tile, `StateSlice.of`). Where the slice reads a plain
  field, the mark is in the field's setter, so the write cannot happen without it. Marks that arrive before the read are folded into one; reads run one at a time
  on the publisher's own thread and each is committed before the next, so an older read never
  lands over a newer one. A state that is not a reading — a switch's «starting» — goes through
  `DenzaStatePublisher.publish` on the same queue, so no read taken before it can put the old state
  back. `DenzaAppRepository.refresh` marks every slice, for the paths that cannot say what changed:
  the activity's resume, the gate's first look when the app starts or recovers after a reboot, and
  the runtime's start. Every read is counted in «Сервис» → «Технические
  сведения» → «Пересчёт состояния» (`StateRecomputes`), and a slice that throws is left out of
  its read and counted there, while the others are still laid (`readEach`).
  What the tests hold: `DenzaStatePublisherTest` the order, the folding and that no state is lost
  or rolled back; `StateSlicesTest` that equal reads are equal states and that no two slices lay
  one field; `TileSliceContractTest` that every tile is moved by the slice it reads, or is named as
  publishing itself; `StateMarksTest` that the writers that run on the JVM mark their slice as they
  write - a share starting or ending (`SimulcastIntegration`), a speaker report
  (`SpeakerCoverRuntime.reporting`), the cloud link's passes and presses
  (`CloudLinkController.publish`, `CloudLinkRuntime.busy`), a package change
  (`DefaultAppsCatalogCache.invalidate`), HUD guidance starting or stopping (`HudGuidanceRuntime`)
  and an accessibility repair (`AccessibilityRepairSingleFlight`); and `SplitSessionPublicationTest`
  the split's. No test sees the marks that need Android to run. They are kept by their code alone
  and, if one were lost, the next resume would still show the truth: the mirrors' monitor
  (`SideCameraMonitorService`), the accessibility service connecting or going
  (`SimulcastAccessibilityService`), notification access (`MediaSessionAccess`,
  `YandexNotificationArtworkListener`), the automatic ADB restore (`AdbRestore`), the navigation
  coordinator's and the wheel button's callbacks, the switches' setters, the overlay grant and the
  display watchers (`DenzaAppRepository.watchOverlayGrant`, `DenzaAppRepository.watchDisplays`),
  and the weather observer (`WeatherProcessContractTest` checks its wiring as text).

## Which tile is which

| The driver sees | `TileId` | Code | Read first |
| --- | --- | --- | --- |
| «Экран водителя» | `CLUSTER` | `feature/navigation/`, `feature/cluster/` | `docs/instrument-display-findings.md` |
| «Трансляция» | `SIMULCAST` | `SimulcastCoordinator` and its neighbours in the root package, `feature/simulcast/`, `libraries/dishare-bridge/` | `docs/dishare-api-notes.md` |
| «Зеркала» | `MIRRORS` | `feature/mirrors/` | `docs/instrument-display-findings.md` |
| «Разделение» | `SPLIT` | `feature/split/` | `docs/split-screen-product-contract.md` |
| «HUD Подсказки» | `HUD` | `feature/hud/`, inside `SimulcastAccessibilityService` | `docs/instrument-display-findings.md` |
| «Погода» | `WEATHER` | `feature/weather/` | `docs/weather-adapter-findings.md` |
| «Динамики» | `SPEAKERS` | `feature/speaker/` | `docs/speaker-lift-findings.md` |
| «Язык системы» | `LOCALE` | `feature/locale/` | `docs/system-language.md` |
| «Экран справа» | `PASSENGER` | `feature/fse/` | `docs/fse-app-installation.md` |
| «Shortcuts» | `DEFAULT_APPS` | `feature/defaultapps/` | `docs/shortcuts-automation-findings.md` |
| «Облако» | `CLOUD` | `feature/cloud/` | `docs/telematics/README.md` |
| «Сервис» | `SERVICE` | `ui/ServicePanel.kt`, `SupportDiagnostics` | `docs/adb-authorization-recovery.md` |

Not tiles, further down: the strip under the tiles, the driver's-display instruments, the local-ADB
gate, runtime start and recovery, the accessibility service, and the split wait «Бригада».

## Tiles

### «Экран водителя» — `CLUSTER`

Puts one thing on the instrument cluster behind the wheel (this app's own instruments, the Contour, or any launchable app moved there as a task) and takes it back on the next press; turn-by-turn hints on the windscreen are the separate `HUD` tile, not this one.

- **Tile:** `DashboardTiles.cluster`; feature `FeatureId.NAVIGATION`; state `DenzaUiState.navigation` (plus `DenzaUiState.navigationAppChoice`, `DenzaUiState.navigationPlacement`, `DenzaUiState.navigationSteeringWheelButton`), the `StateSlice.NAVIGATION` slice. Marked by `NavigationCoordinator` on every session change, by the wheel button's switch, by `SimulcastCoordinator.repairAccess` as a repair starts and as it settles (the row shows it), and with the other `StateSlice.ACCESSIBILITY` slices when the accessibility service connects or goes.
- **Press / long press:** `DashboardPress.perform` → `TileAction.CLUSTER_PROJECT` → `DashboardActions.onNavigationAction` (bound in `MainActivity.onCreate`) → `DenzaAppRepository.performNavigationAction` → `NavigationCoordinator.performPrimaryAction` → `NavigationCoordinator.showDashboardOnCluster` (instruments, via `ClusterSceneService.showDashboard`) or `NavigationCoordinator.projectToCluster` (an app, via `NavigationProxyClient`). When the snapshot waits on `FeatureResolution.SELECT_CLUSTER_DISPLAY`: `DashboardActions.onOpenClusterPicker` → `DenzaAppsRoot` shows `ClusterDisplayPickerDialog` (which, while it has no screen to offer, re-reads only the displays through `DenzaAppRepository.searchClusterDisplays`) → `DenzaAppRepository.selectClusterDisplay`. The ★ wheel key: `SimulcastAccessibilityService.onKeyEvent` → `SteeringWheelKeyInterceptor` → `DenzaAppRepository.performNavigationActionFromSteeringWheel`. Long press: `DashboardActions.onOpenSettings` → `FeatureSheet`.
- **Panel:** `clusterSheet` in `ui/dashboard/FeatureSheets.kt` (rows «Что показывать», «Размещение» and «Кнопка ★ на руле»). Its page is `NavigationAppChooser` in `ui/AppPickers.kt`: it loads through `DashboardActions.onLoadNavigationAppChoices` → `DenzaAppRepository.refreshNavigationAppChoices` and saves the choice through `DenzaAppRepository.selectNavigationApp`.
- **Runtime:** In `feature/navigation/`: `NavigationCoordinator` (an object, not in the manifest; started by `DenzaAppRepository.startAdbRuntime`), `ProjectablePackages` (decides which packages may go; checked again inside the shell-UID `ClusterProxyMain`), `NavigationAppChoices` (the chooser's list, in the root package) and `SteeringWheelNavigationAccessCoordinator`; the scene's right to draw over other windows is `OverlayGrant.ensure` (`adb/OverlayGrant.kt`), which uses ADB only when the right is not held; the tile's captions are `NavigationWords`, the cold start's wait `NavigationLaunchWait`. In `feature/cluster/`: `ClusterSceneService` (a manifest service that draws on the cluster) and `ClusterDisplayResolver`. The instruments are `ClusterDashboardView` and `ContourScene` in `feature/cluster/dashboard/`.
- **Settings:** `NavigationSettings` (key `denza_navigation`: chosen package, placement, ★ button); `ClusterDisplayResolver` (key `denza_cluster`: hand-picked cluster display).
- **Docs:** `docs/energy-display-contract.md` (normative for the Contour's figures), `tools/design-canvas/luminofor/README.md` (normative for the Contour's look), `docs/instrument-display-findings.md` ("Navigation projection", "App-owned instrument dashboard").
- **Luminofor:** fixtures `sheet-cluster`, `one-sheet-cluster`, `sheet-driver-apps`, `one-sheet-driver-apps` in `apps/denza-apps/src/debug/assets/luminofor/fixtures.json`. The Contour itself has fixtures `cluster-city`, `cluster-park`, `cluster-charging`, `cluster-unavailable` and eight more cluster-… keys, drawn by `drawCluster` in `tools/design-canvas/luminofor/luminofor.js`.
- **Tests:** `DriverScreenChoicesTest`, `NavigationModelsTest`, `NavigationChoiceOrderTest`, `NavigationOneTapSourceContractTest`, `NavigationProxyClientTest`, `ClusterProxyMainTest`, `SteeringWheelNavigationButtonTest`, `ClusterDisplayResolverTest`, `ContourFixturesContractTest`, `NavigationWordsTest`.

### «Трансляция» — `SIMULCAST`

Casts apps to the car's other screens (passenger, rear) through the stock DiShare share dialog, which this app redraws to offer the up to six apps chosen here.

- **Tile:** `DashboardTiles.simulcast`; feature `FeatureId.SIMULCAST`; state `DenzaUiState.simulcast` (plus `DenzaUiState.selectedApps`, `DenzaUiState.selectedAppCount`), the `StateSlice.SIMULCAST` slice. Marked by its switch and its row, by `SimulcastIntegration` as the share `SimulcastOverlayService` starts, replaces or ends is written, by `SimulcastCoordinator.reconcile` (its `Refresh` and `Repaired` events mark the slice; `Repairing`, `Blocked` and `RepairFailed` publish their own state through `DenzaStatePublisher.publish`), by this app's overlay grant changing - granted by any of its features or taken away in Settings, watched by `DenzaAppRepository.watchOverlayGrant` - with `StateSlice.ACCESSIBILITY` (the accessibility service connected or gone) and with `StateSlice.PACKAGES` (a package installed, replaced or removed).
- **Press / long press:** `DashboardPress.perform`. While the feature is on, the press is `TileAction.SIMULCAST_LAUNCH` → `DashboardActions.onLaunchSimulcast` (bound in `MainActivity.onCreate`) → `DenzaAppRepository.launchSimulcast` → `SimulcastCoordinator.reconcile`, then it opens the stock DiShare app (`SimulcastCoordinator.DISHARE_PACKAGE`). While it is off, the press is `TileAction.TOGGLE` → `DashboardActions.onToggleSimulcast` → `DenzaAppRepository.setSimulcastEnabled`. Waiting on `FeatureResolution.SELECT_APPS`: `DashboardActions.onChooseApps` → `DenzaAppRepository.showAppPicker` (`AppPickerDialog`). Retry: `DashboardActions.onRepairSimulcast` → `DenzaAppRepository.repairSimulcast`. Long press: `DashboardActions.onOpenSettings` → `FeatureSheet`.
- **Panel:** `simulcastSheet` in `ui/dashboard/FeatureSheets.kt` (switch «Поддержка трансляции», row «Что транслировать», footer «Запустить»). Its page is `SimulcastAppChooser` in `ui/AppPickers.kt`: it loads through `DashboardActions.onLoadAppChoices` → `DenzaAppRepository.refreshAppChoices`, off the main thread, from the launcher catalog `DefaultAppsCatalogCache` that «Что показывать» and the default-app roles share (`SimulcastAppChoices` keeps what the launcher shows), and toggles apps through `DenzaAppRepository.toggleAppSelection`, which moves the marks without reading the package manager.
- **Runtime:** This lives in the root package, not in `feature/simulcast/`. `SimulcastAccessibilityService` is a manifest accessibility service: it watches DiShare's dialog and redraws its app row, and it also hosts HUD guidance and the ★ wheel key. `SimulcastOverlayService` is a manifest service that starts the cast through `DiShareProjectionBridge`, draws the exit control, and drops both when DiShare ends the share on its side (`DiShareShareSession`). `SimulcastCoordinator` does setup and access repair; `SimulcastWindowReconciler` is also here. `feature/simulcast/` holds `SimulcastVideoSizeResolver` and `ScreenTarget`; DiShare itself sits in `libraries/dishare-bridge/` (`DiShareProjectionBridge`, `DiShareScreens`, and `DiShareBinding` under both).
- **Settings:** `SimulcastIntegration` (key `simulcast_integration`: on/off; the app being cast is held in memory for the life of the process, not stored); `SimulcastApps` (key `simulcast_apps`: up to six chosen packages).
- **Docs:** `docs/dishare-api-notes.md`.
- **Luminofor:** fixtures `sheet-simulcast`, `sheet-cast-apps`, `one-sheet-cast-apps` in `apps/denza-apps/src/debug/assets/luminofor/fixtures.json`; shared tile face.
- **Tests:** `SimulcastCoordinatorTest`, `SimulcastWindowReconcilerTest`, `SimulcastDialogVisibilityTrackerTest`, `SimulcastGeometryStabilizerTest`, `SimulcastAccessibilityAccessTest`, `SimulcastVideoSizeResolverTest`, `ScreenTargetTest`, `AppPickersTest`, `SimulcastAppChoicesTest`, `DiShareBindingStateTest`, `DiShareShareSessionTest`.

### «Зеркала» — `MIRRORS`

While a turn signal blinks, that side's camera appears on the instrument cluster (at its own side, or both stacked in the centre) and goes away when the signal stops.

- **Tile:** `DashboardTiles.mirrors`; feature `FeatureId.MIRRORS`; state `DenzaUiState.mirrors` (plus `DenzaUiState.mirrorsPosition`, `DenzaUiState.mirrorsProcessing`), the `StateSlice.MIRRORS` slice. `SideCameraMonitorService` records each status change once (`MirrorsSettings.setObserved`) and marks the slice under its transition gate; its settings and the instruments' screen choice mark it too, and so does a display coming or going (`StateSlice.DISPLAYS`).
- **Press / long press:** `DashboardPress.perform` → `TileAction.TOGGLE` → `DashboardPress.toggle` → `DashboardActions.onToggleMirrors` (bound in `MainActivity.onCreate`) → `DenzaAppRepository.setMirrorsEnabled` → `MirrorsSettings.setEnabled` → `DenzaAppRepository.reconcileMirrors` → `SideCameraMonitorService.start` (when switched off: `SideCameraMonitorService.stop`). Retry: `DashboardPress.retry` → `DashboardActions.onToggleMirrors`. Long press: `DashboardActions.onOpenSettings` → `FeatureSheet`. The panel's «Проверить камеры»: `DashboardActions.onPreviewMirrors` → `DenzaAppRepository.previewMirrors` → `ClusterSceneService.preview`.
- **Panel:** `mirrorsSheet` in `ui/dashboard/FeatureSheets.kt`: the switch, «Где показывать» (`DashboardActions.onMirrorsPosition`) and «Улучшение изображения» (`DashboardActions.onMirrorsProcessing`).
- **Runtime:** In `feature/mirrors/`: `SideCameraMonitorService` (a manifest foreground service: it follows the turn lamps through `DenzaVehicleSignals` and opens the AVC camera), `AvcCameraRenderer`, `AvcStockClient` (sets the stock turn-camera choice and gives it back), `MirrorTransitionGate` and `MirrorDisplayReadiness` (the tile snapshot). The picture is drawn by `ClusterSceneService` on the display that `ClusterDisplayResolver.resolveCameraOverlay` picks.
- **Settings:** `MirrorsSettings` (key `mirrors`: on, position, processing, earlier stock choice).
- **Docs:** `docs/instrument-display-findings.md` ("Mirrors behavior preserved in Denza Apps", "The firmware-model contract"), `docs/vehicle-data-findings.md` (the turn-lamp FID).
- **Luminofor:** fixtures `sheet-mirrors`, `sheet-broken` (the error state) in `apps/denza-apps/src/debug/assets/luminofor/fixtures.json`; shared tile face.
- **Tests:** `MirrorTransitionGateTest`, `MirrorTransitionReducerTest`, `MirrorTurnSignalShadowTest`, `MirrorSwitchPreemptionTest`, `MirrorStockChoicePolicyTest`, `MirrorDisplayReadinessTest`, `SideCameraWindowDetectorTest`, `CameraSceneContentContractTest`.

### «Разделение» — `SPLIT`

Opens two apps side by side on the central screen through the firmware's own split, by the same flow as the Split Screen launcher icon that this tile's panel shows or hides.

- **Tile:** `DashboardTiles.split`; feature `FeatureId.SPLIT_SCREEN`; state `DenzaUiState.splitScreen` (plus `DenzaUiState.splitJournal` for «Сервис»), read as `StateSlice.SPLIT_SCREEN` from the launcher icon and `SplitScreenCoordinator.snapshot`. The core marks that slice (`DenzaAppRepository.invalidate`) only when its `SplitScreenSession` changes, so neither its actor nor a tap on the main thread waits for the dashboard; the launcher icon changing outside the toggle arrives as this package changing, `StateSlice.PACKAGES`.
- **Press / long press:** `DashboardPress.perform` → `TileAction.SPLIT_LAUNCH` → `DashboardActions.onLaunchSplitScreen` (bound in `MainActivity.onCreate`) → `DenzaAppRepository.launchSplitScreen` → `SplitLauncherEntryActivity` → `SplitScreenCoordinator.openPickerSession` → `SplitCoordinatorCore.openPickerSession`, which opens the pickers (`SplitPickerActivity`). The press never turns the feature off; while it is off, `SplitScreenToggleController.launch` first turns it on by the panel switch's own path (the one under `DenzaAppRepository.setSplitScreenEnabled`: launcher icon, runtime and firmware signals together) and then opens (contract 1.2.8). Retry: `DashboardPress.retry` → `DashboardActions.onToggleSplitScreen`. Long press: `DashboardActions.onOpenSettings` → `FeatureSheet`.
- **Panel:** `splitSheet` in `ui/dashboard/FeatureSheets.kt` has one switch, «Значок на рабочем столе»: `DashboardActions.onToggleSplitScreen` → `DenzaAppRepository.setSplitScreenEnabled` → `SplitScreenToggleController.setEnabled` → `SplitLauncherIconController.setVisible` and `SplitScreenCoordinator.setEnabled`. The footer button «Разделить экран» does the same as the tile press.
- **Runtime:** In `feature/split/`: `SplitScreenCoordinator` (an object, started by `DenzaAppRepository.startAdbRuntime`) over `SplitCoordinatorCore` and `SplitAutomaton`. Manifest components: `SplitLauncherEntryActivity` (NoDisplay; the launcher alias points to it), `SplitPickerActivity` (runs in the `:picker` process), `SplitCommandProvider` (the picker's Binder way in) and `SplitNativePickerAccessibilityService` (picker events). Also here: the waiting animation `SplitCrewView` and the journal `SplitWorkJournal`.
- **Settings:** `SplitLauncherIconController` (the switch is the launcher alias's enabled state); `SplitScreenSettings` (key `denza_split_screen`: toggle snapshot, firmware values to restore).
- **Docs:** `docs/split-screen-product-contract.md` (normative), `docs/split-screen-findings.md`.
- **Luminofor:** none: no sheet fixture; shared tile face. The waiting animation has its own board, `tools/design-canvas/split-crew/split-crew.html` (`SplitCrewScene`).
- **Tests:** `SplitScreenToggleControllerTest`, `SplitCoordinatorCoreTest`, `SplitAutomatonTest`, `SplitScenarioTest`, `SplitSessionPublicationTest`, `SplitOperationRunnerTest`, `SplitPickerGridTest`, `SplitPickerWaitTest`, `SplitCrewBoardContractTest`, `SplitWorkJournalTest`.

### «HUD Подсказки» — `HUD`

Repeats Yandex Navigator's turn-by-turn hints (manoeuvre, distance) on the windscreen head-up display by reading the navigator, not by projecting a picture.

- **Tile:** `DashboardTiles.hud`; feature `FeatureId.HUD_GUIDANCE`; state `DenzaUiState.hudGuidance`, the `StateSlice.HUD_GUIDANCE` slice. Marked by its switch and access steps, by `HudGuidanceRuntime` itself when guidance starts or stops (not on every sample), with `StateSlice.ACCESSIBILITY` and with `StateSlice.PACKAGES` (the navigator installed or removed).
- **Press / long press:** `DashboardPress.perform` → `TileAction.TOGGLE` → `DashboardPress.toggle` → `DashboardActions.onToggleHudGuidance` (bound in `MainActivity.onCreate`) → `DenzaAppRepository.setHudGuidanceEnabled` → `HudGuidanceSettings.setEnabled` and `SimulcastAccessibilityService.requestHudGuidanceRefresh`. Access comes through `HudNotificationAccess.ensure` (the shared listener grant, `MediaSessionAccess`, asked only while guidance is on) and `SimulcastCoordinator.repairAccess`. Retry: `DashboardPress.retry` → `DashboardActions.onToggleHudGuidance`. With the navigator missing (unavailable), the press is `DashboardActions.onOpenSettings`. Long press: `DashboardActions.onOpenSettings` → `FeatureSheet`.
- **Panel:** `hudSheet` in `ui/dashboard/FeatureSheets.kt`: one switch, «Подсказки на проекции», and no footer button.
- **Runtime:** `feature/hud/` has no service of its own. `HudGuidanceAccessibilityMonitor` runs inside `SimulcastAccessibilityService` (manifest). It reads Yandex through `YandexGuidanceAccessibilityReader` and `YandexNotificationArtworkListener` (a manifest notification listener), parses with `YandexGuidanceParser` and sends over SOME/IP with `HudSomeIpClient`. The limit on Yandex's speed sign also goes to the car's own sign: `HudNativeSpeedLimitRunner` writes it through the shell when `HudNativeSpeedLimitEngine` says so, and `HudNativeSpeedLimitProtocol` holds the commands. State is kept in `HudGuidanceRuntime` and `HudArApproximationTracker`; `HudRouteFreshness` decides, poll by poll, which source the HUD shows and when its route is lost. The tile snapshot is `HudGuidanceStatus.snapshot`, which `DenzaAppRepository.evaluateHudGuidance` feeds.
- **Settings:** `HudGuidanceSettings` (key `denza_hud_guidance`: on/off; navigator fixed to Yandex).
- **Docs:** `docs/instrument-display-findings.md` ("HUD turn-by-turn guidance"), `docs/hud-projection-findings.md` (the HUD as a display, which is not this tile).
- **Luminofor:** none: no sheet fixture; shared tile face.
- **Tests:** `YandexGuidanceParserTest`, `YandexNotificationGuidanceTest`, `HudRoadPacketTest`, `HudNativeSpeedLimitTest`, `HudRouteFreshnessTest`, `HudManeuverStockIdTest`, `HudSomeIpRuntimeTest`, `HudArApproximationTest`, `MediaSessionAccessTest`, `HudNotificationArtworkTest`, `SingleFlightReadRunnerTest`, `HudGuidanceStatusTest`.

### «Погода» — `WEATHER`

The car's own weather widget keeps getting a fresh forecast for where the car is; nothing of ours draws weather.

- **Tile:** `DashboardTiles.weather`; no runtime feature (`TileId.feature` is null); state `DenzaUiState.weatherEnabled`, `DenzaUiState.weatherTemperature`, `DenzaUiState.weatherUpdatedMillis` — read from `WeatherAdapterState` as the `StateSlice.WEATHER` slice: on every `DenzaAppRepository.refresh`, and through `DenzaAppRepository.refreshWeather`, which `WeatherAdapterState.observe` calls after every run. The switch publishes its own position at once (`DenzaStatePublisher.publish`).
- **Press / long press:** always `TileAction.TOGGLE`: `DashboardBody` → `DashboardPress.perform` → `DashboardPress.toggle` → `DashboardActions.onSetWeatherEnabled` (bound in `MainActivity`, handed down by `DenzaAppsRoot`) → `DenzaAppRepository.setWeatherEnabled` → `WeatherAdapterState.setEnabled` + `WeatherAdapterScheduler.ensureScheduled` / `WeatherAdapterScheduler.cancel`. Long press: `DashboardActions.onOpenSettings` → `DenzaAppsRoot` → `FeatureSheet`.
- **Panel:** `weatherSheet` (switch «Данные для виджета», age line via `DashboardTiles.ago`) in `apps/denza-apps/src/main/java/dev/denza/apps/ui/dashboard/FeatureSheets.kt`, inside `FeatureSheet`; paragraph from `helpOf`; no footer button (`panelAction`).
- **Runtime:** `feature/weather/` — `WeatherAdapterScheduler` (AlarmManager every 10 min, `WeatherAdapterConfig`), `WeatherAdapterReceiver` (manifest receiver for the alarm), `WeatherAdapterService` (manifest foreground service, in the app's own process since 2026-10-06) → `WeatherAdapterController` (`WeatherLocationSource`, `AndroidWeatherGeocoder`, `MetNorwayClient`, `NativeWeatherPayload`) → `NativeWeatherStore` writes the stock weather provider. `SimulcastAccessibilityService.onAccessibilityEvent` calls `WeatherAdapterScheduler.onNativeWeatherVisible` when the stock weather app comes up.
- **Settings:** `WeatherAdapterState` (switch, default on; last temperature; last success; next alarm).
- **Docs:** `docs/weather-adapter-findings.md`.
- **Luminofor:** fixtures: none for the panel (no weather sheet board); the tile face is on `main-sound` and every other head board — shared tile face.
- **Tests:** `WeatherProcessContractTest`, `DashboardTilesTest`, `WeatherForecastCachePolicyTest`, `WeatherCodeMapperTest`, `WeatherLocationLabelTest`, `RuntimeRecoveryManifestContractTest`, `DenzaProcessPolicyTest`.

### «Динамики» — `SPEAKERS`

The motorised speaker covers come up for music the car does not report itself (third-party players), and «Поднять» brings them up on demand; only the car puts them away.

- **Tile:** `DashboardTiles.speakers`; feature `FeatureId.SPEAKER_COVERS`; state `DenzaUiState.speakerCovers` (built by `SpeakerCoverStatus.snapshot` in the `StateSlice.SPEAKER_COVERS` slice, needs notification access `MediaSessionAccess.isEnabled`) and `DenzaUiState.speakerCoversReporting` (from `SpeakerCoverRuntime.reporting`). Marked by its switch, by `SpeakerCoverRuntime.reporting` changing as a report goes out or lands, and when notification access is repaired or its listener disconnects.
- **Press / long press:** `DashboardPress.perform` → `DashboardPress.toggle` (or `DashboardPress.retry` while it waits) → `DashboardActions.onToggleSpeakerCovers` → `DenzaAppRepository.setSpeakerCoversEnabled` → `SpeakerCoverSettings.setEnabled` + `SpeakerCoverService.reconcile`. Long press → `FeatureSheet`; its «Поднять» → `DashboardActions.onRaiseSpeakerCovers` → `DenzaAppRepository.raiseSpeakerCovers` → `SpeakerCoverService.raise`.
- **Panel:** `speakerSheet` (switch «Автоуправление динамиками», button «Поднять»; help lists `SpeakerCoverApps.EXAMPLES`) in `apps/denza-apps/src/main/java/dev/denza/apps/ui/dashboard/FeatureSheets.kt`.
- **Runtime:** `feature/speaker/` — `SpeakerCoverService` (manifest-declared foreground service, started by `SpeakerCoverService.reconcile` from `DenzaAppRepository.startAdbRuntime`) hears players through `SpeakerMediaSessionObserver` (a subscriber of the process's `MediaSessionHub`; `SpeakerPlayback` says what counts as playing) and `SpeakerCoverService.onForegroundPackage` (called by `SimulcastAccessibilityService`), asks `SpeakerCoverPolicy`, and reports once through `SpeakerCoverTransport` (`SpeakerCoverProtocol.reportPlayingCommand` over `DenzaLocalAdb`); `SpeakerCoverReporting` and `SpeakerCoverApps` say which players the car already covers. Nothing is written to the car's own auto-lift setting.
- **Settings:** `SpeakerCoverSettings` (the switch only, default off).
- **Docs:** `docs/speaker-lift-findings.md`.
- **Luminofor:** fixtures `sheet-speakers` in `apps/denza-apps/src/debug/assets/luminofor/fixtures.json`; shared tile face and shared sheet drawing in `tools/design-canvas/luminofor/luminofor.js`.
- **Tests:** `SpeakerCoverPolicyTest`, `SpeakerPlaybackTest`, `MediaSessionHubTest`, `SpeakerCoverStatusTest`, `SpeakerCoverProtocolTest`, `SpeakerCoverReportingTest`, `SpeakerCoverAppsTest`, `SpeakerCoverFlagContractTest`, `DashboardTilesTest`.

### «Язык системы» — `LOCALE`

The tile names the language the whole car speaks, and a press opens the car's own hidden list of forty languages, which applies the choice system-wide without a reboot.

- **Tile:** `DashboardTiles.locale`; no runtime feature; state `DenzaUiState.systemLanguage` (`SystemLanguageSnapshot`, the `StateSlice.SYSTEM_LANGUAGE` slice: read on every `DenzaAppRepository.refresh` and, through `DenzaAppRepository.refreshSystemLanguage`, whenever «Сервис» opens).
- **Press / long press:** `DashboardPress.perform` (`TileAction.LANGUAGE_PICK`) → `DashboardActions.onOpenSystemLanguage` → `DenzaAppRepository.openSystemLanguage` → `SystemLanguage.open` (intent `SystemLanguage.PICKER_ACTION`). Long press: `DashboardActions.onOpenSettings` → `FeatureSheet`, whose button «Выбрать язык» is the same press (`primaryLabel`).
- **Panel:** `FeatureSheet` in `apps/denza-apps/src/main/java/dev/denza/apps/ui/dashboard/FeatureSheets.kt` with no body of its own: header, paragraph from `helpOf`, footer button.
- **Runtime:** `feature/locale/` — `SystemLanguage` (object: opens the firmware picker, names the current system locale). No service, no manifest entry, nothing written by this app.
- **Settings:** none (the car's system locale is the state).
- **Docs:** `docs/system-language.md`.
- **Luminofor:** fixtures `sheet-locale`; shared tile face and shared sheet drawing.
- **Tests:** `SystemLanguageTest`, `DashboardTilesTest`, `DenzaUiStateStoreTest`.

### «Экран справа» — `PASSENGER`

Copies an app installed on the head unit to the front passenger's own computer (the FSE) and installs it there, so there is nothing to switch on, only an app to choose.

- **Tile:** `DashboardTiles.passenger`; feature `FeatureId.FSE_INSTALLER`; state `DenzaUiState.fseInstaller` (plus `DenzaUiState.fseInstallApps`, `DenzaUiState.fseInstallerPickerVisible`).
- **Press / long press:** Both gestures open the chooser, never a panel. Press: `DashboardPress.perform` → `TileAction.PASSENGER_INSTALL` → `DashboardActions.onChooseFseApp`. Long press: `DashboardActions.onOpenSettings` → `DenzaAppsRoot`, which sends it on to `DashboardActions.onChooseFseApp`. From there (bound in `MainActivity.onCreate`): `DenzaAppRepository.showFseInstallerPicker` → `FseInstallerPickerDialog` → `DenzaAppRepository.installOnPassengerScreen` → `FseAppInstaller.install`. Retry: `DashboardPress.retry` → `DashboardActions.onChooseFseApp`.
- **Panel:** none. `FseInstallerPickerDialog` in `ui/AppPickers.kt` is the whole UI; its list comes from `FseAppInstaller.installedApps` and is filtered by `fseChooserApps`. The branch for this tile in `FeatureSheet` can never be reached.
- **Runtime:** `feature/fse/`: `FseAppInstaller` (an object; nothing in the manifest). It copies the APK over local ADB (`DenzaLocalAdb`) and asks the FSE to install it over the vendor cross-device channel; `FseCrossResponseSession` and `FseInstallResponseWaiter` wait for the answer. It runs on the `DenzaAppRepository` executor and is guarded by `DenzaAppRepository.claimFseInstall`.
- **Settings:** none. The last result lives only in `DenzaUiState.fseInstaller`.
- **Docs:** `docs/fse-app-installation.md`, `research/fse-firmware/README.md`.
- **Luminofor:** none: no fixture for the chooser; shared tile face.
- **Tests:** `FseAppInstallerTest`, `AppPickersTest`, `DashboardTilesTest`, `TileCaptionContractTest`.

### «Shortcuts» — `DEFAULT_APPS`

The car's own navigation, music and video Shortcuts commands open the driver's chosen apps instead of the stock ones (AutoVoice PersonBean roles); one press hands all three back to stock and back again.

- **Tile:** `DashboardTiles.defaultApps`; no runtime feature; state `DenzaUiState.defaultApps` (`DefaultAppsUiState`; the switch is `DefaultAppsUiState.substituting`, read off the car, not stored).
- **Press / long press:** `TileAction.TOGGLE` when `DefaultAppsUiState.substituting` or `DefaultAppsUiState.canSubstitute`, else the panel: `DashboardPress.toggle` → `DashboardActions.onSetDefaultAppsEnabled` → `DenzaAppRepository.setDefaultAppsEnabled` → `DenzaAppRepository.applyDefaultAppTargets` → `DefaultAppRoleRepository.set`. Long press: `DashboardActions.onOpenSettings` → `DenzaAppsRoot` (calls `DenzaAppRepository.refreshDefaultApps`) → `DefaultAppsSheet`; a row choice → `DenzaAppRepository.selectDefaultApp`.
- **Panel:** `DefaultAppsSheet` (title «Приложения по умолчанию», switch «Заменять приложения», page `DefaultAppsChooserPage`) in `apps/denza-apps/src/main/java/dev/denza/apps/ui/dashboard/DefaultAppsSheet.kt`, opened directly by `DenzaAppsRoot`, not through `FeatureSheet`.
- **Runtime:** `feature/defaultapps/` — no service or manifest entry; `DefaultAppRoleRepository` (ContentResolver read/write of the PersonBean rows, `AutoVoicePersonBeanProtocol`), `DefaultAppRole` (the three role keys and stock packages), `DefaultAppsCatalog` / `DefaultAppsCatalogCache` (launchable apps; its package receiver also feeds `NavigationRoleRepair`, which `DenzaAppRepository.repairNavigationRole` uses to put the navigator back after AutoVoice drops it on a Store update), `DefaultAppsPolicy`. Related, no tile: the steering-wheel Play/Pause in `feature/media/` (`SimulcastAccessibilityService.onKeyEvent` → `MediaResumeController.onKeyEvent`, policy `MediaResumeCore`) surfaces only as «Кнопка play/pause на руле» in «Сервис» → «Технические сведения» (`SupportDiagnostics.mediaKeySection` from `MediaKeyReport.lines`).
- **Settings:** `DefaultAppsSettings` (per role: first-run marker, remembered pick, last confirmed).
- **Docs:** `docs/shortcuts-automation-findings.md` (PersonBean roles; normative wheel Play/Pause contract).
- **Luminofor:** fixtures `sheet-defaults`; shared tile face and shared sheet drawing.
- **Tests:** `DefaultAppsPolicyTest`, `DefaultAppRoleRepositoryTest`, `NavigationRoleRepairTest`, `DefaultAppsCatalogTest`, `DefaultAppsSheetTest`, `DashboardTilesTest`, `MediaResumeCoreTest`, `MediaResumeKeyInterceptorTest`, `MediaKeyDiagnosticsTest`.

### «Облако» — `CLOUD`

The car's stock cloud client gets online over ordinary internet (Wi-Fi, or mobile data from a local SIM), so the official Denza phone app sees the car's charge and range.

- **Tile:** `DashboardTiles.cloud`; feature `FeatureId.CLOUD_LINK`; state `DenzaUiState.cloudLink` (from `CloudLinkRuntime.snapshot` in the `StateSlice.CLOUD_LINK` slice; words `CloudLinkStatus.words`), `DenzaUiState.cloudLinkBusy`, `DenzaUiState.cloudWifiRetained`. `CloudLinkController` marks the slice on every press (`CloudLinkRuntime.busy`) and every publication of its worker (`CloudLinkController.publish`), ticks included, which also carries the readings that age with the clock.
- **Press / long press:** `DashboardPress.perform` (ignored while `DenzaUiState.cloudLinkBusy`) → `DashboardPress.toggle` (after a refusal it asks the same wish again) → `DashboardActions.onToggleCloudLink` → `DenzaAppRepository.setCloudLinkEnabled` → `CloudLinkController.switchOn` / `CloudLinkController.switchOff`. Long press → `FeatureSheet`; second switch → `DashboardActions.onSetCloudWifiRetained` → `DenzaAppRepository.setCloudWifiRetained` → `CloudLinkController.setWifiRetained`.
- **Panel:** `cloudSheet` (switches «Поддерживать связь с облаком», «Держать Wi-Fi включенным») in `apps/denza-apps/src/main/java/dev/denza/apps/ui/dashboard/FeatureSheets.kt`.
- **Runtime:** `feature/cloud/` — `CloudLinkService` (manifest-declared foreground service that holds the adapter and watches the default network; `CloudLinkService.reconcile` from `DenzaAppRepository.startAdbRuntime`), `CloudLinkController` (reads the car, runs steps; `CloudLinkController.refresh` from `DenzaAppRepository.refreshCloudLink` on resume), `CloudLinkCore` (pure policy), `CloudLinkFailures` (a refused press against a failed automatic pass, and what clears each), `CloudLinkProtocol` (shell commands to the stock client), `CloudNetwork` (usable internet), `CloudLinkDiagnostics` and `CloudLinkReport` (exported report, the «Облако» section of «Сервис»).
- **Settings:** `CloudLinkSettings` (declared in `feature/cloud/CloudLinkStatus.kt`: wish, pending disable, awaiting TCP down).
- **Docs:** `docs/telematics/cloud-tile.md`, `docs/telematics/README.md`.
- **Luminofor:** fixtures `sheet-cloud`, `one-sheet-cloud`; shared tile face and shared sheet drawing.
- **Tests:** `CloudTilePressTest`, `CloudLinkCoreTest`, `CloudLinkFailuresTest`, `CloudLinkStatusTest`, `CloudLinkRecoveryTest`, `CloudLinkProtocolTest`, `CloudNetworkTest`, `CloudLinkReportTest`, `CloudNativeLogTest`.

### «Сервис» — `SERVICE`

One door to what is wrong right now, the app's access to the car, the instruments' screen choice, and the technical readings an owner screenshots for support.

- **Tile:** `DashboardTiles.service` (built last from the other eleven; its count is `DashboardTiles.attentionTiles`); no runtime feature; state `DenzaUiState.technicalDetails`, `DenzaUiState.splitJournal`, `DenzaUiState.adbRescue`, `DenzaUiState.adbRestore`, `DenzaUiState.clusterDisplayLabel`.
- **Press / long press:** both open the same panel: `DashboardActions.onOpenService` (`TileAction.SERVICE_OPEN`) or `DashboardActions.onOpenSettings` → `DenzaAppsRoot` (calls `DenzaAppRepository.refreshScreenDiagnostics` and `DenzaAppRepository.refreshSystemLanguage`) → `ServicePanel`. While the ADB gate is up, seven taps on the title of `AdbExplainerSheet` (`ServiceEntryTaps`) open it instead. A trouble row opens that tile's own panel.
- **Panel:** `ServicePanel` in `apps/denza-apps/src/main/java/dev/denza/apps/ui/ServicePanel.kt` — pages `ServicePage` (`ServiceMain`, `ServiceScreenPage`, `ServiceTechnicalPage`, `ReadingsPage`), decisions in `ServiceModel`. Access buttons → `DenzaAppRepository.checkAdbAccess`, `DenzaAppRepository.requestAdbAuthorizationOnce`, `DenzaAppRepository.allowNewAdbAuthorizationAttempt`; «Приборный экран» → `DenzaAppRepository.selectClusterDisplay`. «Восстановление ADB» (`ServicePage.RESTORE`) → `AdbRestore.setEnabled`; opening the service calls `AdbRestore.trigger("settings")`.
- **Runtime:** no service; the report and the split's journal are built only while the panel stands: `DenzaAppsRoot` opens them with `DenzaAppRepository.setServiceReportOpen` and `ServiceReport` builds them on its own thread at once and every second, behind the ADB gate too (a recompute behind the gate publishes only `behindAdbGate` and the restore state). `SupportDiagnostics.build` renders the report with `TechnicalReadings.render`, read back by `TechnicalReadings.parse`. Sections come from feature code: `CloudLinkReport.rows`, `AdbRestoreReport.rows`, `AdbPortRestoreReport.rows`, `MediaKeyReport.lines` (`SimulcastAccessibilityService.mediaKeySnapshot`, ring in `MediaKeyDiagnostics`), `SupportDiagnostics.splitJournal`, and the last section, «Пересчёт состояния», from `StateRecomputes` (`RecomputeLog`: how many times the dashboard state was rebuilt, how long each took and on which thread).
- **Settings:** «Восстановление ADB» is on by default (`AdbRestorePreferences`, `adb_restore`, `adb_restore_enabled`); «Приборный экран» stores its override with `ClusterDisplayResolver.saveOverride`.
- **Docs:** `docs/adb-authorization-recovery.md` (the tile and the gate's door), `docs/shortcuts-automation-findings.md` (play/pause lines), `docs/telematics/cloud-tile.md` («Облако» section).
- **Luminofor:** fixtures `sheet-service`, `sheet-service-trouble`, `one-sheet-service-trouble`, `sheet-service-access`, `sheet-service-screen`, `sheet-service-restore`, `one-sheet-service-restore`, `sheet-service-technical`, `sheet-service-split`, `sheet-service-journal`; the technical page's blocks are built by techBlocks in `tools/design-canvas/luminofor/fixtures.js` (same parse rule as `TechnicalReadings`).
- **Tests:** `ServiceModelTest`, `SupportDiagnosticsTest`, `BehindAdbGateTest`, `TechnicalReadingsTest`, `MediaKeyDiagnosticsTest`, `RecomputeLogTest`, `ServiceReportTest`, `DashboardTilesTest`.

## Not tiles

### Trip strip under the tiles

- **What it is:** two pages one swipe apart, remembered by `StripPageSettings`: page 1 sound (`StripPage.SOUND`: track, trip readings, spectrum analyser) and page 2 the car (`StripPage.VEHICLE`: pack flow, volts, five temperatures, engine, last 10 km).
- **Entry points:** `DenzaAppsRoot` hands `SpectrumPanel` to `DashboardBody` as its strip (not drawn while the ADB gate blocks) → `TripPanelView` (frame loop, swipe) → `TripPanelRenderer` draws a `StripModel` filled by `StripReadings`. Sound page: `NowPlayingSource`, `SpectrumSource` → `SpectrumRenderer`, trip from `TripSession.hub` (`TripSensorHub`, `TripEngine`). Car page: `VehiclePageRenderer`, `VehiclePageWords`, data from `VehicleSession.hub` (`VehicleTelemetryHub`, claimed as `VehicleWatcher.STRIP` only while that page is on screen).
- **Surface:** `ui/SpectrumPanel.kt`, `feature/trip/`, `feature/vehicle/`.
- **Docs:** `docs/energy-display-contract.md` (normative for the car page), `docs/audio-capture-findings.md` (analyser capture), `docs/vehicle-data-findings.md`, `tools/design-canvas/luminofor/README.md`.
- **Luminofor:** fixtures `main-sound`, `main-first`, `main-paused`, `two-sound`, `two-first`, `one-sound`, `main-car`, `main-car-engine`, `main-car-hot`, `main-car-neutral`, `main-car-charging`, `two-car`, `one-car`; drawn by `drawHead` in `tools/design-canvas/luminofor/luminofor.js`; debug mapping `StripFixtures`.
- **Tests:** `StripBoardContractTest`, `StripGeometryTest`, `StripReadingsTest`, `SpectrumAnalysisTest`, `TripEngineTest`, `EnergyReadoutsTest`, `VehicleLogReplayTest`, `LuminoforScreenContractTest`.

### Driver's-display instruments (the Contour)

- **What it is:** this app's own instruments on the driver's display, the Contour drawn as Luminofor; one of the «Экран водителя» choices («Приборы», `NavigationAppChoices.instruments`).
- **Entry points:** `NavigationCoordinator` → `ClusterSceneService.showDashboard` (manifest-declared service) adds a `ClusterDashboardView`; each frame `ContourFrameBuilder.build` fills a `ContourFrame` from `VehicleTelemetryHub` (claimed as `VehicleWatcher.CLUSTER`) and `ClusterDashboardRenderer.draw` paints it with `ClusterDashboardLayout`, `ContourGeometry`, `ContourReadout`, `ContourScene`, `ContourMotion`. The energy log is recorded regardless, from `VehicleSession.record` in `DenzaAppsApplication`.
- **Surface:** `feature/cluster/dashboard/`, `feature/vehicle/`.
- **Docs:** `docs/energy-display-contract.md` (normative), `tools/design-canvas/luminofor/README.md` (normative), `docs/instrument-display-findings.md`, `docs/vehicle-data-findings.md`.
- **Luminofor:** fixtures `cluster-city`, `cluster-launch`, `cluster-regen`, `cluster-engine`, `cluster-hot`, `cluster-park`, `cluster-charging`, `cluster-spread`, `cluster-filling`, `cluster-stale`, `cluster-waking`, `cluster-unavailable`; drawn by `drawCluster` in `tools/design-canvas/luminofor/luminofor.js`; debug mapping `ContourFixtures`.
- **Tests:** `ContourFrameBuilderTest`, `ContourGeometryTest`, `ContourFixturesContractTest`, `ContourSceneTest`, `ContourReadoutTest`, `ClusterDashboardLayoutTest`, `EnergyReadoutsTest`, `VehicleLogReplayTest`.

### Local-ADB startup gate and explainer

- **What it is:** almost every feature needs a trusted local ADB shell; until there is one, a blocking overlay covers the dashboard and the strip.
- **Entry points:** `MainActivity` → `DenzaAppRepository.initialize` → `DenzaAppRepository.initializeAdbGate` → `AdbStartupGatePolicy.entryAction` → `DenzaAppRepository.checkAdbAccess` → `AdbRescueCoordinator.checkAccess`. Overlay: `AdbStartupGatePolicy.overlay` → `AdbStartupOverlay` (`AdbStartupPrimaryAction`: check, or `DenzaAppRepository.requestAdbAuthorizationOnce` → `AdbRescueCoordinator.requestOnce`), `AdbRecoveryDialog`, «Что такое ADB» → `AdbExplainerSheet` (copy `AdbExplainer`). On TRUSTED, `DenzaAppRepository.startAdbRuntime` reconciles every feature, and `AdbPortRestore.prepare` grants the app `WRITE_SECURE_SETTINGS` over the trusted shell when it is missing (for reopening port 5555 after a reboot later) and reads what keeps that port open (`AdbPortReadout`). Transport: `DenzaLocalAdb.client` → `LocalAdbClient` (`libraries/dishare-bridge/src/main/java/dev/denza/disharebridge/LocalAdbClient.java`); system switch via `AdbSystemSwitchReader`.
- **Surface:** `ui/DenzaAppsScreen.kt` (overlay and recovery dialog), `ui/AdbExplainerSheet.kt`, `feature/adb/`, `adb/DenzaLocalAdb.kt`.
- **Docs:** `docs/adb-authorization-recovery.md`.
- **Luminofor:** fixtures `modal-adb`, `one-modal-adb`, `modal-adb-wifi` (the gate), `main-car-closed`, `one-car-closed` (the strip without access), `cluster-unavailable` (the instruments without access); drawn by `drawModal` in `tools/design-canvas/luminofor/luminofor.js`.
- **Tests:** `AdbStartupGatePolicyTest`, `AdbRescuePolicyTest`, `AdbExplainerTest`, `AdbSystemSwitchTest`, `AdbPortRestoreTest`, `LocalAdbClientTest`, `AdbProblemTest`.

### ADB restoration after reboot

- **What it is:** stock wireless debugging restores classic 5555 using the already trusted app RSA identity; on by default, autonomous within those preconditions.
- **Entry points:** `AdbRestore.initialize` in the main `DenzaAppsApplication`; process start, Wi-Fi callbacks (local Wi-Fi suffices), `SCREEN_ON`/`USER_PRESENT`, failed repository passive checks and trusted runtime preparation. `AdbRestoreManager` serializes attempts, coalesces hints, guards OFF with generations and owns one finite dialog retry wave per network. `AndroidAdbRestoreSystem` shares `AdbPortRestore.ensurePermission` and `SimulcastCoordinator.repairAccess`; success rejoins the normal runtime recovery.
- **Surface:** `feature/adb/AdbRestoreManager.kt`, `AdbRestorePreferences.kt`, `AndroidAdbRestoreSystem.kt`, `AdbTlsDiscovery.kt`, `WifiDebuggingDialogAutoAllow.kt`, `AdbRestoreReport.kt`; `libraries/dishare-bridge/src/main/java/dev/denza/disharebridge/LocalAdbTlsClient.java`, `AdbCertificate.java`, same `AdbKeyStore` as classic ADB. «Сервис» → «Восстановление ADB» and the support report.
- **Docs:** `docs/adb-authorization-recovery.md` (wireless recovery, requirements and pending vehicle acceptance).
- **Luminofor:** fixtures `sheet-service-restore`, `one-sheet-service-restore`, `modal-adb-wifi`; debug mapping `SheetFixtures`.
- **Tests:** `AdbRestoreManagerTest`, `WifiDebuggingDialogPolicyTest`, `AdbStartupGatePolicyTest`, `LocalAdbTlsClientTest`.

### Runtime start and recovery

- **What it is:** brings every enabled feature back with no activity open: process start, boot or quickboot wake, APK replacement, screen on.
- **Entry points:** `DenzaAppsApplication` (main process only, `DenzaProcessPolicy.shouldBootstrap`; registers `ScreenOnRuntimeRecovery`) → `DenzaRuntimeCoordinator.bootstrap` with a `RuntimeStartCause`. `RuntimeRecoveryReceiver` (manifest; the only owner of boot-completed and package-replaced, filtered by `RuntimeRecoveryActionPolicy.shouldRecover`) → `RuntimeRecoveryService.start` (manifest foreground service, bounded) → the same bootstrap. One cycle at a time (`RuntimeRecoveryCycleState`), retried at `RuntimeAutostartRetrySchedule.atMillis` → `DenzaAppRepository.recoverAutostart` → `AdbAutostartRetryPolicy.action` → `DenzaAppRepository.startAdbRuntime`. The accessibility service also calls `DenzaRuntimeCoordinator.recover` when it connects.
- **Surface:** none on screen; `core/DenzaRuntimeCoordinator.kt`, `core/RuntimeStartCause.kt`, and at the package root `DenzaAppsApplication`, `RuntimeRecoveryReceiver` (Java), `RuntimeRecoveryService`.
- **Docs:** `docs/adb-authorization-recovery.md`, `docs/split-screen-findings.md` (the stock "Disable background Apps" switch, re-armed by every APK install, blocks the boot start).
- **Luminofor:** none.
- **Tests:** `RuntimeRecoveryActionPolicyTest`, `RuntimeRecoveryManifestContractTest`, `RuntimeRecoveryCycleStateTest`, `RuntimeAutostartRetryScheduleTest`, `ScreenOnRecoveryPolicyTest`, `DenzaProcessPolicyTest`.

### The accessibility service and what rides on it

- **What it is:** `SimulcastAccessibilityService` (manifest, config `apps/denza-apps/src/main/res/xml/simulcast_a11y_service.xml`) hosts eight features: «Трансляция» overlay windows over the DiShare dialog (`SimulcastWindowReconciler`, `SimulcastDialogVisibilityTracker`); «HUD Подсказки» (`HudGuidanceAccessibilityMonitor`); the wheel Play/Pause (`SimulcastAccessibilityService.onKeyEvent` → `MediaResumeController.onKeyEvent`, gated by `MediaKeyExperiment` and `MediaButtonEnvironment`); the wheel button of «Экран водителя» (`SteeringWheelKeyInterceptor` → `DenzaAppRepository.performNavigationActionFromSteeringWheel`); «Динамики» (`SpeakerCoverService.onForegroundPackage` on every window change); «Погода» (`WeatherAdapterScheduler.onNativeWeatherVisible`); runtime recovery (`DenzaRuntimeCoordinator.recover` on connect, `DenzaAppRepository.recoverNavigationSteeringWheelAccess` on unbind); and the stock wireless-debugging network dialog (`WifiDebuggingDialogAutoAllow`, exact SystemUI window and `ACTION_CLICK`).
- **Entry points:** switched on over ADB by `SimulcastCoordinator.repairAccess` → `DenzaAccessibilityRepairController.repair` (`AccessibilityServiceSettings`, `AccessibilitySettingsMutationLock`, `AccessibilityRepairSingleFlight`; component names in `SimulcastAccessibilityAccess`). Static hooks: `SimulcastAccessibilityService.isConnected`, `SimulcastAccessibilityService.mediaKeySnapshot`, `SimulcastAccessibilityService.requestHudGuidanceRefresh`, `SimulcastAccessibilityService.requestMediaResumeRefresh`; the two requests may come from any thread and reach their rider on the main thread through `ServiceInstanceHop`. The split has a second service of its own, `SplitNativePickerAccessibilityService`.
- **Surface:** none of its own; health shows in «Сервис» → «Технические сведения» («Служба трансляции подключена» and the play/pause lines).
- **Docs:** `docs/dishare-api-notes.md`, `docs/shortcuts-automation-findings.md`, `docs/split-screen-findings.md`.
- **Luminofor:** none.
- **Tests:** `SimulcastAccessibilityAccessTest`, `ServiceInstanceHopTest`, `AccessibilityRepairSingleFlightTest`, `MediaResumeKeyInterceptorTest`, `SteeringWheelNavigationButtonTest`, `SteeringWheelNavigationAccessTest`, `SimulcastWindowReconcilerTest`, `SimulcastDialogVisibilityTrackerTest`, `SplitNativePickerEventPolicyTest`.

### Split-screen wait «Бригада»

- **What it is:** the opaque full-screen wait while an explicit split open builds its scene: a crew pushing the divider between ⅓ and ⅔, captioned with the overlay text.
- **Entry points:** the split launcher icon or the «Разделение» press (`DenzaAppRepository.launchSplitScreen`) → `SplitLauncherEntryActivity` → `SplitScreenCoordinator.openPickerSession` → `SplitScreenCoordinator.overlayLease` → `SplitLaunchOverlay.begin` (leases in `SplitLaunchOverlayController`, `SplitLaunchOverlay.MIN_VISIBLE_MS` and `SplitLaunchOverlay.MAX_VISIBLE_MS`) → `VehicleProgressOverlay` with `SplitCrewView` as its backdrop, drawing `SplitCrewScene` (one still frame, `SplitCrewScene.STILL_T`, when animations are off). A native edge drag never shows it.
- **Surface:** `feature/split/SplitCrewView.kt`, `feature/split/SplitCrewScene.kt`, `feature/split/SplitLaunchOverlay.kt`, `ui/VehicleProgressOverlay.kt`.
- **Docs:** `docs/split-screen-product-contract.md` (normative; 15 s ceiling 1.3.8), `docs/split-screen-findings.md` ("Explicit restore progress window and bounded close").
- **Luminofor:** none; its own board is `tools/design-canvas/split-crew/` (`tools/design-canvas/split-crew/compare.py`), shown in the debug build by `LuminoforFixtureActivity` with board split-crew.
- **Tests:** `SplitCrewBoardContractTest`, `SplitCrewGeometryTest`, `SplitCrewTimelineTest`, `SplitLaunchOverlayTest`.

## Adding a tile

The «Облако» tile (commit c1875b0b, 21 files) is the worked example. In order:

1. **Identity.** An entry in `TileId` and its branch in `TileId.feature`; a `FeatureId` in
   `core/FeatureModels.kt` when the tile drives a runtime feature.
2. **State.** The feature's fields in `DenzaUiState`; a `StateSlice` (and its branch in
   `StateSlice.of` for a `FeatureId`) with a `SliceReading` that lays those fields, read in
   `DenzaAppRepository.readSlice`; its setters in `DenzaAppRepository`, which publish a transient
   state through `DenzaStatePublisher.publish` rather than writing it; and the `FeatureId` →
   snapshot branch in `DashboardPress.snapshotOf`.
3. **Invalidation.** Every place that changes what the slice reads — a setter, the feature's own
   thread, a callback from the car — marks that slice once it has written: `StateMarks.mark`, in
   the setter of the field when the slice reads a plain field. Nothing else re-reads it: a writer
   without a mark leaves the tile frozen until the next resume. Where the writer runs on the JVM,
   add its case to `StateMarksTest`.
4. **Tile.** A `TileIcon` entry, a builder in `DashboardTiles` listed in `DashboardTiles.of`, the
   glyph in `DenzaIcons` (`design/DenzaIcons.kt`) and its branch in `tileGlyph`
   (`ui/dashboard/DashboardGrid.kt`).
5. **Gestures.** A callback field in `DashboardActions`; the short press in `DashboardPress.perform`
   and its `toggle`, `resolve` and `retry` branches.
6. **Panel.** A `…Sheet` function and its branch in `FeatureSheet`, the paragraph in `helpOf`, the
   button in `panelAction`.
7. **Wiring.** The callbacks threaded through `DenzaAppsRoot` (`ui/DenzaAppsScreen.kt`) and bound
   to the repository in `MainActivity`; `MainActivity.onResume` reads every slice again
   (`DenzaAppRepository.refresh`), and a state the car keeps behind the shell gets its own read on
   resume (as `DenzaAppRepository.refreshCloudLink`).
8. **Runtime.** The feature's package (for example `feature/cloud/`) and any service or permission
   in `apps/denza-apps/src/main/AndroidManifest.xml`.
9. **Board.** The chip in `tools/design-canvas/luminofor/fixtures.js`; the rows in
   `tools/design-canvas/luminofor/luminofor.js` when the count changes them; the panel fixtures
   in `apps/denza-apps/src/debug/assets/luminofor/fixtures.json`. Render with
   `tools/design-canvas/luminofor/shot.py` and lay the debug build over it with
   `tools/design-canvas/luminofor/compare.py` (`tools/design-canvas/luminofor/README.md`).
10. **Tests.** The tile count `FEATURES` in `DashboardLayoutPolicyTest` and
   `LuminoforScreenContractTest`; press and state in `DashboardTilesTest`, plus a focused test of
   the feature's own press (for example `CloudTilePressTest`); `TileSliceContractTest`, which
   wants the tile's slice or its own publishing path named; `StripBoardContractTest` when the
   strip moves; a section on this page for `FeatureMapContractTest`.
11. **Docs.** A row in `docs/project-map.md`, a row in CLAUDE.md's "Read before touching" when the
    feature has its own doc, and that doc's Current state table.
