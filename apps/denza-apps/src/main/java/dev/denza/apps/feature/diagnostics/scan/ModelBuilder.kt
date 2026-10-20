package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.bus.DiagReply
import dev.denza.apps.feature.diagnostics.bus.hex
import dev.denza.apps.feature.diagnostics.ecu.Ecu
import dev.denza.apps.feature.diagnostics.ecu.EcuCatalog
import dev.denza.apps.feature.diagnostics.ecu.EcuGroup
import dev.denza.apps.feature.diagnostics.protocol.DiagDecoder
import dev.denza.apps.feature.diagnostics.protocol.DtcCandidate
import dev.denza.apps.feature.diagnostics.protocol.DtcCode
import dev.denza.apps.feature.diagnostics.protocol.DtcFormat
import dev.denza.apps.feature.diagnostics.protocol.DtcMemory
import dev.denza.apps.feature.diagnostics.protocol.IdentDid
import dev.denza.apps.feature.diagnostics.protocol.Ignition
import dev.denza.apps.feature.diagnostics.protocol.Monitor
import dev.denza.apps.feature.diagnostics.protocol.MonitorStatus
import dev.denza.apps.feature.diagnostics.protocol.ObdDtcMode
import dev.denza.apps.feature.diagnostics.protocol.ObdPids
import dev.denza.apps.feature.diagnostics.protocol.OemDtcIndex
import dev.denza.apps.feature.diagnostics.protocol.Polled
import dev.denza.apps.feature.diagnostics.protocol.quality
import dev.denza.apps.feature.diagnostics.protocol.MonitorState as ObdMonitorState

/** Everything the session knows at one moment, handed to [ModelBuilder] under its lock. */
internal class SessionSnapshot(
    val link: LinkState,
    val phase: ScanPhase,
    val done: Int,
    val finishedAtMillis: Long?,
    val notice: String?,
    val polling: Int?,
    val results: Map<Int, ModuleResult>,
    val idents: Map<Int, IdentResult>,
    val open: Set<Int>,
    val streams: Map<Int, LiveStream>,
    /** The module whose «Потоковые данные» are on screen, and whether its stream may run. */
    val live: Int?,
    val streaming: Boolean,
    val recording: Int?,
    val car: CarReadings,
    val nowMillis: Long,
)

/**
 * [DiagnosticsModel] out of a [SessionSnapshot]: words, figures and states, decided here so the
 * screens never decode a byte or read the catalog (architecture, 7). Names follow the board
 * «Сканер (v3)»; a value nobody read is said to be unread, never zero.
 */
