package dev.denza.apps

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.feature.cluster.CameraRuntimeSnapshot
import dev.denza.apps.feature.cluster.ClusterDisplayDescriptor
import dev.denza.apps.feature.cluster.ClusterDisplayResolver
import dev.denza.apps.feature.cluster.ClusterDisplaySelection
import dev.denza.apps.feature.cluster.ClusterSceneService
import dev.denza.apps.feature.adb.AdbRescueCoordinator
import dev.denza.apps.feature.cloud.CloudLinkReport
import dev.denza.apps.feature.cloud.CloudLinkRuntime
import dev.denza.apps.feature.cloud.CloudLinkSettings
import dev.denza.apps.feature.cloud.CloudLinkStatus
import dev.denza.apps.feature.cloud.CloudNetwork
import dev.denza.apps.feature.cloud.CloudNetworkKind
import dev.denza.apps.feature.speaker.SpeakerCoverRuntime
import dev.denza.apps.feature.adb.AdbSystemSwitch
import dev.denza.apps.feature.hud.HudGuidanceRuntime
import dev.denza.apps.feature.hud.HudGuidanceSettings
import dev.denza.apps.feature.hud.HudNotificationAccessCoordinator
import dev.denza.apps.feature.hud.HudNotificationArtworkRuntime
import dev.denza.apps.feature.hud.HudSomeIpRuntime
import dev.denza.apps.feature.media.MediaKeyReport
import dev.denza.apps.feature.mirrors.MirrorSide
import dev.denza.apps.feature.mirrors.MirrorTurnSignalDiagnostics
import dev.denza.apps.feature.mirrors.MirrorWindowDiagnostics
import dev.denza.apps.feature.mirrors.MirrorsPosition
import dev.denza.apps.feature.mirrors.MirrorsSettings
import dev.denza.apps.feature.mirrors.SideCameraDetection
import dev.denza.apps.feature.navigation.NavigationCoordinator
import dev.denza.apps.feature.split.SplitScreenCoordinator
import dev.denza.apps.feature.trip.SpectrumSource
import dev.denza.apps.feature.trip.TripSession

data class SupportDiagnosticsHeader(
    val versionName: String,
    val versionCode: Long,
    val androidRelease: String,
    val sdkLevel: Int,
    val fingerprint: String,
    val cameraRuntime: CameraRuntimeSnapshot,
    val mirrorDetection: SideCameraDetection,
    val simulcastRuntime: SimulcastRuntimeSnapshot,
)

/**
 * Builds the support report outside the UI state facade: the service's «Технические сведения»,
 * one section a feature, in the words the page shows (see [TechnicalReadings]).
 *
 * The cloud comes first. It is the feature tested on cars nobody here can reach, by owners who
 * send a screenshot of this page, and the first screen of the page is the screenshot.
 */
