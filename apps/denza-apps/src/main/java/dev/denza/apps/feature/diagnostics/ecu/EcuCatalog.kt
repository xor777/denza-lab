package dev.denza.apps.feature.diagnostics.ecu

/** Systems as the scanner lists them, in screen order (architecture, 5). */
internal enum class EcuGroup(val title: String, val short: String) {
    POWERTRAIN("СИЛОВАЯ УСТАНОВКА", "Силовая"),
    CHASSIS("ШАССИ И БЕЗОПАСНОСТЬ", "Шасси"),
    ADAS("АССИСТЕНТЫ ВОДИТЕЛЯ", "Ассистенты"),
    BODY("КУЗОВ", "Кузов"),
    CABIN("САЛОН", "Салон"),
    CLIMATE("КЛИМАТ И ТЕРМОРЕГУЛИРОВАНИЕ", "Климат"),
    LOW_VOLTAGE("ЭЛЕКТРОСЕТЬ 12 В", "12 В"),
    UNKNOWN("НАЗНАЧЕНИЕ НЕ УСТАНОВЛЕНО", "Прочие"),
}

/** What the catalog's name for an address rests on (architecture, 5.1). */
internal enum class IdentityBasis(val label: String) {
    /** `22 F194` returned a software code the stock OTA cache keeps under this name. */
    F194("22 F194"),
    /** A stock route or script names the address; F194 was not read. */
    STOCK_ROUTE("маршрут стока"),
    /** OBD `09 0A`: the ECU's own name. */
    OBD_ECU_NAME("OBD 09 0A"),
    /** No route known: the name exists only in the OTA cache. */
    NONE("нет маршрута"),
}

/** One record of the stock OTA cache (`ECUVersion.json`) or route map; versions are null for a route name. */
internal data class CachedIdentity(val ecuId: String, val softwareVersion: String? = null, val hardwareVersion: String? = null)

/**
 * One diagnostic address of this car (Z9GT, profile 170). [inferred] is the screen's `≈`: the
 * role comes from the abbreviation, not from the ECU. [request] is null for the three ECUs the
 * OTA cache knows without a route.
 */
internal data class Ecu(
    val group: EcuGroup,
    val abbreviation: String,
    val name: String,
    val shortName: String,
    val inferred: Boolean,
    val request: Int?,
    val identity: IdentityBasis,
    /** The software code `22 F194` must return; any other means another ECU, and its DTC memory is not read. */
    val expectedF194: String? = null,
    /** The name OBD `09 0A` must return, for an ECU identified that way. */
    val expectedObdName: String? = null,
    /** For an ECU without a route: its software code in the OTA cache. */
    val cachedSoftwareCode: String? = null,
    val aliases: List<CachedIdentity> = emptyList(),
    val supplier: String? = null,
    /** Answers UDS (`22`, `19`). False for the OBD-only brake interface and for ECUs without a route. */
    val uds: Boolean = request != null,
    /** Answers OBD (`01`, `03`, `07`, `0A`, `09`). */
    val obd: Boolean = false,
    /** Its DTC memory was read live through this transport; the rest is the first read (acceptance, step 4). */
    val dtcQualified: Boolean = false,
    /** DTCFormatIdentifier seen in a live `59 01`, null where none was read. */
    val dtcFormat: Int? = null,
    /** The `19 01/02` status mask (architecture, 5.2). */
    val dtcMask: Int = DTC_MASK,
) {
    /** The address the ECU answers on. */
    val reply: Int? get() = request?.plus(8)

    /** `7E0`, or «нет адреса». */
    val address: String get() = request?.let { "%03X".format(it) } ?: "нет адреса"

    /** The abbreviation with the supplier where the cache names one: `ECM (UAES)`. */
    val title: String get() = supplier?.let { "$abbreviation ($it)" } ?: abbreviation

    companion object {
        /** testFailed or confirmedDTC: includes historical records (status 28), excludes untested monitors. */
        const val DTC_MASK = 0x09
    }
}