internal class ModelBuilder(
    private val clock: ScanClock,
    /** The public DTC catalog: candidate headings as a record's description (architecture, 4). */
    private val catalog: () -> OemDtcIndex = { OemDtcIndex.EMPTY },
) {
    fun build(s: SessionSnapshot): DiagnosticsModel = DiagnosticsModel(
        header = Header(s.link, carLine(s.car), clock.seconds(s.nowMillis)),
        scan = summary(s),
        groups = EcuGroup.entries.map { group(s, it) },
        modules = (s.results.keys + s.idents.keys + s.open).mapNotNull { r -> EcuCatalog.byRequest(r)?.let { r to module(s, it) } }.toMap(),
        systems = SystemsBuilder.build(s, ::rowStatus),
    )

    // ------------------------------------------------------------------ autoscan

    private fun summary(s: SessionSnapshot): ScanSummary {
        val results = s.results.values
        val answered = results.count { it.answered }
        val scanned = s.phase != ScanPhase.NEVER || results.isNotEmpty()
        val mils = results.mapNotNull { it.mil }
        return ScanSummary(
            phase = s.phase,
            done = s.done,
            total = EcuCatalog.routed.size,
            finishedAt = s.finishedAtMillis?.let { clock.minutes(it, s.nowMillis) },
            answered = "$answered/${EcuCatalog.all.size}",
            active = if (scanned) results.sumOf { it.active ?: 0 } else null,
            history = if (scanned) results.sumOf { it.history ?: 0 } else null,
            memoryRead = "${results.count { it.memoryRead }}/$answered",
            mil = when {
                mils.any { it } -> MilState.ON
                mils.isNotEmpty() -> MilState.OFF
                else -> MilState.UNKNOWN
            },
            notice = s.notice,
        )
    }

    fun rowStatus(s: SessionSnapshot, ecu: Ecu): ModuleStatus = when {
        ecu.request == null -> ModuleStatus.NO_ROUTE
        s.polling == ecu.request -> ModuleStatus.POLLING
        else -> s.results[ecu.request]?.status ?: ModuleStatus.UNREAD
    }

    private fun group(s: SessionSnapshot, g: EcuGroup): ModuleGroupView {
        val rows = EcuCatalog.inGroup(g).map { row(s, it) }
        fun n(vararg st: ModuleStatus) = rows.count { it.status in st }
        val parts = listOfNotNull(
            "${rows.size} бл.",
            n(ModuleStatus.OK, ModuleStatus.HISTORY, ModuleStatus.ACTIVE).takeIf { it > 0 }?.let { "прочитано $it" },
            n(ModuleStatus.ACTIVE).takeIf { it > 0 }?.let { "активные $it" },
            n(ModuleStatus.HISTORY).takeIf { it > 0 }?.let { "история $it" },
            n(ModuleStatus.UNREAD).takeIf { it > 0 }?.let { "DTC не читали $it" },
            n(ModuleStatus.POLLING).takeIf { it > 0 }?.let { "опрос $it" },
            n(ModuleStatus.NO_REPLY).takeIf { it > 0 }?.let { "нет ответа $it" },
            n(ModuleStatus.OTHER_ECU).takeIf { it > 0 }?.let { "другой блок $it" },
            n(ModuleStatus.REFUSED).takeIf { it > 0 }?.let { "отказ $it" },
            n(ModuleStatus.INCONSISTENT).takeIf { it > 0 }?.let { "несогласованный ответ $it" },
            n(ModuleStatus.NO_ROUTE).takeIf { it > 0 }?.let { "нет адреса $it" },
        )
        return ModuleGroupView(GROUP_IDS.getValue(g), g.title, g.short, parts.joinToString(" · "), rows)
    }

    private fun row(s: SessionSnapshot, ecu: Ecu): ModuleRowView {
        val r = ecu.request?.let { s.results[it] }
        val read = r?.memoryRead == true
        return ModuleRowView(
            request = ecu.request,
            abbreviation = ecu.abbreviation,
            unit = unit(ecu),
            name = name(ecu),
            short = ecu.shortName,
            inferred = ecu.inferred,
            route = ecu.request?.let(Figures::route) ?: "—",
            protocol = when {
                ecu.uds && ecu.obd -> "OBD-II, UDS"
                ecu.obd -> "OBD-II"
                ecu.uds -> "UDS"
                else -> "—"
            },
            active = if (read) r?.active?.toString() ?: "—" else "—",
            history = if (read) r?.history?.toString() ?: "—" else "—",
            status = rowStatus(s, ecu),
            target = target(ecu),
        )
    }

    // ------------------------------------------------------------------ one module

    private fun module(s: SessionSnapshot, ecu: Ecu): ModuleView {
        val request = requireNotNull(ecu.request)
        val r = s.results[request]
        val id = s.idents[request]
        return ModuleView(
            request = request,
            name = name(ecu),
            unit = unit(ecu),
            inferred = ecu.inferred,
            line = line(ecu, r, id),
            status = rowStatus(s, ecu),
            dtc = dtc(s, ecu, r),
            ident = ident(ecu, r, id),
            obd = if (ecu.obd) obd(s, ecu, r, id) else null,
        )
    }

    private fun line(ecu: Ecu, r: ModuleResult?, id: IdentResult?): String {
        val parts = mutableListOf(Figures.route(ecu.request!!), longProtocol(ecu))
        (r?.memory as? Polled.Got)?.value?.let { m ->
            parts += "маска доступности ${m.availability.hex}"
            parts += formatLine(m)
        }
        val f195 = (id?.dids?.get(IdentDid.F195) as? Polled.Got)?.value?.text
        val f194 = (r?.identity as? Polled.Got)?.value?.takeIf { ecu.uds }
        when {
            f195 != null -> parts += "ПО (F195) $f195"
            f194 != null -> parts += "ПО (F194) $f194"
        }
        (id?.obdStandard as? Polled.Got)?.value?.let { parts += "стандарт OBD (PID 1C) $it" }
        if (id?.dids?.get(IdentDid.F197).let { it is Polled.Unsupported || it is Polled.Denied }) parts += "F197 не поддерживается"
        return parts.joinToString(" · ")
    }

    private fun dtc(s: SessionSnapshot, ecu: Ecu, r: ModuleResult?): DtcView {
        val obdOnly = !ecu.uds && ecu.obd
        val stamp = r?.read?.atMillis?.let { "снимок ${clock.seconds(it)}" }
        if (obdOnly) {
            val stored = r?.obdLists?.get(ObdDtcMode.STORED) ?: Polled.NotPolled
            val records = r?.obdLists.orEmpty().flatMap { (mode, p) ->
                (p as? Polled.Got)?.value?.codes.orEmpty().map { obdRow(it, mode) }
            }
            val asked = ScanRequests.OBD_LISTS.filter { (_, uds) -> r?.read?.reply(uds) != null }.map { "Mode ${it.second}" }
            return DtcView(
                asks = obdAsks(r),
                records = records,
                quality = stored.quality(s.nowMillis, FRESH_MS),
                outsideMask = false,
                footnote = (asked + listOfNotNull(stamp)).joinToString(" · ").ifEmpty { "не читали" },
            )
        }
        val mask = "%02X".format(ecu.dtcMask)
        val countReply = r?.read?.reply(ScanRequests.count(ecu.dtcMask))
        val listReply = r?.read?.reply(ScanRequests.list(ecu.dtcMask))
        val memory = r?.memory ?: Polled.NotPolled
        val count = countReply?.let { Polled.of(it) { a -> DiagDecoder.dtcCount(a) } }
        val countText = when (count) {
            null -> if (ecu.dtcCountAnswers) "не запрашивали" else "блок не отвечает на 19 01"
            is Polled.Got -> count.value.count.toString()
            else -> word(count)
        }
        val listRow = when {
            listReply != null -> AskRow("Список по той же маске", "19 02 $mask",
                (memory as? Polled.Got)?.value?.records?.size?.toString() ?: word(memory), true)
            (count as? Polled.Got)?.value?.count == 0 -> AskRow("Список по той же маске", "19 02 $mask", "не нужен: счётчик 0", false)
            else -> AskRow("Список по той же маске", "19 02 $mask", "не запрашивали", false)
        }
        val m = (memory as? Polled.Got)?.value
        val records = m?.records.orEmpty().map { rec ->
            val lookup = catalog().lookup(ecu.request!!, rec.code)
            DtcRow(
                code = rec.code.display,
                failureType = rec.code.failureType?.let { "%02X".format(it) } ?: "—",
                failureName = failureName(rec.code),
                group = codeGroup(rec.code),
                note = "",
                status = rec.status.byte,
                availability = m!!.availability.byte,
                description = CatalogText.description(lookup.candidates),
                reference = lookup.candidates.map(CatalogText::reference),
            )
        }
        val asked = listOfNotNull(countReply?.let { "19 01 $mask" }, listReply?.let { "19 02 $mask" })
        return DtcView(
            asks = listOf(
                AskRow("Отказ сейчас или подтверждённые", "19 01 $mask", countText, countReply != null),
                listRow,
                AskRow("Ожидающие", "19 02 04", "не запрашивали", false),
                AskRow("Все записи, с непроверенными", "19 02 FF", "не запрашивали", false),
            ),
            records = records,
            quality = memory.quality(s.nowMillis, FRESH_MS),
            outsideMask = r?.outsideMask ?: false,
            footnote = (asked + listOfNotNull(stamp)).joinToString(" · ").ifEmpty { "не читали" },
            source = CATALOG_SOURCE.takeIf { records.any { it.description != null } },
        )
    }

    private fun obdAsks(r: ModuleResult?): List<AskRow> = ScanRequests.OBD_LISTS.map { (mode, uds) ->
        val p = r?.obdLists?.get(mode) ?: Polled.NotPolled
        AskRow(OBD_LIST_NAMES.getValue(mode), "Mode $uds",
            (p as? Polled.Got)?.value?.codes?.size?.toString() ?: word(p), p !is Polled.NotPolled)
    }

    private fun obdRow(code: String, mode: ObdDtcMode): DtcRow {
        // An OBD mode says one thing about its codes; the other status bits are not reported.
        val bit = if (mode == ObdDtcMode.PENDING) 0x04 else 0x08
        return DtcRow(code, "—", "Mode %02X · %s".format(mode.service, mode.label), codeGroup(code), "", bit, bit)
    }

    private fun ident(ecu: Ecu, r: ModuleResult?, id: IdentResult?): IdentView {
        val own = mutableListOf(Field("Запрос → ответ", Figures.route(ecu.request!!)))
        val muted = setOf("не читали", "нет ответа", "не запрашивали", "шлюз занят", "несогласованный ответ")
        fun field(key: String, value: String, forceMuted: Boolean = false) {
            own += Field(key, value, forceMuted || value in muted || value.startsWith("не отдаёт"))
        }
        if (ecu.uds) {
            field("Протоколы", if (ecu.obd) "OBD-II · UDS (ISO 14229)" else "UDS (ISO 14229)")
            DID_ORDER.forEach { did ->
                val p = id?.dids?.get(did)?.takeIf { it !is Polled.NotPolled }
                    ?: if (did == IdentDid.F194) r?.identity ?: Polled.NotPolled else Polled.NotPolled
                field("${DID_LABELS.getValue(did)} · 22 ${did.hex}", identText(p))
            }
        } else {
            field("Протоколы", "OBD-II (SAE J1979)")
            val name = id?.ecuName?.takeIf { it !is Polled.NotPolled } ?: r?.identity ?: Polled.NotPolled
            field("Имя ЭБУ · 09 0A", identText(name))
            field("Калибровка · 09 04", identText(id?.calibration ?: Polled.NotPolled))
        }
        if (ecu.obd) field("Стандарт OBD · PID 1C", identText(id?.obdStandard ?: Polled.NotPolled))
        (r?.memory as? Polled.Got)?.value?.let {
            field("Формат DTC · 19 01", if (it.format == DtcFormat.OTHER) "не сообщён: 19 01 не отвечает" else "%02X · %s".format(it.format.id, formatName(it.format)))
        }
        if (!ecu.uds) field("UDS 19 и 22", "не поддерживает", forceMuted = true)
        field(if (ecu.uds) "VIN · 22 F190" else "VIN · 09 02", "не запрашивали")

        val cache = mutableListOf(Field("Основание имени", ecu.identity.label))
        ecu.expectedF194?.let { cache += Field("Ожидаемый F194", it) }
        ecu.expectedObdName?.let { cache += Field("Ожидаемое имя · 09 0A", it) }
        ecu.cachedSoftwareCode?.let { cache += Field("Код ПО в кэше", it) }
        ecu.aliases.firstOrNull()?.let { a ->
            cache += Field("Идентификатор", a.ecuId)
            a.softwareVersion?.let { cache += Field("Версия ПО", it) }
            a.hardwareVersion?.let { cache += Field("Версия железа", it) }
        }
        ecu.aliases.drop(1).forEach { cache += Field("Вариант", it.ecuId, muted = true) }
        if (ecu.aliases.isEmpty()) cache += Field("Идентификатор", "нет в кэше обновлений", muted = true)

        val idF194 = (id?.dids?.get(IdentDid.F194) as? Polled.Got)?.value?.text
        val matched = r?.matched ?: idF194?.let { it == ecu.expectedF194 }
        val offered = id?.offered.orEmpty()
        return IdentView(
            own = own,
            cache = cache,
            matched = matched,
            pids = (offered.filterNot(ObdPids::isBitmap) + offered.filter(ObdPids::isBitmap)).map { "%02X".format(it) },
        )
    }

    private fun obd(s: SessionSnapshot, ecu: Ecu, r: ModuleResult?, id: IdentResult?): ObdView {
        val request = ecu.request!!
        val (status, reply) = (id?.monitors as? Polled.Got)?.let { it.value to id.read.reply(ScanRequests.PID_01) }
            ?: (r?.monitors as? Polled.Got)?.let { it.value to r.read.reply(ScanRequests.PID_01) }
            ?: (null to null)
        val raw = (reply as? DiagReply.Answer)?.let { Figures.spaced(hex(it.uds)) } ?: "—"
        val monitors = status?.let(::monitorRows).orEmpty()
        val supported = monitors.count { it.state != MonitorState.UNSUPPORTED }
        val ready = monitors.count { it.state == MonitorState.READY }
        val lists = obdAsks(r).toMutableList()
        if (ecu.uds) {
            val m = r?.memory ?: Polled.NotPolled
            lists += AskRow("Отказ сейчас или подтверждённые, UDS", "19 02 %02X".format(ecu.dtcMask),
                (m as? Polled.Got)?.value?.records?.size?.toString() ?: word(m), m !is Polled.NotPolled)
        } else {
            lists += AskRow("UDS 19 и 22", "—", "не поддерживает", false)
        }
        (id?.thisCycle as? Polled.Got)?.value?.let { cycle ->
            val offered = cycle.monitors.filter { it.state != ObdMonitorState.DISABLED && it.state != ObdMonitorState.NOT_SUPPORTED }
            lists += AskRow("Мониторы этой поездки", "PID 41",
                "готово ${offered.count { it.state == ObdMonitorState.READY }} из ${offered.size}", true)
        }
        val stream = s.streams[request]
        return ObdView(
            mil = when (status?.mil) { true -> MilState.ON; false -> MilState.OFF; null -> MilState.UNKNOWN },
            milLine = status?.let(::milLine) ?: "PID 01 не читали",
            raw = raw,
            readiness = if (status == null) "—" else "$ready/$supported",
            monitors = monitors,
            lists = lists,
            live = stream?.view(
                running = s.live == request && s.streaming,
                recording = s.recording == request,
                engineRunning = s.car.engineRunning,
            ) ?: LiveView(false, "—", emptyList(), emptyList(), s.recording == request),
        )
    }

    private fun milLine(m: MonitorStatus): String = listOfNotNull(
        when (m.mil) { true -> "MIL горит"; false -> "MIL выключена"; null -> null },
        m.dtcCount?.let { "DTC, включивших MIL: $it" },
        if (m.ignition == Ignition.SPARK) "зажигание искровое" else "воспламенение от сжатия",
    ).joinToString(" · ")

    private fun monitorRows(m: MonitorStatus): List<MonitorRow> = m.monitors.map { r ->
        val (name, abbreviation) = MONITOR_NAMES[r.monitor] ?: (r.monitor.label to r.monitor.name)
        val state = when (r.state) {
            ObdMonitorState.READY -> MonitorState.READY
            ObdMonitorState.NOT_READY -> MonitorState.NOT_READY
            ObdMonitorState.NOT_SUPPORTED, ObdMonitorState.DISABLED -> MonitorState.UNSUPPORTED
        }
        val kind = when {
            state == MonitorState.UNSUPPORTED -> "—"
            r.monitor in CONTINUOUS -> "непрерывный"
            else -> "разовый"
        }
        MonitorRow(name, abbreviation, kind, state)
    }.sortedBy { it.state.ordinal }

    companion object {
        /** A scan read older than this is «устарело»; the same age that starts a new scan on opening. */
        const val FRESH_MS = 10 * 60_000L
        const val CAR = "Z9 GT DM"
        /** The footer of a DTC screen that shows a description from the public catalog: said once, not per row. */
        const val CATALOG_SOURCE = "Описания: каталог мануала Z9GT PHEV, перевод"

        private val GROUP_IDS = mapOf(
            EcuGroup.POWERTRAIN to "power", EcuGroup.CHASSIS to "chassis", EcuGroup.ADAS to "assist",
            EcuGroup.BODY to "body", EcuGroup.CABIN to "salon", EcuGroup.CLIMATE to "climate",
            EcuGroup.LOW_VOLTAGE to "lv", EcuGroup.UNKNOWN to "misc",
        )

        private val DID_ORDER = listOf(IdentDid.F18A, IdentDid.F194, IdentDid.F195, IdentDid.F197)
        private val DID_LABELS = mapOf(
            IdentDid.F18A to "Поставщик", IdentDid.F194 to "Номер ПО поставщика",
            IdentDid.F195 to "Версия ПО", IdentDid.F197 to "Имя системы",
        )

        private val OBD_LIST_NAMES = mapOf(
            ObdDtcMode.STORED to "Сохранённые (Stored)",
            ObdDtcMode.PENDING to "Ожидающие (Pending)",
            ObdDtcMode.PERMANENT to "Постоянные (Permanent)",
        )

        private val CONTINUOUS = setOf(Monitor.MISFIRE, Monitor.FUEL_SYSTEM, Monitor.COMPONENTS)
        private val MONITOR_NAMES = mapOf(
            Monitor.MISFIRE to ("Пропуски воспламенения" to "MIS"),
            Monitor.FUEL_SYSTEM to ("Топливная система" to "FUEL"),
            Monitor.COMPONENTS to ("Контроль компонентов" to "CCM"),
            Monitor.CATALYST to ("Катализатор" to "CAT"),
            Monitor.HEATED_CATALYST to ("Подогрев катализатора" to "HCAT"),
            Monitor.EVAP to ("Система улавливания паров" to "EVAP"),
            Monitor.SECONDARY_AIR to ("Вторичный воздух" to "AIR"),
            Monitor.GPF to ("Фильтр частиц бензиновый" to "GPF"),
            Monitor.O2_SENSOR to ("Датчики кислорода" to "O2S"),
            Monitor.O2_HEATER to ("Подогрев датчиков кислорода" to "O2S HTR"),
            Monitor.EGR_VVT to ("Рециркуляция ОГ / фазы ГРМ" to "EGR/VVT"),
            Monitor.NMHC_CATALYST to ("Катализатор NMHC" to "NMHC"),
            Monitor.NOX_SCR to ("Нейтрализатор NOx / SCR" to "NOx"),
            Monitor.BOOST_PRESSURE to ("Давление наддува" to "BP"),
            Monitor.EXHAUST_SENSOR to ("Датчик ОГ" to "EGS"),
            Monitor.PM_FILTER to ("Сажевый фильтр" to "PM"),
        )

        /** «ЭБУ двигателя»; a module whose role is not established is named by its address, as on the board. */
        fun name(ecu: Ecu): String =
            if (ecu.group == EcuGroup.UNKNOWN && ecu.request != null) "Блок ${Figures.address(ecu.request)}" else ecu.name

        /** «ECM · UAES». */
        fun unit(ecu: Ecu): String = ecu.supplier?.let { "${ecu.abbreviation} · $it" } ?: ecu.abbreviation

        fun target(ecu: Ecu): Target? = when {
            ecu.request != null -> Target.Module(ecu.request)
            ecu.abbreviation == "DCOBC" -> Target.System(SystemScreen.HV)
            ecu.group == EcuGroup.POWERTRAIN -> Target.System(SystemScreen.DRIVES)
            else -> null
        }

        fun longProtocol(ecu: Ecu): String = when {
            ecu.uds && ecu.obd -> "OBD-II (SAE J1979) + UDS"
            ecu.obd -> "OBD-II (SAE J1979)"
            else -> "UDS"
        }

        /** «формат DTC 00 (SAE J2012)»; a memory read by its list alone has no format to tell. */
        fun formatLine(m: DtcMemory): String =
            if (m.format == DtcFormat.OTHER) "формат DTC не сообщён" else "формат DTC %02X (%s)".format(m.format.id, formatName(m.format))

        fun formatName(f: DtcFormat): String = when (f) {
            DtcFormat.SAE_J2012_00 -> "SAE J2012"
            DtcFormat.ISO_14229_1 -> "ISO 14229-1"
            else -> f.title
        }

        /** What a reading that is not a value says, in the screens' words. */
        fun word(p: Polled<*>): String = when (p) {
            Polled.NotPolled -> "не читали"
            is Polled.NoReply -> "нет ответа"
            is Polled.Busy -> "шлюз занят"
            is Polled.Denied -> "отказ · NRC ${p.nrc.hex}"
            is Polled.Unsupported -> p.nrc?.let { "не поддерживается · NRC ${it.hex}" } ?: "не поддерживается"
            is Polled.Inconsistent -> "несогласованный ответ"
            is Polled.Got -> "прочитано"
        }

        fun identText(p: Polled<*>): String = when (p) {
            is Polled.Got -> p.value.toString().ifEmpty { "пусто" }
            is Polled.Denied -> "не отдаёт · NRC ${p.nrc.hex}"
            is Polled.Unsupported -> "не отдаёт" + (p.nrc?.let { " · NRC ${it.hex}" } ?: "")
            else -> word(p)
        }

        fun failureName(code: DtcCode): String = when {
            code.format == DtcFormat.ISO_14229_1 -> "Формат 01: тип отказа не выделяется"
            code.format != DtcFormat.SAE_J2012_00 -> "Формат ${"%02X".format(code.formatId and 0xFF)}: сырые байты"
            else -> code.failureTypeName?.replaceFirstChar { it.uppercase() } ?: "Тип отказа не назван в SAE J2012-DA"
        }

        fun codeGroup(code: DtcCode): String = code.base?.let(::codeGroup) ?: when (code.format) {
            DtcFormat.ISO_14229_1 -> "ISO 14229-1 · без буквы системы"
            else -> "формат ${"%02X".format(code.formatId and 0xFF)}"
        }

        /** «Шасси · код производителя (C1xxx)» from a J2012 base code: system letter and code owner. */
        fun codeGroup(base: String): String {
            val letter = base.first()
            val digit = base.getOrNull(1) ?: '0'
            val system = when (letter) { 'P' -> "Силовая"; 'C' -> "Шасси"; 'B' -> "Кузов"; 'U' -> "Сеть"; else -> "?" }
            val owner = when {
                digit == '0' || (letter == 'P' && digit == '2') -> "общий код SAE"
                digit == '3' && letter == 'P' -> "код производителя или SAE"
                digit == '3' -> "резерв SAE"
                else -> "код производителя"
            }
            return "$system · $owner ($letter${digit}xxx)"
        }

        /** «Z9 GT DM · P · 0 км/ч», «Z9 GT DM · P · ДВС работает». */
        fun carLine(car: CarReadings): String {
            val gear = when (car.parked) { true -> "P"; false -> "не P"; null -> "P —" }
            val tail = when {
                car.engineRunning == true -> "ДВС работает"
                car.speedKmh != null -> "${Figures.number(car.speedKmh!!, 0)} км/ч"
                else -> "— км/ч"
            }
            return "$CAR · $gear · $tail"
        }
    }
}

/**
 * The public catalog's headings as a record's screen text (architecture, 4): the Russian heading is the
 * description itself, with no mark and no source - the source is said once, in the screen's footer
 * ([ModelBuilder.CATALOG_SOURCE]). Two different headings of one section are both shown, «A / B»; the
 * rear drives' two sections, when they disagree, as «Л: A / П: B». Nothing else is added.
 */
internal object CatalogText {
    fun description(candidates: List<DtcCandidate>): String? {
        if (candidates.isEmpty()) return null
        val sided = candidates.mapNotNull { it.side }.distinct().size > 1
        return candidates.joinToString(" / ") { c ->
            val side = c.side?.takeIf { sided }?.let { "${it.short}: " } ?: ""
            side + c.titleRu
        }
    }

    fun reference(c: DtcCandidate) = DtcReference(
        code = c.displayCode,
        titleRu = c.titleRu,
        titleZh = c.titleZh,
        locators = c.locators.map { "${it.section.key} · ${it.outlineId}" },
    )
}