object SupportDiagnostics {
    fun build(context: Context, fseInstaller: FeatureSnapshot): String {
        val header = SupportDiagnosticsHeader(
            versionName = installedVersionName(context),
            versionCode = installedVersionCode(context),
            androidRelease = Build.VERSION.RELEASE,
            sdkLevel = Build.VERSION.SDK_INT,
            fingerprint = Build.FINGERPRINT,
            cameraRuntime = ClusterSceneService.cameraRuntimeSnapshot(),
            mirrorDetection = MirrorWindowDiagnostics.snapshot(),
            simulcastRuntime = SimulcastRuntimeDiagnostics.snapshot(),
        )
        val displays = ClusterDisplayResolver.candidates(context)
        return TechnicalReadings.render(
            listOf(
                section("Облако", cloudRows(context)),
                appSection(header),
                section("Доступ к машине", accessRows()),
                section("Трансляция", simulcastRows(context, header)),
                section("Зеркала", mirrorsRows(context, header)),
                section("Экран водителя", driverScreenRows(context)),
                section("Разделение экрана", splitRows()),
                section("HUD", hudRows(context)),
                // A refused wheel press goes back to stock routing, whose Play fallback opens the
                // stock local player - exactly what the N9 owner reports. The only other trace was
                // `Log.i` under `DenzaMediaResume`, which this firmware silences with a global
                // `log.tag=M`, and that owner has no host ADB. Whether we hear the key, which
                // session we remember, and what became of the last dozen presses.
                mediaKeySection(MediaKeyReport.lines(SimulcastAccessibilityService.mediaKeySnapshot())),
                section("Динамики", listOf(row("Отчёт о воспроизведении", yesNo(SpeakerCoverRuntime.reporting)))),
                // Анализатор питается тем же захватом, что и автоматика крышек, и когда захвата нет,
                // обе функции молчат одинаково. На экране про это не пишется ни слова (U5), поэтому
                // единственное место, где «столбики не шевелятся» можно отличить от «в машине тихо», -
                // здесь. Живой разбор 27.08.2026 пришлось вести дампами `media.audio_flinger` ровно
                // потому, что этой строки не было.
                section("Анализатор спектра", spectrumRows(spectrumLabel(context))),
                section("Экран справа", fseRows(fseInstaller)),
                section("Экраны Android", displayRows(displays)),
            ),
        )
    }

    /** The steering-wheel key's lines, verbatim, one reading each. */
    internal fun mediaKeySection(lines: List<String>): TechnicalSection =
        section("Кнопка play/pause на руле", lines.map(TechnicalReadings::row))

    /** The version as the service's foot prints it, the Android under it, and the firmware's build. */
    internal fun appSection(header: SupportDiagnosticsHeader): TechnicalSection = section(
        "Приложение",
        listOf(
            row("Версия", "${header.versionName} · сборка ${header.versionCode}"),
            row("Android", "${header.androidRelease} · SDK ${header.sdkLevel}"),
            row("Прошивка", header.fingerprint),
        ),
    )

    /**
     * The cloud link's whole state. Read from what the link's own thread last published, never from
     * the car: this runs on whatever thread asked for a redraw.
     */
    private fun cloudRows(context: Context): List<TechnicalRow> {
        val enabled = CloudLinkSettings.isEnabled(context)
        val network = CloudNetwork.reading(context)
        val car = CloudLinkRuntime.car
        val tile = CloudLinkStatus.words(
            CloudLinkStatus.snapshot(
                enabled = enabled,
                car = car,
                network = network.kind != CloudNetworkKind.NONE,
                failure = CloudLinkRuntime.failure,
            ),
        )
        return CloudLinkReport.rows(
            enabled = enabled,
            tile = tile,
            failure = CloudLinkRuntime.failure,
            network = network,
            car = car,
            readAtMs = CloudLinkRuntime.readAtMs,
            adapter = CloudLinkRuntime.adapter,
            busy = CloudLinkRuntime.busy,
            nowMs = SystemClock.elapsedRealtime(),
        ).map { (key, value) -> row(key, value) }
    }

    private fun accessRows(): List<TechnicalRow> {
        val adbRescue = AdbRescueCoordinator.snapshot()
        return listOf(
            row("Состояние", adbRescue.phase.name.lowercase().replace('_', '-')),
            // The reading that chose that phase. Without it a screenshot of the wrong state cannot
            // be told from a screenshot of the right one.
            row("Отладка ADB в машине", adbSwitchLabel(adbRescue.systemSwitch)),
            row("Запрос ждёт ответа", yesNo(adbRescue.requestPending)),
            row("Отправлено запросов", adbRescue.attemptCount.toString()),
            row("Восстановление очереди", AdbRescueCoordinator.QUEUE_RECOVERY_STATUS),
        )
    }