internal object EcuCatalog {
    /** The corner-radar family: one software code, twelve cached names, four routes. */
    private val CORNER_RADAR_CODE = "474E0011DF82000000"
    private val CORNER_RADAR_ALIASES = listOf(
        "LFCR_BYD_00100001" to "0.0.2", "LFCR_WHST_00100001" to "0.0.2", "LFCR_chengtech_00100001" to "0.0.0",
        "RFCR_BYD_00100001" to "0.0.2", "RFCR_WHST_00100001" to "0.0.2", "RFCR_chengtech_00100001" to "0.0.0",
        "BSD_R_BYD_00100001" to "0.0.2", "BSD_R_WHST_00100001" to "0.0.2", "LRCR_chengtech_00100001" to "0.0.0",
        "BSD_BYD_00100001" to "0.0.2", "BSD_WHST_00100001" to "0.0.2", "RRCR_chengtech_00100001" to "0.0.0",
    ).map { (id, hw) -> CachedIdentity("HTH-2021_$id", "4.00.12", hw) }

    private fun cache(id: String, sw: String, hw: String) = CachedIdentity("HTH-2021_$id", sw, hw)
    private fun route(id: String) = CachedIdentity("HTH-2021_$id")

    private fun radar(abbreviation: String, request: Int, name: String, short: String) = Ecu(
        EcuGroup.ADAS, abbreviation, name, short, inferred = true, request = request, identity = IdentityBasis.F194,
        expectedF194 = CORNER_RADAR_CODE, aliases = CORNER_RADAR_ALIASES,
    )

    /** An ECU whose role is not established: its short name is the cached software version. */
    private fun unknown(abbreviation: String, request: Int, f194: String, vararg aliases: CachedIdentity) = Ecu(
        EcuGroup.UNKNOWN, abbreviation, "Назначение не установлено",
        aliases.firstOrNull()?.softwareVersion?.let { "ПО $it" } ?: "нет в кэше",
        inferred = false, request = request, identity = IdentityBasis.F194, expectedF194 = f194, aliases = aliases.toList(),
    )

