package dev.denza.apps

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.feature.cluster.CameraRuntimeSnapshot
import dev.denza.apps.feature.cluster.ClusterDisplayResolver
import dev.denza.apps.feature.cluster.ClusterDisplaySelection
import dev.denza.apps.feature.cluster.ClusterSceneService
import dev.denza.apps.feature.adb.AdbRescueCoordinator
import dev.denza.apps.feature.speaker.SpeakerCoverRuntime
import dev.denza.apps.feature.adb.AdbSystemSwitch
import dev.denza.apps.feature.hud.HudGuidanceRuntime
import dev.denza.apps.feature.hud.HudGuidanceSettings
import dev.denza.apps.feature.hud.HudNotificationAccessCoordinator
import dev.denza.apps.feature.hud.HudNotificationArtworkRuntime
import dev.denza.apps.feature.hud.HudSomeIpRuntime
import dev.denza.apps.feature.mirrors.MirrorSide
import dev.denza.apps.feature.mirrors.MirrorWindowDiagnostics
import dev.denza.apps.feature.mirrors.MirrorsPosition
import dev.denza.apps.feature.mirrors.MirrorsSettings
import dev.denza.apps.feature.mirrors.SideCameraDetection
import dev.denza.apps.feature.navigation.NavigationCoordinator
import dev.denza.apps.feature.split.SplitScreenCoordinator

data class SupportDiagnosticsHeader(
    val versionName: String,
    val sdkLevel: Int,
    val fingerprint: String,
    val cameraRuntime: CameraRuntimeSnapshot,
    val mirrorDetection: SideCameraDetection,
    val simulcastRuntime: SimulcastRuntimeSnapshot,
)

/** Builds the support report outside the UI state facade. */
object SupportDiagnostics {
    fun build(context: Context, fseInstaller: FeatureSnapshot): String {
        val displays = ClusterDisplayResolver.candidates(context)
        val bodyLines = buildList {
            add(
                "DiShare=" +
                    yesNo(isInstalled(context.packageManager, SimulcastCoordinator.DISHARE_PACKAGE)),
            )
            add("Доступ поверх окон=${yesNo(SimulcastCoordinator.hasOverlayPermission(context))}")
            add(
                "Управление интерфейсом=" +
                    yesNo(SimulcastCoordinator.isAccessibilityEnabled(context)),
            )
            add(
                "Сервис трансляции=" +
                    yesNo(SimulcastAccessibilityService.isConnected()),
            )
            addAll(SimulcastScreenDiagnostics.diagnosticLines())
            add("Android displays=${displays.size}")
            val adbRescue = AdbRescueCoordinator.snapshot()
            add(
                "ADB Rescue=" +
                    "phase=${adbRescue.phase.name.lowercase().replace('_', '-')}; " +
                    // The reading that chose that phase. Without it a screenshot of the wrong
                    // state cannot be told from a screenshot of the right one.
                    "adb_enabled=${adbSwitchLabel(adbRescue.systemSwitch)}; " +
                    "pending=${if (adbRescue.requestPending) "да" else "нет"}; " +
                    "attempts=${adbRescue.attemptCount}",
            )
            add("ADB queue recovery=${AdbRescueCoordinator.QUEUE_RECOVERY_STATUS}")
            // The speaker automation's own phase. The tile can only say "working" or not, so when
            // it sits on "working" there is no way from the screen to tell a motor command in
            // flight from a watcher that never finished starting - which is exactly the question
            // this panel exists to answer.
            SpeakerCoverRuntime.snapshot().let { speaker ->
                add(
                    "Крышки динамиков=" +
                        "phase=${speaker.phase.name.lowercase()}; " +
                        "asked=${speaker.raised?.let { if (it) "up" else "down" } ?: "—"}; " +
                        "message=${speaker.message.ifBlank { "—" }}; " +
                        "details=${speaker.details ?: "—"}",
                )
            }
            displays.forEach { display ->
                add(
                    "Android display #${display.id}=" +
                        "name=${display.name.ifBlank { "—" }}; " +
                        "size=${display.width}×${display.height}; " +
                        "dpi=${display.densityDpi}; " +
                        "type=${display.type}; " +
                        "flags=0x${Integer.toHexString(display.flags)}; " +
                        "Denza virtual=${if (display.isOwnVirtualDisplay) "да" else "нет"}",
                )
            }
            add("Трансляция=${enabledLabel(SimulcastIntegration.isEnabled(context))}")
            add("Выбрано приложений=${SimulcastApps.selectedCount(context)}")
            add("Зеркала=${enabledLabel(MirrorsSettings.isEnabled(context))}")
            add(
                "Расположение зеркал=" +
                    if (MirrorsSettings.position(context) == MirrorsPosition.CENTER) {
                        "По центру"
                    } else {
                        "По сторонам"
                    },
            )
            add(
                "Улучшение изображения=" +
                    enabledLabel(MirrorsSettings.processingEnabled(context)),
            )
            add("Состояние зеркал=${mirrorRuntimeLabel(MirrorsSettings.statusDetails(context))}")
            add(
                "Экран приборки=" +
                    clusterSelectionLabel(ClusterDisplayResolver.resolve(context)),
            )
            val navigation = NavigationCoordinator.snapshot()
            add("Навигация=${navigation.message.ifBlank { navigation.phase.name.lowercase() }}")
            val split = SplitScreenCoordinator.snapshot()
            add("Split screen=${split.message.ifBlank { split.phase.name.lowercase() }}")
            // Sixty lines of the split screen's own log used to be spliced in here, on the
            // reasoning that a diagnostic nobody can read is a silent failure - `Log.i` from this
            // application cannot be proven to reach logcat on this firmware. True, and it made this
            // report a log file: the panel a driver opens when something is wrong buried its forty
            // readings under a scrolling transcript of one feature's background work.
            // `SplitDiagnostics.recent` is still there for a session that needs it.
            add("HUD-подсказки=${enabledLabel(HudGuidanceSettings.isEnabled(context))}")
            val hudNotificationAccess = HudNotificationAccessCoordinator.diagnostics(context)
            add(
                "Доступ к уведомлениям HUD=" +
                    yesNo(hudNotificationAccess.accessEnabled),
            )
            add(
                "Восстановление доступа HUD=" +
                    hudNotificationAccess.phase.name.lowercase().replace('_', '-'),
            )
            hudNotificationAccess.lastFailure?.let {
                add("Последняя ошибка доступа HUD=$it")
            }
            val hudArtwork = HudNotificationArtworkRuntime.diagnostics()
            add("Графика HUD из уведомления=${enabledLabel(hudArtwork.flagEnabled)}")
            add("Слушатель уведомлений HUD=${yesNo(hudArtwork.listenerConnected)}")
            add("Стрелка HUD=${hudArtwork.source.name.lowercase().replace('_', '-')}")
            add("Состояние графики HUD=${hudArtwork.detail}")
            hudArtwork.lastFailure?.let { add("Последний fallback HUD=$it") }
            val hudDelivery = HudSomeIpRuntime.snapshot()
            add(
                "Доставка HUD=" +
                    "phase=${hudDelivery.phase.name.lowercase()}; " +
                    "start=${hudDelivery.lastStartResult ?: "—"}; " +
                    "fire=${hudDelivery.lastFireResult ?: "—"}; " +
                    "recovery=${hudDelivery.recoveryAttempts}; " +
                    "details=${hudDelivery.detail}",
            )
            add(
                "Установка FSE=" +
                    fseInstaller.message.ifBlank { fseInstaller.status.name.lowercase() },
            )
            fseInstaller.details?.let { add("Детали FSE=$it") }
            // And the other wall: `FseAppInstaller.diagnosticLines` names every split APK file of
            // every installable application, one line each, sizes and all. That is a question about
            // one install, asked once, and it was being answered on every open.
            add("Данные HUD=${HudGuidanceRuntime.details()}")
        }
        return render(
            SupportDiagnosticsHeader(
                versionName = installedVersionName(context),
                sdkLevel = Build.VERSION.SDK_INT,
                fingerprint = Build.FINGERPRINT,
                cameraRuntime = ClusterSceneService.cameraRuntimeSnapshot(),
                mirrorDetection = MirrorWindowDiagnostics.snapshot(),
                simulcastRuntime = SimulcastRuntimeDiagnostics.snapshot(),
            ),
            bodyLines,
        )
    }