    private fun simulcastRows(context: Context, header: SupportDiagnosticsHeader): List<TechnicalRow> =
        listOf(
            row("Включена", yesNo(SimulcastIntegration.isEnabled(context))),
            row("Выбрано приложений", SimulcastApps.selectedCount(context).toString()),
            row("DiShare установлен", yesNo(isInstalled(context.packageManager, SimulcastCoordinator.DISHARE_PACKAGE))),
            row("Доступ поверх окон", yesNo(SimulcastCoordinator.hasOverlayPermission(context))),
            row("Управление интерфейсом", yesNo(SimulcastCoordinator.isAccessibilityEnabled(context))),
            row("Служба трансляции подключена", yesNo(SimulcastAccessibilityService.isConnected())),
        ) + SimulcastScreenDiagnostics.diagnosticLines().map(TechnicalReadings::row) +
            row("Счётчики окон", simulcastCounters(header.simulcastRuntime))

    internal fun simulcastCounters(counters: SimulcastRuntimeSnapshot): String =
        "найдено ${counters.rootsFound}, потеряно ${counters.rootsMissing}, " +
            "промахов геометрии ${counters.geometryParseMisses}, нестабильных ${counters.unstableSamples}, " +
            "перекладок ${counters.appliedRelayouts}, пересборок ${counters.semanticWindowRebuilds}"

    private fun mirrorsRows(context: Context, header: SupportDiagnosticsHeader): List<TechnicalRow> = listOf(
        row("Включены", yesNo(MirrorsSettings.isEnabled(context))),
        row(
            "Расположение",
            if (MirrorsSettings.position(context) == MirrorsPosition.CENTER) "По центру" else "По сторонам",
        ),
        row("Улучшение изображения", yesNo(MirrorsSettings.processingEnabled(context))),
        row("Состояние", mirrorRuntimeLabel(MirrorsSettings.statusDetails(context))),
        row("Сигнал поворотников", MirrorTurnSignalDiagnostics.snapshot().compact()),
    ) + avcRows(header)

    /** What the stock camera app is doing, and what we recognise of its windows. */
    internal fun avcRows(header: SupportDiagnosticsHeader): List<TechnicalRow> {
        val runtime = header.cameraRuntime
        val detection = header.mirrorDetection
        return listOf(
            row(
                "Камера AVC",
                "${runtime.phase.name}, сторона ${runtime.side.diagnosticName()}, поколение ${runtime.generation}",
            ),
            row("Камера AVC, подробно", runtime.details),
            row(
                "Окна AVC",
                "сторона ${detection.recognizedSide.diagnosticName()}, кандидатов ${detection.avcCandidateBlocks}, " +
                    "нераспознанных ${detection.unrecognizedCandidates}",
            ),
        )
    }

    private fun driverScreenRows(context: Context): List<TechnicalRow> {
        val navigation = NavigationCoordinator.snapshot()
        return listOf(
            row("Навигация", navigation.message.ifBlank { navigation.phase.name.lowercase() }),
            row("Экран приборки", clusterSelectionLabel(ClusterDisplayResolver.resolve(context))),
        )
    }

    // Sixty lines of the split screen's own log used to be spliced in here, on the reasoning that a
    // diagnostic nobody can read is a silent failure - `Log.i` from this application cannot be
    // proven to reach logcat on this firmware. True, and it made this report a log file.
    // `SplitDiagnostics.recent` is still there for a session that needs it.
    private fun splitRows(): List<TechnicalRow> {
        val split = SplitScreenCoordinator.snapshot()
        return listOf(row("Состояние", split.message.ifBlank { split.phase.name.lowercase() }))
    }