    val all: List<Ecu> = listOf(
        // Powertrain
        Ecu(EcuGroup.POWERTRAIN, "ECM", "ЭБУ двигателя", "Двигатель", false, 0x7E0, IdentityBasis.F194,
            expectedF194 = "32440011E004000000", supplier = "UAES",
            aliases = listOf(cache("ECM_UAES_01200001", "4.00.10", "1.0.0")),
            obd = true, dtcQualified = true, dtcFormat = 0x00),
        Ecu(EcuGroup.POWERTRAIN, "VCU", "Контроллер силовой установки", "Силовая уст.", false, 0x7E2, IdentityBasis.F194,
            expectedF194 = "32440011E07E007841",
            aliases = listOf(cache("VCU_BYD_00100001", "4.08.22", "0.0.2"), cache("VCU_UAES_00100001", "4.08.22", "0.0.2")),
            obd = true, dtcQualified = true, dtcFormat = 0x00),
        Ecu(EcuGroup.POWERTRAIN, "FMCU", "Контроллер переднего мотора", "Перед. мотор", false, 0x7E3, IdentityBasis.F194,
            expectedF194 = "48370011E075000000", aliases = listOf(cache("FMCU_BYD_00100001", "4.03.12", "0.0.5")),
            dtcQualified = true, dtcFormat = 0x01),
        Ecu(EcuGroup.POWERTRAIN, "ISG", "Контроллер мотор-генератора", "Мотор-генер.", false, 0x7E6, IdentityBasis.F194,
            expectedF194 = "48370011E0EA000000", aliases = listOf(cache("ISG_BYD_00100001", "4.03.13", "0.0.5")),
            dtcQualified = true, dtcFormat = 0x01),
        Ecu(EcuGroup.POWERTRAIN, "BMS", "Система управления ВВБ", "ВВБ", false, 0x7E7, IdentityBasis.F194,
            expectedF194 = "4A520011E00D00047B", aliases = listOf(cache("BMS_BYD_03300001", "3.48.07", "0.0.3")),
            dtcQualified = true, dtcFormat = 0x00),
        Ecu(EcuGroup.POWERTRAIN, "SMCU", "Контроллер заднего мотора Л", "Задн. мотор", true, null, IdentityBasis.NONE,
            cachedSoftwareCode = "48370011E086001100", aliases = listOf(cache("SMCU_BYD_00200001", "4.00.06", "0.0.2"))),
        Ecu(EcuGroup.POWERTRAIN, "SMCU3", "Контроллер заднего мотора П", "Задн. мотор", true, null, IdentityBasis.NONE,
            cachedSoftwareCode = "48370011E088001100", aliases = listOf(cache("SMCU3_BYD_00100001", "4.00.06", "0.0.2"))),
        Ecu(EcuGroup.POWERTRAIN, "DCOBC", "Бортовое зарядное и DC-DC", "OBC + DC-DC", true, null, IdentityBasis.NONE,
            cachedSoftwareCode = "48350011E04D000100", aliases = listOf(cache("DCOBC_BYD_00100001", "4.00.01", "0.0.2"))),

        // Chassis and safety
        Ecu(EcuGroup.CHASSIS, "ESP/IPB", "Тормозная система, стабилизация", "Тормоза, ESP", false, 0x782, IdentityBasis.F194,
            expectedF194 = "32330011DF0B000101", supplier = "Bosch",
            aliases = listOf(cache("ESP_BOSCH_00100001", "4.00.08", "0.0.4"), cache("IPB_BOSCH_00100001", "4.00.08", "0.0.4"),
                cache("BSC_BYD_00100001", "4.00.08", "0.0.4")),
            dtcQualified = true, dtcFormat = 0x00),
        Ecu(EcuGroup.CHASSIS, "BSCM", "Тормозная система · интерфейс OBD", "Тормоза · OBD", false, 0x7E5, IdentityBasis.OBD_ECU_NAME,
            expectedObdName = "BSCM-BrakeSystem", uds = false, obd = true, dtcQualified = true),
        Ecu(EcuGroup.CHASSIS, "EPS", "Электроусилитель руля", "Рулевое", false, 0x783, IdentityBasis.F194,
            expectedF194 = "43430011DE6F000000",
            aliases = listOf(cache("REPS_BYD_00100001", "4.00.05", "0.0.1"), cache("DPEPS_TRW_00100001", "4.00.05", "0.0.1")),
            dtcQualified = true, dtcFormat = 0x00),
        Ecu(EcuGroup.CHASSIS, "SRS", "Подушки безопасности", "Подушки", false, 0x7F1, IdentityBasis.F194,
            expectedF194 = "54500011E011000012", supplier = "Veoneer",
            aliases = listOf(cache("SRS_VEONEER_00100001", "4.00.04", "0.0.2")),
            dtcQualified = true, dtcFormat = 0x00),

        // Driver assistance
        Ecu(EcuGroup.ADAS, "ADC", "Контроллер ассистентов", "Ассистенты", false, 0x712, IdentityBasis.F194,
            expectedF194 = "48450011DF83000000", aliases = listOf(cache("ADC_BYD_00100001", "4.14.07", "0.0.0")),
            dtcQualified = true, dtcFormat = 0x01),
        Ecu(EcuGroup.ADAS, "MRR", "Радар передний", "Радар перед.", false, 0x7F2, IdentityBasis.F194,
            expectedF194 = "474E0011DF70000000",
            aliases = listOf(cache("MRR_BYD_00100001", "4.00.05", "1.0.0"), cache("MRR_WHST_00100001", "4.00.05", "1.0.0"),
                cache("MRR_chengtech_00100001", "4.00.05", "0.0.0")),
            dtcQualified = true, dtcFormat = 0x00),
        radar("LFCR", 0x760, "Угловой радар передний левый", "Радар ПЛ"),
        radar("RFCR", 0x761, "Угловой радар передний правый", "Радар ПП"),
        radar("RRCR", 0x7C2, "Угловой радар задний правый", "Радар ЗП"),
        radar("LRCR", 0x7D4, "Угловой радар задний левый", "Радар ЗЛ"),
        Ecu(EcuGroup.ADAS, "IPA", "Парковочный ассистент", "Парковка", true, 0x701, IdentityBasis.F194,
            expectedF194 = "32330011DF42000000", supplier = "Bosch",
            aliases = listOf(cache("IPA_BOSCH_00100001", "4.00.09", "0.0.1"))),

        // Body
        Ecu(EcuGroup.BODY, "LA_BCM", "Кузовной контроллер левый", "Кузов слева", false, 0x720, IdentityBasis.STOCK_ROUTE,
            dtcQualified = true, dtcFormat = 0x00),
        Ecu(EcuGroup.BODY, "R_ZCU", "Кузов, зона справа", "Зона справа", true, 0x747, IdentityBasis.STOCK_ROUTE,
            aliases = listOf(route("R_ZCU_BYD_00100001")), dtcQualified = true, dtcFormat = 0x00),
        Ecu(EcuGroup.BODY, "F_ZCU", "Кузов, зона спереди", "Зона спереди", true, 0x722, IdentityBasis.STOCK_ROUTE,
            aliases = listOf(route("F_ZCU_BYD_00100001")), dtcQualified = true, dtcFormat = 0x00),
        Ecu(EcuGroup.BODY, "B_ZCU", "Кузов, зона сзади", "Зона сзади", false, 0x726, IdentityBasis.STOCK_ROUTE,
            aliases = listOf(route("B_ZCU_BYD_00100001")), dtcQualified = true, dtcFormat = 0x00),
        Ecu(EcuGroup.BODY, "FL_L", "Фара левая", "Фара Л", true, 0x770, IdentityBasis.F194,
            expectedF194 = "48330011DF2B000201", aliases = listOf(cache("FL_L_BYD_00100001", "4.00.18", "0.1.3"))),
        Ecu(EcuGroup.BODY, "FL_R", "Фара правая", "Фара П", true, 0x771, IdentityBasis.F194,
            expectedF194 = "48330011DF2C000202", aliases = listOf(cache("FL_R_BYD_00100001", "4.00.18", "0.1.3"))),

        // Cabin
        Ecu(EcuGroup.CABIN, "INS", "Комбинация приборов", "Приборы", true, 0x723, IdentityBasis.F194,
            expectedF194 = "4B450011E0250037B1", aliases = listOf(cache("INS_BYD_00100001", "4.00.04", "0.0.3"))),
        Ecu(EcuGroup.CABIN, "AMP", "Усилитель звука", "Усилитель", true, 0x751, IdentityBasis.F194,
            expectedF194 = "5A4A0011DE4A000000", aliases = listOf(cache("AMP_BYD_00100001", "3.00.03", "0.6.0"))),
        Ecu(EcuGroup.CABIN, "WCT", "Беспроводная зарядка", "Беспр. ЗУ", true, 0x733, IdentityBasis.F194,
            expectedF194 = "47370011DE06000000",
            aliases = listOf(cache("WCT_BJYG_00200001", "3.00.09", "0.0.2"), cache("WCT_TEME_00100001", "3.00.09", "0.0.2"),
                cache("WCT_ADAYO_00100001", "3.00.09", "0.0.2"))),
        Ecu(EcuGroup.CABIN, "SWS", "Кнопки руля", "Кнопки руля", true, 0x711, IdentityBasis.F194,
            expectedF194 = "4D540011DE1E000000",
            aliases = listOf(cache("SWS_BYD_00100001", "4.00.07", "0.0.1"), cache("SWS_JOYSON_00100001", "4.00.07", "0.0.1"))),

        // Climate and thermal management
        Ecu(EcuGroup.CLIMATE, "ACC", "Климат-контроль", "Климат", true, 0x7B3, IdentityBasis.F194,
            expectedF194 = "48440012AD14004501", aliases = listOf(cache("ACC_BYD_00100001", "1.00.07", "0.0.1"))),
        Ecu(EcuGroup.CLIMATE, "TMIM", "Модуль терморегулирования", "Терморег.", true, 0x7B4, IdentityBasis.F194,
            expectedF194 = "344E0011E001620101", supplier = "Tuopu",
            aliases = listOf(cache("TMIM_TUOPU_00100001", "4.00.00", "0.0.5"))),
        Ecu(EcuGroup.CLIMATE, "PTC", "Высоковольтный нагреватель", "Нагреватель", true, 0x766, IdentityBasis.F194,
            expectedF194 = "59340011E015000000", supplier = "HGTECH",
            aliases = listOf(cache("PTC_HGTECH_00300001", "1.00.07", "1.0.2"))),

        // 12 V
        Ecu(EcuGroup.LOW_VOLTAGE, "LBMS", "АКБ 12 В", "АКБ 12 В", false, 0x762, IdentityBasis.STOCK_ROUTE,
            dtcQualified = true, dtcFormat = 0x00),

        // Role not established
        unknown("PDC-VCU", 0x743, "48370011E002066441",
            cache("PDC-VCU_FDP_00100001", "4.17.18", "0.0.0"), cache("PDC-VCU_BYD_00100001", "4.17.18", "0.0.0")),
        unknown("PDCMB", 0x724, "4A440011DF01510001", cache("PDCMB_BYD_00100001", "4.02.03", "0.0.2")),
        unknown("CS", 0x725, "48490011DF1D000000", cache("CS_BYD_00100001", "4.00.04", "0.1.0")),
        unknown("MSRF", 0x713, "48430011AD30000202", cache("MSRF_BYD_00100001", "4.00.08", "0.0.1")),
        unknown("MSRZ", 0x742, "48430011AD1A000201", cache("MSRZ_BYD_00100001", "4.00.08", "0.0.1")),
        unknown("DUCU", 0x750, "4B310011DF016A0001", cache("DUCU_BYD_00100001", "4.00.11", "0.0.3")),
        unknown("PUCU", 0x7B5, "4B310011DF016B0001", cache("PUCU_BYD_00100001", "4.00.10", "0.0.3")),
        unknown("MLUCU", 0x7D2, "4B310011DF016C0001", cache("MLUCU_BYD_00100001", "4.00.08", "0.0.4")),
        unknown("HFRM", 0x754, "48490011AD0C000004", cache("HFRM_BYD_00100001", "4.00.01", "0.0.2")),
        unknown("CCS/SCPA", 0x7A7, "48490011DF55000100",
            cache("CCS_BYD_00100001", "4.00.06", "0.0.5"), cache("SCPA_BYD_00100001", "4.00.06", "0.0.5")),
        unknown("RRCP", 0x7C6, "48580011DF02220000", cache("RRCP_BYD_00100001", "4.00.04", "0.0.3")),
        unknown("SEC", 0x7D6, "484900118C0A001000", cache("SEC_BYD_00100001", "1.11.36", "0.0.1")),
        unknown("—", 0x741, "4B450011E0930034E2"),
    )

    private val byRequest: Map<Int, Ecu> = all.mapNotNull { e -> e.request?.let { it to e } }.toMap()

    fun byRequest(request: Int): Ecu? = byRequest[request]

    fun inGroup(group: EcuGroup): List<Ecu> = all.filter { it.group == group }

    /** Every ECU with a route, in screen order: groups in [EcuGroup] order, ECM first. */
    val routed: List<Ecu> get() = EcuGroup.entries.flatMap { g -> all.filter { it.group == g && it.request != null } }
}
