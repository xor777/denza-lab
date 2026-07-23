package dev.denza.apps

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.feature.cluster.CameraRuntimeSnapshot
import dev.denza.apps.feature.cluster.ClusterDisplayResolver
import dev.denza.apps.feature.cluster.ClusterDisplaySelection
import dev.denza.apps.feature.cluster.ClusterSceneService
import dev.denza.apps.feature.hud.HudGuidanceRuntime
import dev.denza.apps.feature.hud.HudGuidanceSettings
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
            add("HUD-подсказки=${enabledLabel(HudGuidanceSettings.isEnabled(context))}")
            add(
                "Установка FSE=" +
                    fseInstaller.message.ifBlank { fseInstaller.status.name.lowercase() },
            )
            fseInstaller.details?.let { add("Детали FSE=$it") }
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