    private fun hudRows(context: Context): List<TechnicalRow> = buildList {
        add(row("Подсказки", yesNo(HudGuidanceSettings.isEnabled(context))))
        val access = HudNotificationAccessCoordinator.diagnostics(context)
        add(row("Доступ к уведомлениям", yesNo(access.accessEnabled)))
        add(row("Восстановление доступа", access.phase.name.lowercase().replace('_', '-')))
        access.lastFailure?.let { add(row("Последняя ошибка доступа", it)) }
        val artwork = HudNotificationArtworkRuntime.diagnostics()
        add(row("Графика из уведомления", yesNo(artwork.flagEnabled)))
        add(row("Слушатель уведомлений", yesNo(artwork.listenerConnected)))
        add(row("Стрелка", artwork.source.name.lowercase().replace('_', '-')))
        add(row("Состояние графики", artwork.detail))
        artwork.lastFailure?.let { add(row("Последний fallback", it)) }
        val delivery = HudSomeIpRuntime.snapshot()
        add(
            row(
                "Доставка",
                "${delivery.phase.name.lowercase()}, start ${delivery.lastStartResult ?: "—"}, " +
                    "fire ${delivery.lastFireResult ?: "—"}, восстановлений ${delivery.recoveryAttempts}",
            ),
        )
        add(row("Доставка, подробно", delivery.detail))
        add(row("Данные", HudGuidanceRuntime.details()))
    }

    /** The analyser's line, one reading a row; the panel that never opened is one row saying so. */
    internal fun spectrumRows(label: String): List<TechnicalRow> =
        if ('=' in label) label.split("; ").map(TechnicalReadings::row) else listOf(row("Состояние", label))

    // And the other wall: `FseAppInstaller.diagnosticLines` names every split APK file of every
    // installable application, one line each, sizes and all. That is a question about one install,
    // asked once, and it was being answered on every open.
    private fun fseRows(fseInstaller: FeatureSnapshot): List<TechnicalRow> = buildList {
        add(row("Установка", fseInstaller.message.ifBlank { fseInstaller.status.name.lowercase() }))
        fseInstaller.details?.let { add(row("Подробно", it)) }
    }

    private fun displayRows(displays: List<ClusterDisplayDescriptor>): List<TechnicalRow> =
        listOf(row("Всего", displays.size.toString())) + displays.map { display ->
            row(
                "Экран #${display.id}",
                "${display.name.ifBlank { "—" }} · ${display.width}×${display.height} · dpi ${display.densityDpi} · " +
                    "type ${display.type} · flags 0x${Integer.toHexString(display.flags)}" +
                    if (display.isOwnVirtualDisplay) " · наш виртуальный" else "",
            )
        }

    private fun section(title: String, rows: List<TechnicalRow>) = TechnicalSection(title, rows)

    private fun row(key: String, value: String) = TechnicalRow(key, value.ifBlank { "—" })

    private fun installedVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()?.ifBlank { null } ?: "—"

    private fun installedVersionCode(context: Context): Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    }.getOrDefault(0L)

    private fun spectrumLabel(context: Context): String =
        spectrumLabel(TripSession.existingHub()?.spectrum?.diagnostics(context))

    /**
     * Чистая часть строки анализатора: только из снимка, без контекста и без хаба.
     *
     * Отдельная функция, потому что различить «нет захвата», «захват есть, но эффект выключен» и
     * «эффект включён, а кадров нет» - это и есть весь смысл строки, и проверять это на живой
     * машине с играющей музыкой ради формата текста незачем.
     */
    internal fun spectrumLabel(state: SpectrumSource.Diagnostics?): String {
        if (state == null) return "панель не открывалась"
        val frames = state.sinceLastFrameMs
        return "разрешение=${if (state.granted) "есть" else "нет"}; " +
            "захват=${if (state.running) "запрошен" else "не запрошен"}; " +
            "привязан=${if (state.attached) "да" else "нет"}; " +
            "эффект=${state.effectEnabled?.let { if (it) "включён" else "ВЫКЛЮЧЕН" } ?: "нет"}; " +
            "кадр=${frames?.let { "${it} мс назад" } ?: "не приходил"}; " +
            "ошибка=${state.lastFailure ?: "—"}"
    }


    private fun yesNo(value: Boolean) = if (value) "да" else "нет"

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