    fun render(header: SupportDiagnosticsHeader, bodyLines: List<String>): String = buildString {
        appendLine("Версия=${header.versionName}")
        appendLine("SDK=${header.sdkLevel}")
        appendLine("Fingerprint=${header.fingerprint}")
        val runtime = header.cameraRuntime
        appendLine(
            "AVC runtime=" +
                "phase=${runtime.phase.name}; " +
                "side=${runtime.side.diagnosticName()}; " +
                "generation=${runtime.generation}; " +
                "details=${runtime.details.ifBlank { "—" }}",
        )
        val detection = header.mirrorDetection
        appendLine(
            "AVC detector=" +
                "side=${detection.recognizedSide.diagnosticName()}; " +
                "candidates=${detection.avcCandidateBlocks}; " +
                "unrecognized=${detection.unrecognizedCandidates}",
        )
        val counters = header.simulcastRuntime
        appendLine(
            "Simulcast counters=" +
                "roots found=${counters.rootsFound}; " +
                "roots missing=${counters.rootsMissing}; " +
                "geometry misses=${counters.geometryParseMisses}; " +
                "unstable=${counters.unstableSamples}; " +
                "relayouts=${counters.appliedRelayouts}; " +
                "semantic rebuilds=${counters.semanticWindowRebuilds}",
        )
        bodyLines.forEach(::appendLine)
    }.trimEnd()

    private fun installedVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()?.ifBlank { null } ?: "—"

    private fun yesNo(value: Boolean) = if (value) "Доступен" else "Недоступен"

    private fun enabledLabel(value: Boolean) = if (value) "Включено" else "Выключено"

    private fun adbSwitchLabel(value: AdbSystemSwitch) = when (value) {
        AdbSystemSwitch.ENABLED -> "включено"
        AdbSystemSwitch.DISABLED -> "выключено"
        AdbSystemSwitch.UNKNOWN -> "не прочитано"
    }

    private fun mirrorRuntimeLabel(value: String): String = when {
        value == "monitor running" -> "Монитор работает"
        value == "monitor stopped" -> "Монитор остановлен"
        value == "disabled after com.byd.avc failure" ->
            "Отключены после сбоя штатной камеры"
        value.startsWith("showing left") -> "Показывается левая камера"
        value.startsWith("showing right") -> "Показывается правая камера"
        value.isBlank() -> "Нет данных"
        else -> value
    }

    private fun clusterSelectionLabel(selection: ClusterDisplaySelection): String =
        when (selection) {
            is ClusterDisplaySelection.Selected -> with(selection.display) {
                "#$id · ${width}×$height · $name"
            }
            is ClusterDisplaySelection.NeedsVerification -> "Нужно выбрать экран"
            ClusterDisplaySelection.Missing -> "Не найден"
        }

    private fun MirrorSide?.diagnosticName(): String = this?.name ?: "NONE"

    private fun isInstalled(packageManager: PackageManager, packageName: String): Boolean = try {
        packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

}
